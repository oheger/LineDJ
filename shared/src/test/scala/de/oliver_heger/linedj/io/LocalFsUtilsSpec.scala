/*
 * Copyright 2015-2026 The Developers Team.
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

import de.oliver_heger.linedj.FileTestHelper
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.io.IOException
import java.nio.file.{Files, Path, Paths}

/**
  * Test class for ''LocalFsUtils''.
  */
class LocalFsUtilsSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  BeforeAndAfterEach, Matchers, FileTestHelper:
  def this() = this(ActorSystem("LocalFsUtilsSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()  
    super.afterEach()

  "LocalFsUtils" should "extract an extension from a file name" in:
    val File = "test.txt"

    LocalFsUtils extractExtension File should be("txt")

  it should "return the extension if a name contains multiple dots" in:
    val File = "/my/music/1. Test.Music.mp3"

    LocalFsUtils extractExtension File should be("mp3")

  it should "return an empty extension if a file does not have an extension" in:
    LocalFsUtils extractExtension "fileWithoutExtension" should be("")

  it should "extract the extension from a Path object" in:
    val File = Paths.get("some", "path", "test.txt")

    LocalFsUtils.extractExtension(File) should be("txt")

  it should "list the content of a folder" in:
    val FileCount = 8
    val paths = (1 to FileCount).map: index =>
      writeFileContent(createPathInDirectory(s"testFile$index.tst$index"), "test file " + index)
    
    LocalFsUtils.listFolder(testDirectory, system) map: files =>
      files should contain theSameElementsAs paths
      
  it should "list the content of a folder without subfolders" in:
    val FileCount = 8
    val paths = (1 to FileCount).map: index =>
      writeFileContent(createPathInDirectory(s"testFile$index.tst$index"), "test file " + index)
    Files.createDirectory(createPathInDirectory("sub1"))   
    val sub = Files.createDirectory(createPathInDirectory("sub2"))
    writeFileContent(sub.resolve("fileInSubFolder.tst"), "some test data in subfolder")

    LocalFsUtils.listFolder(testDirectory, system) map : files =>
      files should contain theSameElementsAs paths
      
  it should "list the content of a folder filtering for specific file extensions" in:
    val FileTypeCount = 4
    val testFileNames = (1 to FileTypeCount).map: index =>
      s"testFile$index."
    testFileNames.foreach: fileName =>  
      writeFileContent(createPathInDirectory(fileName + "txt"), "text file")
      writeFileContent(createPathInDirectory(fileName + "png"), "graphics file")
      writeFileContent(createPathInDirectory(fileName + "mp3"), "audio file")
  
    LocalFsUtils.listFolder(testDirectory, system, extensions = Set("txt", "mp3")) map: files =>
      val expectedPaths = testFileNames.foldRight(List.empty[Path]): (name, lst) =>
        val txtPath = testDirectory.resolve(name + "txt")
        val mp3Path = testDirectory.resolve(name + "mp3")
        mp3Path :: txtPath :: lst
      files should contain theSameElementsAs expectedPaths
      
  it should "return a failed future if listing a non-existing path" in:
    val nonExistingPath = Paths.get("non", "existing", "path")
    
    recoverToSucceededIf[IOException]:
      LocalFsUtils.listFolder(nonExistingPath, system)
