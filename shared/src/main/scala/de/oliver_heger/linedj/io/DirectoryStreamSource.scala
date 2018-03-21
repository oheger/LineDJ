/*
 * Copyright 2015-2018 The Developers Team.
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

import java.nio.file.{DirectoryStream, Files, Path}
import java.util.Locale

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import de.oliver_heger.linedj.io.DirectoryStreamSource.{DirectoryStreamRef, PathFilter, StreamFactory, TransformFunc}

import scala.annotation.tailrec

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
                                        iterator: java.util.Iterator[Path])

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
    * Creates a ''Source'' for reading the content of a directory structure.
    *
    * @param root          the root directory to be scanned
    * @param filter        a filter to be applied to the directory stream
    * @param streamFactory the function for creating directory streams
    * @param f             the transformation function
    * @tparam A the type of items produced by this source
    * @return the newly created ''Source''
    */
  def apply[A](root: Path, filter: PathFilter = AcceptAllFilter,
               streamFactory: StreamFactory = createDirectoryStream)
              (f: TransformFunc[A]): Source[A, NotUsed] = {
    val dirSource = new DirectoryStreamSource[A](root, filter, streamFactory)(f)
    Source fromGraph dirSource
  }
}

/**
  * A stream source for traversing a directory structure.
  *
  * This source generates items for all files and directories below a given
  * root folder. A transformation function can be specified to transform the
  * encountered ''Path'' objects to a target type. The order in which elements
  * are visited is not specified. Note that the root directory itself is not
  * part of the output of this source.
  *
  * This source makes use of the ''DirectoryStream'' API from Java nio. It
  * thus uses blocking operations.
  *
  * @param root   the root directory to be scanned
  * @param filter a filter to be applied to the directory stream
  * @param f      the transformation function
  * @tparam A the type of items produced by this source
  */
class DirectoryStreamSource[A](val root: Path,
                               val filter: PathFilter,
                               streamFactory: StreamFactory)
                              (f: TransformFunc[A])
  extends GraphStage[SourceShape[A]] {
  val out: Outlet[A] = Outlet("DirectoryStreamSource")

  override def shape: SourceShape[A] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** Holds the currently processed directory stream. */
      private var optCurrentStream: Option[DirectoryStreamRef] = None

      /** The list with directories to be processed. */
      private var dirsToProcess = List(root)

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          val (optStream, pendingDirs, optElem) = iterate(optCurrentStream, dirsToProcess)
          optElem match {
            case Some(e) =>
              push(out, e)
              optCurrentStream = optStream
              dirsToProcess = pendingDirs
            case None =>
              complete(out)
          }
        }

        override def onDownstreamFinish(): Unit = {
          optCurrentStream foreach (_.stream.close())
          super.onDownstreamFinish()
        }
      })
    }

  /**
    * The iteration function. Updates the current state of the source and
    * returns the next element to emit if any. If the end of the iteration is
    * reached, a ''None'' element is returned.
    *
    * @param optStream   option for the current directory stream
    * @param pendingDirs a list with the directories to be processed
    * @return a tuple with the new current directory stream, the new list of
    *         pending directories, and the next element to emit
    */
  @tailrec private def iterate(optStream: Option[DirectoryStreamRef],
                               pendingDirs: List[Path]):
  (Option[DirectoryStreamRef], List[Path], Option[A]) = {
    optStream match {
      case Some(ref) =>
        if (ref.iterator.hasNext) {
          val path = ref.iterator.next()
          val isDir = Files isDirectory path
          val elem = Some(f(path, isDir))
          if (isDir) (optStream, path :: pendingDirs, elem)
          else (optStream, pendingDirs, elem)
        } else {
          ref.stream.close()
          iterate(None, pendingDirs)
        }

      case None =>
        pendingDirs match {
          case h :: t =>
            val stream = streamFactory(h, filter)
            iterate(Some(DirectoryStreamRef(stream, stream.iterator())), t)
          case _ =>
            (None, Nil, None)
        }
    }
  }
}

