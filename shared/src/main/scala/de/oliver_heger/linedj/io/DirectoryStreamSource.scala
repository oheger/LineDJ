/*
 * Copyright 2015-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.io

import java.io.IOException
import java.nio.file.{DirectoryStream, Files, Path}
import java.util.Locale

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import de.oliver_heger.linedj.io.DirectoryStreamSource.{PathFilter, StreamFactory, TransformFunc}
import scalaz.State

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

object DirectoryStreamSource {
  /** Constant for an undefined file extension. */
  private val NoExtension = ""

  /** Constant for the extension delimiter character. */
  private val Dot = '.'

  /**
    * Extracts the file extension from the given path.
    *
    * @param path the path
    * @return the extracted extension
    */
  def extractExtension(path: Path): String = {
    val fileName = path.getFileName.toString
    val pos = fileName lastIndexOf Dot
    if (pos >= 0) fileName.substring(pos + 1)
    else NoExtension
  }

  /**
    * A class representing a filter for the entries encountered by a
    * [[DirectoryStreamSource]].
    *
    * When creating a ''DirectoryStreamSource'' it is possible to specify a
    * filter object. Only entries in the directory to be processed that pass
    * this filter are emitted to the stream.
    *
    * The class is a simple predicate. It defines some boolean operators that
    * allow combining more complex expressions based on simple ones.
    *
    * @param accept a function whether a path in the directory is accepted
    */
  case class PathFilter(accept: Path => Boolean) {
    /**
      * Generates a new ''PathFilter'' that accepts a path if and only if it is
      * accepted by this filter OR by the other filter.
      *
      * @param other the filter to be combined
      * @return the resulting filter implementing an OR
      */
    def ||(other: PathFilter): PathFilter =
      PathFilter(p => accept(p) || other.accept(p))

    /**
      * Generates a new ''PathFilter'' that accepts a path if and only if it is
      * accepted by this filter AND by the other filter.
      *
      * @param other the filter to be combined
      * @return the resulting filter implementing an AND
      */
    def &&(other: PathFilter): PathFilter =
      PathFilter(p => accept(p) && other.accept(p))

    /**
      * Generates a new ''PathFilter'' that accepts exactly the opposite paths
      * than this filter.
      *
      * @return the resulting filter implementing the negation of this one
      */
    def negate(): PathFilter = PathFilter(!accept(_))
  }

  /**
    * A conversion function which generates a ''DirectoryStream.Filter'' out of
    * a ''PathFilter''.
    *
    * @param filter the ''PathFilter''
    * @return the equivalent ''DirectoryStream.Filter''
    */
  implicit def toDirFilter(filter: PathFilter): DirectoryStream.Filter[Path] =
    new DirectoryStream.Filter[Path] {
      override def accept(entry: Path): Boolean = filter.accept(entry)
    }

  /**
    * A ''PathFilter'' implementation that accepts all entries in a directory.
    * This filter is used if no special filter has been provided. It ensures
    * that a whole directory structure is scanned.
    */
  val AcceptAllFilter: PathFilter = PathFilter(_ => true)

  /**
    * A ''PathFilter'' implementation that accepts sub directories. Note that
    * when using a filter other than ''AcceptAllFilter'', the directory source
    * only scans the current directory. To scan a whole directory structure,
    * this filter has to be integrated using the ''||'' operator.
    */
  val AcceptSubdirectoriesFilter: PathFilter =
    PathFilter(p => Files isDirectory p)

  /**
    * A special filter that excludes files with specific extensions.
    *
    * @param exclusions a set with file extensions to be excluded
    */
  private class ExcludeExtensionsFilter(exclusions: Set[String])
    extends DirectoryStream.Filter[Path] {

    override def accept(entry: Path): Boolean =
      Files.isDirectory(entry) ||
        !exclusions.contains(extractExtension(entry).toUpperCase(Locale.ENGLISH))
  }

  /**
    * An internally used data class for storing data about a directory
    * stream. The class stores the original reference to the stream and the
    * iterator for the current iteration.
    *
    * @param stream   the stream
    * @param iterator the iterator
    */
  private case class DirectoryStreamRef(stream: DirectoryStream[Path],
                                        iterator: java.util.Iterator[Path]) {
    /**
      * Closes the underlying stream ignoring all exceptions.
      */
    def close(): Unit = {
      try {
        stream.close()
      } catch {
        case _: IOException => // ignore
      }
    }
  }

  /**
    * Case class representing the state of a BFS iteration.
    *
    * @param optCurrentStream option for the currently active stream
    * @param dirsToProcess    a list with directories to be processed
    */
  private case class BFSState(optCurrentStream: Option[DirectoryStreamRef],
                              dirsToProcess: Queue[Path])

  /**
    * A data class representing an iteration over a directory in DFS mode.
    * In this mode, the files of the current directory are processed first. If
    * a sub directory is encountered, it is stored in a list for later
    * traversal. After all entries of the current directory have been visited,
    * the list with sub directories is processed.
    *
    * @param optStream option for the directory stream
    * @param subDirs   the list with sub directories of the current directory
    */
  private case class DFSCurrentDir(optStream: Option[DirectoryStreamRef], subDirs: List[Path]) {
    /**
      * Closes the stream for this directory if it is still open.
      */
    def close(): Unit = {
      optStream foreach (_.close())
    }
  }

  /**
    * Case class representing the state of a DFS iteration.
    *
    * @param streams a stack with all directory streams that are currently
    *                processed
    */
  private case class DFSState(streams: List[DFSCurrentDir])

  /**
    * Definition of the transformation function used by the source to transform
    * a ''Path'' into the desired target type. The function receives the path
    * and a flag whether it is a directory. It has to create a corresponding
    * output object that will be emitted downstream.
    *
    * @tparam A the type of the result objects produced by the source
    */
  type TransformFunc[A] = (Path, Boolean) => A

  /**
    * Definition of a function serving as stream factory. Such a function can
    * be provided when creating a source. This can be used to influence the
    * creation of directory streams.
    */
  type StreamFactory = (Path, PathFilter) => DirectoryStream[Path]

  /**
    * A default function for creating a ''DirectoryStream''. This function
    * just delegates to the ''Files'' class.
    *
    * @param path the path in question
    * @return a ''DirectoryStream'' for this path
    */
  def createDirectoryStream(path: Path, filter: PathFilter): DirectoryStream[Path] =
    Files.newDirectoryStream(path, filter)

  /**
    * Returns a filter that includes only files with extensions in the set
    * specified (ignoring case). Note: In order to support matching of file
    * extensions ignoring case, the extensions must be provided in uppercase.
    *
    * @param extensions the set with the extensions to be included
    * @return a filter which includes files with these extensions
    */
  def includeExtensionsFilter(extensions: Set[String]): PathFilter =
    PathFilter { entry =>
      extensions.contains(extractExtension(entry).toUpperCase(Locale.ROOT))
    }

  /**
    * Returns a filter that excludes files with the specified extensions from
    * the result produced by the source. Note: In order to support matching of
    * file extensions ignoring case, the extensions must be provided in
    * uppercase.
    *
    * @param extensions the set with extensions to be excluded
    * @return a filter which excludes files with these extensions
    */
  def excludeExtensionsFilter(extensions: Set[String]): PathFilter =
    includeExtensionsFilter(extensions).negate()

  /**
    * Creates a new source that returns the content of the specified root
    * directory in BFS order.
    *
    * @param root          the root directory to be processed
    * @param filter        the filter for elements encountered
    * @param streamFactory a factory for creating directory streams
    * @param f             the transformation function
    * @tparam A the type of the elements produced by this source
    * @return the new source
    */
  def newBFSSource[A](root: Path, filter: PathFilter = AcceptAllFilter,
                      streamFactory: StreamFactory = createDirectoryStream)
                     (f: TransformFunc[A]): Source[A, NotUsed] = {
    val dirSrc = new DirectoryStreamSource[A](root, filter, streamFactory)(f) {
      override type IterationState = BFSState

      override protected val iterationFunc: State[IterationState, Option[A]] =
        State(state => iterateBFS(filter, streamFactory, f)(state))

      override protected val initialState: IterationState = BFSState(None, Queue(root))

      override protected def iterationComplete(state: BFSState): Unit = {
        state.optCurrentStream foreach (_.close())
      }
    }
    Source fromGraph dirSrc
  }

  /**
    * Creates a new source that returns the content of the specified root
    * directory in BFS order.
    *
    * @param root          the root directory to be processed
    * @param filter        the filter for elements encountered
    * @param streamFactory a factory for creating directory streams
    * @param f             the transformation function
    * @tparam A the type of the elements produced by this source
    * @return the new source
    */
  def newDFSSource[A](root: Path, filter: PathFilter = AcceptAllFilter,
                      streamFactory: StreamFactory = createDirectoryStream)
                     (f: TransformFunc[A]): Source[A, NotUsed] =
    Try {
      new DirectoryStreamSource[A](root, filter, streamFactory)(f) {
        override type IterationState = DFSState

        override protected val iterationFunc: State[IterationState, Option[A]] =
          State(state => iterateDFS(filter, streamFactory, f)(state))

        override protected val initialState: IterationState =
          DFSState(List(DFSCurrentDir(subDirs = Nil,
            optStream = Some(createStreamRef(root, streamFactory, filter)))))

        override protected def iterationComplete(state: DFSState): Unit = {
          state.streams foreach (_.close())
        }
      }
    } match {
      case Success(src) => Source fromGraph src
      case Failure(e) => Source.failed(e)
    }

  /**
    * The iteration function for BFS traversal. This function processes
    * directories on the current level first before sub directories are
    * iterated over.
    *
    * @param filter        a filter to be applied to the directory stream
    * @param streamFactory the function for creating directory streams
    * @param f             the transformation function
    * @param state         the current state of the iteration
    * @tparam A the type of items produced by this source
    * @return a tuple with the new current directory stream, the new list of
    *         pending directories, and the next element to emit
    */
  @tailrec private def iterateBFS[A](filter: PathFilter, streamFactory: StreamFactory,
                                     f: TransformFunc[A])(state: BFSState):
  (BFSState, Option[A]) = {
    state.optCurrentStream match {
      case Some(ref) =>
        if (ref.iterator.hasNext) {
          val path = ref.iterator.next()
          val isDir = Files isDirectory path
          val elem = Some(f(path, isDir))
          if (isDir) (BFSState(state.optCurrentStream, state.dirsToProcess enqueue path), elem)
          else (state, elem)
        } else {
          ref.close()
          iterateBFS(filter, streamFactory, f)(BFSState(None, state.dirsToProcess))
        }

      case None =>
        state.dirsToProcess.dequeueOption match {
          case Some((p, q)) =>
            iterateBFS(filter, streamFactory, f)(BFSState(Some(createStreamRef(p, streamFactory,
              filter)), q))
          case _ =>
            (BFSState(None, Queue()), None)
        }
    }
  }

  /**
    * The iteration function for DFS traversal. This function processes the
    * files of the current directory first, and then recursively descends into
    * all sub directories.
    *
    * @param filter        a filter to be applied to the directory stream
    * @param streamFactory the function for creating directory streams
    * @param f             the transformation function
    * @param state         the current state of the iteration
    * @tparam A the type of items produced by this source
    * @return a tuple with the new current directory stream, the new list of
    *         pending directories, and the next element to emit
    */
  @tailrec private def iterateDFS[A](filter: PathFilter, streamFactory: StreamFactory,
                                     f: TransformFunc[A])(state: DFSState):
  (DFSState, Option[A]) = {
    state.streams match {
      case h :: t =>
        h.optStream match {
          case Some(stream) =>
            if (stream.iterator.hasNext) {
              val path = stream.iterator.next()
              val isDir = Files isDirectory path
              val elem = Some(f(path, isDir))
              if (isDir) {
                val nextDir = h.copy(subDirs = path :: h.subDirs)
                (DFSState(nextDir :: t), elem)
              }
              else (state, elem)
            } else {
              h.close()
              val nextDir = h.copy(optStream = None)
              iterateDFS(filter, streamFactory, f)(DFSState(nextDir :: t))
            }

          case None =>
            h.subDirs match {
              case dh :: dt =>
                val nextDir = DFSCurrentDir(Some(createStreamRef(dh, streamFactory, filter)), Nil)
                val lastDir = h.copy(subDirs = dt)
                iterateDFS(filter, streamFactory, f)(DFSState(nextDir :: lastDir :: t))
              case _ =>
                iterateDFS(filter, streamFactory, f)(DFSState(t))
            }
        }

      case _ =>
        (DFSState(Nil), None)
    }
  }


  /**
    * Uses the specified ''StreamFactory'' to create a ''DirectoryStreamRef''
    * for the specified path.
    *
    * @param p       the path
    * @param factory the ''StreamFactory''
    * @param filter  the filter
    * @return the new ''DirectoryStreamRef''
    */
  private def createStreamRef(p: Path, factory: StreamFactory, filter: PathFilter):
  DirectoryStreamRef = {
    val stream = factory(p, filter)
    DirectoryStreamRef(stream, stream.iterator())
  }
}

