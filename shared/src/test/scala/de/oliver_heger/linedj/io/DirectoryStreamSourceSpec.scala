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
import java.util
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, DelayOverflowStrategy, KillSwitches}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.TestKit
import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Test class for ''DirectoryStreamSource''.
  */
class DirectoryStreamSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfter with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("DirectoryStreamSourceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  after {
    tearDownTestFile()
  }

  /**
    * Creates a file with a given name in a given directory. The content of the
    * file is its name as string.
    *
    * @param dir  the directory
    * @param name the name of the file to be created
    * @return the path to the newly created file
    */
  private def createFile(dir: Path, name: String): Path =
    writeFileContent(dir resolve name, name)

  /**
    * Creates a directory below the given parent directory.
    *
    * @param parent the parent directory
    * @param name   the name of the new directory
    * @return the newly created directory
    */
  private def createDir(parent: Path, name: String): Path = {
    val dir = parent resolve name
    Files.createDirectory(dir)
    dir
  }

  /**
    * Creates a directory structure with some test files and directories.
    *
    * @return a map with all directories and the files created in them
    */
  private def setUpDirectoryStructure(): Map[Path, Seq[Path]] = {
    val rootFiles = List(createFile(testDirectory, "test.txt"),
      createFile(testDirectory, "noMedium1.mp3"))
    val dir1 = createDir(testDirectory, "medium1")
    val dir1Files = List(
      createFile(dir1, "noMedium2.mp3"),
      createFile(dir1, "README.TXT"),
      createFile(dir1, "medium1.settings"))
    val sub1 = createDir(dir1, "aSub1")
    val sub1Files = List(createFile(sub1, "medium1Song1.mp3"))
    val sub1Sub = createDir(sub1, "subSub")
    val sub1SubFiles = List(
      createFile(sub1Sub, "medium1Song2.mp3"),
      createFile(sub1Sub, "medium1Song3.mp3"))
    val sub2 = createDir(dir1, "anotherSub")
    val sub2Files = List(createFile(sub2, "song.mp3"))

    Map(testDirectory -> rootFiles, dir1 -> dir1Files, sub1 -> sub1Files, sub1Sub ->
      sub1SubFiles, sub2 -> sub2Files)
  }

  /**
    * Returns the transformation function used by the tests. This function
    * produces elements of type ''PathData''.
    *
    * @return the transformation function
    */
  private def transFunc: DirectoryStreamSource.TransformFunc[PathData] =
    (p, d) => PathData(p, d)

  /**
    * Returns a sink for collecting the items produced by the test source.
    *
    * @return the sink
    */
  private def foldSink(): Sink[PathData, Future[List[PathData]]] =
    Sink.fold[List[PathData], PathData](List.empty[PathData])((lst, p) => p :: lst)

  /**
    * Executes the test source on a directory structure.
    *
    * @param source the source to be run
    * @return a sequence with the paths produced by the source
    */
  private def runSource(source: Source[PathData, Any]): Seq[PathData] = {
    implicit val mat = ActorMaterializer()
    val futRun = source.runWith(foldSink())
    Await.result(futRun, 5.seconds)
  }

  /**
    * Splits the given list of path data objects into a list of directories
    * and a list of files.
    *
    * @param data the list with data
    * @return a list with directories and a list with files
    */
  private def splitDirs(data: Seq[PathData]): (Seq[PathData], Seq[PathData]) =
    data.partition(_.isDir)

  /**
    * Creates a special directory stream factory that returns stream wrapper
    * objects. All created streams are stored in a queue and can thus be
    * fetched later. So it can be checked whether they all have been closed.
    *
    * @return a tuple with the queue and the factory
    */
  private def createStreamWrapperFactory(): (BlockingQueue[DirectoryStreamWrapper],
    DirectoryStreamSource.StreamFactory) = {
    val queue = new LinkedBlockingQueue[DirectoryStreamWrapper]
    val factory: DirectoryStreamSource.StreamFactory = (p, f) => {
      val stream = new DirectoryStreamWrapper(Files.newDirectoryStream(p, f))
      queue offer stream
      stream
    }
    (queue, factory)
  }

  /**
    * Checks that all directory streams that have been created have been
    * closed.
    *
    * @param queue the queue with streams
    */
  @tailrec private def checkAllStreamsClosed(queue: BlockingQueue[DirectoryStreamWrapper]):
  Unit = {
    if (!queue.isEmpty) {
      queue.poll().closed shouldBe true
      checkAllStreamsClosed(queue)
    }
  }

  "A DirectoryStreamSource" should "return all files in the scanned directory structure" in {
    val fileData = setUpDirectoryStructure()
    val allFiles = fileData.values.flatten.toSeq
    val source = DirectoryStreamSource(testDirectory)(transFunc)
    val (_, files) = splitDirs(runSource(source))

    files map (_.path) should contain only (allFiles: _*)
  }

  it should "return all directories in the scanned directory structure" in {
    val fileData = setUpDirectoryStructure()
    val source = DirectoryStreamSource(testDirectory)(transFunc)

    val (directories, _) = splitDirs(runSource(source))
    val expDirs = fileData.keySet - testDirectory
    directories.map(_.path) should contain only (expDirs.toSeq: _*)
  }

  it should "support suppressing files with specific extensions" in {
    setUpDirectoryStructure()
    val source = DirectoryStreamSource(testDirectory,
      filter = DirectoryStreamSource.excludeExtensionsFilter(Set("TXT")))(transFunc)
    val (_, files) = splitDirs(runSource(source))

    val fileNames = files.map(f => f.path.getFileName.toString).toSet
    fileNames should not contain "README.TXT"
    fileNames should not contain "test.txt"
  }

  it should "scan directories even if suppressed by file exclusion filter" in {
    val fileData = setUpDirectoryStructure()
    val source = DirectoryStreamSource(testDirectory,
      filter = DirectoryStreamSource.excludeExtensionsFilter(Set("TXT", "")))(transFunc)

    val (directories, _) = splitDirs(runSource(source))
    val expDirs = fileData.keySet - testDirectory
    directories.map(_.path) should contain only (expDirs.toSeq: _*)
  }

  it should "close all directory streams it creates" in {
    setUpDirectoryStructure()
    val (queue, factory) = createStreamWrapperFactory()
    val source = DirectoryStreamSource(testDirectory, streamFactory = factory)(transFunc)

    runSource(source)
    queue.isEmpty shouldBe false
    checkAllStreamsClosed(queue)
  }

  it should "support canceling stream processing" in {
    implicit val mat = ActorMaterializer()
    val Count = 32
    (1 to Count).foreach(i => createFile(testDirectory, s"test$i.txt"))
    val (queue, factory) = createStreamWrapperFactory()
    val source = DirectoryStreamSource(testDirectory, streamFactory = factory)(transFunc)
    val srcDelay = source.delay(1.second, DelayOverflowStrategy.backpressure)
    val (killSwitch, futSrc) = srcDelay
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(foldSink())(Keep.both)
      .run()

    val streamWrapper = queue.poll(1, TimeUnit.SECONDS)
    killSwitch.shutdown()
    val result = Await.result(futSrc, 5.seconds)
    result.size should be < Count
    streamWrapper.closed shouldBe true
    checkAllStreamsClosed(queue)
  }
}

/**
  * Simple case class for testing whether the transformation function is
  * correctly applied.
  *
  * @param path  the wrapped path
  * @param isDir a flag whether the path is a directory
  */
case class PathData(path: Path, isDir: Boolean)

/**
  * A wrapper around a directory stream to verify that the stream is correctly
  * closed.
  *
  * @param stream the stream to be wrapped
  */
class DirectoryStreamWrapper(stream: DirectoryStream[Path]) extends DirectoryStream[Path] {
  /** Stores a flag whether the stream was closed. */
  var closed = false

  override def iterator(): util.Iterator[Path] = stream.iterator()

  override def close(): Unit = {
    stream.close()
    closed = true
  }
}
