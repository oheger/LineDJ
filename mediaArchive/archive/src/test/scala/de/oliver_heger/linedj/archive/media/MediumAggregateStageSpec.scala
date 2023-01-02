/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.file.{Path, Paths}

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

object MediumAggregateStageSpec {
  /** The name of the root directory in the test directory structure. */
  private val RootName = "archiveRoot"

  /** The root directory of the test archive. */
  private val RootPath = Paths get RootName

  /** The name of the media archive. */
  private val ArchiveName = "MyTestArchive"

  /** Prefix for medium directories. */
  private val MediumDirPrefix = "medium"

  /** A list with the names of sub directories for path generation. */
  private val SubDirectories = List("sub1", "Sub2", "sub3")

  /** The name of a settings (medium description) file. */
  private val SettingsName = "playlist.settings"

  /**
    * Generates a ''Path'' for a directory in the test archive structure.
    *
    * @param mediumIdx the index of the medium
    * @param subLevel  the sub directory level
    * @return the resulting path
    */
  private def generateDirPath(mediumIdx: Int, subLevel: Int): Path =
    Paths.get(RootName, (MediumDirPrefix + mediumIdx) :: (SubDirectories take subLevel): _*)

  /**
    * Generates a ''Path'' for a test file in the directory structure to be
    * passed. This method generates the path for a file on the given medium,
    * on a specific sub directory level, and with the given name.
    *
    * @param mediumIdx the index of the medium
    * @param subLevel  the sub directory level
    * @param name      the name of the file
    * @return the resulting path
    */
  private def generateFilePath(mediumIdx: Int, subLevel: Int, name: String): Path =
    generateDirPath(mediumIdx, subLevel) resolve name

  /**
    * Generates a ''FileData'' object for the specified path.
    *
    * @param path the path
    * @return the new ''FileData'' object
    */
  private def createFileData(path: Path): FileData =
    FileData(path, path.toString.length)

  /**
    * Generates a ''Path'' representing a media file in the test directory
    * structure.
    *
    * @param mediumIdx the index of the medium
    * @param subLevel  the sub directory level
    * @param songIdx   the index of the media file
    * @return the resulting path
    */
  private def generateMediaFilePath(mediumIdx: Int, subLevel: Int, songIdx: Int): Path =
    generateFilePath(mediumIdx, subLevel, s"song$songIdx.mp3")

  /**
    * Generates a ''Path'' representing a medium description file in the test
    * directory structure.
    *
    * @param mediumIdx the index of the medium
    * @param subLevel  the sub directory level
    * @return the resulting path
    */
  private def generateSettingsPath(mediumIdx: Int, subLevel: Int = 0): Path =
    generateFilePath(mediumIdx, subLevel, SettingsName)

  /**
    * Generates a URI relative to the test root path that corresponds to the
    * given path.
    *
    * @param path the path
    * @return the relative URI for this path
    */
  private def relativeUri(path: Path): String = {
    def toComponents(it: java.util.Iterator[Path]): List[String] =
      if (it.hasNext) it.next().toString :: toComponents(it)
      else Nil

    val relativePath = RootPath.relativize(path)
    toComponents(relativePath.iterator).mkString("/")
  }

  /**
    * Generates the ID of a medium based on the given path of the medium
    * description file.
    *
    * @param settingsPath the path to the settings file
    * @return the medium ID
    */
  private def generateMediumID(settingsPath: Path): MediumID = {
    val mediumUri = relativeUri(settingsPath.getParent)
    val settingsUri = relativeUri(settingsPath)
    MediumID(mediumUri, Some(settingsUri), ArchiveName)
  }

  /**
    * Generates a ''MediaScanResult'' object that contains the paths of the
    * given medium.
    *
    * @param mid   the medium ID
    * @param paths the list of paths
    * @return the scan result
    */
  private def generateScanResult(mid: MediumID, paths: Path*): MediaScanResult = {
    val files = paths.map(createFileData).toList
    MediaScanResult(RootPath, Map(mid -> files))
  }

  /**
    * Combines the given results to a combined media scan result containing the
    * file information of both source results.
    *
    * @param sr1 scan result 1
    * @param sr2 scan result 2
    * @return the combined scan result
    */
  private def combineScanResults(sr1: MediaScanResult, sr2: MediaScanResult): MediaScanResult =
    sr1.copy(mediaFiles = sr1.mediaFiles ++ sr2.mediaFiles)
}

/**
  * Test class for ''MediumAggregateStage''.
  */
class MediumAggregateStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("MediumAggregateStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  import MediumAggregateStageSpec._

  /**
    * Checks whether a scan result contains the expected data. The order of
    * files is irrelevant; therefore, a special comparision function has to be
    * used.
    *
    * @param expected the expected scan result
    * @param actual   the actual scan result
    */
  private def checkScanResult(expected: MediaScanResult, actual: MediaScanResult): Unit = {
    actual.root should be(expected.root)
    actual.mediaFiles.keySet should contain theSameElementsAs expected.mediaFiles.keySet
    expected.mediaFiles.keys foreach { mid =>
      actual.mediaFiles(mid) should contain theSameElementsAs expected.mediaFiles(mid)
    }
  }

  /**
    * Runs a stream with the stage under test using the given files as input
    * and returns the result passed to the sink.
    *
    * @param files the files to be processed
    * @return the result from the sink
    */
  private def runStream(files: List[Path]): List[MediaScanResult] = {
    val source = Source(files)
    val stage = new MediumAggregateStage(RootPath, ArchiveName, new PathUriConverter(RootPath), createFileData)
    val sink = Sink.fold[List[MediaScanResult], MediaScanResult](List.empty)((lst, res) =>
      res :: lst)
    val futStream = source.via(stage).runWith(sink)
    Await.result(futStream, 5.seconds).reverse
  }

  "A MediumAggregateStage" should "handle an empty directory structure" in {
    val result = runStream(List.empty)
    result should be(List(MediaScanResult(RootPath, Map.empty)))
  }

  it should "use a correct default file data converter function" in {
    val path = createDataFile()
    val stage = new MediumAggregateStage(RootPath, ArchiveName, new PathUriConverter(RootPath))

    stage.fileDataFactory(path) should be(FileData(path, FileTestHelper.TestData.length))
  }

  it should "aggregate the files of a single medium" in {
    val settingsPath = generateSettingsPath(1)
    val song1 = generateMediaFilePath(1, 1, 1)
    val song2 = generateMediaFilePath(1, 1, 2)
    val song3 = generateMediaFilePath(1, 2, 3)
    val mid = generateMediumID(settingsPath)
    val expScanResult = generateScanResult(mid, song1, song2, song3)

    val result = runStream(List(settingsPath, song1, song2, song3))
    result should have size 1
    checkScanResult(expScanResult, result.head)
  }

  it should "aggregate the files of multiple media" in {
    val settings1 = generateSettingsPath(1)
    val settings2 = generateSettingsPath(2)
    val mid1 = generateMediumID(settings1)
    val mid2 = generateMediumID(settings2)
    val song1 = generateMediaFilePath(1, 1, 1)
    val song2 = generateMediaFilePath(1, 1, 2)
    val song3 = generateMediaFilePath(2, 1, 3)
    val song4 = generateMediaFilePath(2, 2, 4)
    val song5 = generateMediaFilePath(2, 3, 5)

    val result = runStream(List(settings1, song1, song2, settings2, song3, song4, song5))
    result should have size 2
    checkScanResult(generateScanResult(mid1, song1, song2), result.head)
    checkScanResult(generateScanResult(mid2, song3, song4, song5), result(1))
  }

  it should "deal with nested media" in {
    val settings1 = generateSettingsPath(1)
    val settings2 = generateSettingsPath(1, subLevel = 1)
    val settings3 = generateSettingsPath(2)
    val mid1 = generateMediumID(settings1)
    val mid2 = generateMediumID(settings2)
    val mid3 = generateMediumID(settings3)
    val song1 = generateMediaFilePath(1, 1, 1)
    val song2 = generateMediaFilePath(1, 2, 2)
    val song3 = generateMediaFilePath(1, 1, 3)
    val song4 = generateMediaFilePath(2, 1, 4)

    val result = runStream(List(settings1, song1, settings2, song2, song3, settings3, song4))
    result should have size 2
    val expRes1 = combineScanResults(generateScanResult(mid2, song2),
      generateScanResult(mid1, song1, song3))
    checkScanResult(expRes1, result.head)
    checkScanResult(generateScanResult(mid3, song4), result(1))
  }

  it should "support the undefined medium" in {
    val settings = generateSettingsPath(1)
    val mid = generateMediumID(settings)
    val song1 = RootPath resolve "TopLevelSong.mp3"
    val song2 = generateMediaFilePath(1, 1, 2)
    val song3 = generateMediaFilePath(2, 0, 3)
    val song4 = generateMediaFilePath(2, 0, 4)
    val song5 = generateMediaFilePath(3, 1, 5)

    val result = runStream(List(song1, settings, song2, song3, song4, song5))
    result should have size 2
    checkScanResult(generateScanResult(mid, song2), result.head)
    val midUndef = MediumID(RootPath.toString, None, ArchiveName)
    checkScanResult(generateScanResult(midUndef, song1, song3, song4, song5), result(1))
  }
}