/**
  * A stream source for traversing a directory structure.
  *
  * This source generates items for all files and directories below a given
  * root folder. A transformation function can be specified to transform the
  * encountered ''Path'' objects to a target type. It is also possible to
  * specify a filter to control which items are to be processed; that way it is
  * possible for instance to limit the iteration to a single directory only or
  * to specific sub directories or files. Note that the root directory itself
  * is not part of the output of this source.
  *
  * There are factory methods for creating instances of this source that return
  * elements in a specific order. In BFS mode all directories on a specific
  * level are processed first before sub directories are handled. In DFS mode
  * iteration navigates to sub directories first before it continues with the
  * current directory.
  *
  * This source makes use of the ''DirectoryStream'' API from Java nio. It
  * thus uses blocking operations.
  *
  * @param root   the root directory to be scanned
  * @param filter a filter to be applied to the directory stream
  * @param f      the transformation function
  * @tparam A the type of items produced by this source
  */
abstract class DirectoryStreamSource[A](val root: Path,
                                        val filter: PathFilter,
                                        streamFactory: StreamFactory)
                                       (f: TransformFunc[A])
  extends GraphStage[SourceShape[A]] {

  /** Type definition for the current state of the iteration. */
  type IterationState

  val out: Outlet[A] = Outlet("DirectoryStreamSource")

  override def shape: SourceShape[A] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** The current state of the iteration. */
      private var currentState = initialState

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          val (nextState, optElem) = iterationFunc(currentState)
          optElem match {
            case Some(e) =>
              push(out, e)
              currentState = nextState
            case None =>
              complete(out)
          }
        }

        override def onDownstreamFinish(): Unit = {
          iterationComplete(currentState)
          super.onDownstreamFinish()
        }
      })
    }

  /**
    * Returns the iteration function. This function is used to obtain the next
    * stream element based on the current state of the iteration.
    *
    * @return the iteration function
    */
  protected val iterationFunc: State[IterationState, Option[A]]

  /**
    * Returns the initial state of the iteration. This value is obtained during
    * initialization.
    *
    * @return the initial state of the iteration
    */
  protected def initialState: IterationState

  /**
    * Callback function to indicate that stream processing is now finished. A
    * concrete implementation should free all resources that are still in use.
    *
    * @param state the current iteration state
    */
  protected def iterationComplete(state: IterationState): Unit
}
