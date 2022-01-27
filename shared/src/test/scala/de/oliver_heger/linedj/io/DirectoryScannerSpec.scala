/*
 * Copyright 2015-2022 The Developers Team.
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
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/**
 * Test class for ''DirectoryScanner''.
 */
class DirectoryScannerSpec extends AnyFlatSpec with Matchers with BeforeAndAfter with FileTestHelper {

  after {
    tearDownTestFile()
  }

  /**
   * Creates a file with a given name in a given directory. The content of the
   * file is its name as string.
   * @param dir the directory
   * @param name the name of the file to be created
   * @return the path to the newly created file
   */
  private def createFile(dir: Path, name: String): Path =
    writeFileContent(dir resolve name, name)

  /**
   * Creates a directory below the given parent directory.
   * @param parent the parent directory
   * @param name the name of the new directory
   * @return the newly created directory
   */
  private def createDir(parent: Path, name: String): Path = {
    val dir = parent resolve name
    Files.createDirectory(dir)
    dir
  }

  /**
   * Creates a directory structure with some test files and directories.
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
    * Extracts the file name of a path.
    *
    * @param path the path
    * @return the file name
    */
  private def fileName(path: Path): String =  path.getFileName.toString

  "A DirectoryScanner" should "return all files in the scanned directory structure" in {
    val fileData = setUpDirectoryStructure()
    val allFiles = fileData.values.flatten.toSeq
    val scanner = new DirectoryScanner

    val result = scanner scan testDirectory
    result.files map (_.path) should contain only (allFiles: _*)
  }

  it should "return correct file sizes" in {
    setUpDirectoryStructure()
    val scanner = new DirectoryScanner(Set.empty)

    val result = scanner scan testDirectory
    val wrongSizes = result.files filter (d => fileName(d.path).length != d.size)
    wrongSizes shouldBe empty
  }

  it should "support suppressing files with specific extensions" in {
    setUpDirectoryStructure()
    val scanner = new DirectoryScanner(Set("TXT"))

    val result = scanner scan testDirectory
    val fileNames = result.files.map(f => fileName(f.path)).toSet
    fileNames should not contain "README.TXT"
    fileNames should not contain "test.txt"
  }

  it should "return all directories in the scanned directory structure" in {
    val fileData = setUpDirectoryStructure()
    val scanner = new DirectoryScanner(Set("TXT"))

    val result = scanner scan testDirectory
    result.directories should contain only (fileData.keys.toSeq: _*)
  }

  it should "return directories in order of visiting them" in {
    setUpDirectoryStructure()
    val scanner = new DirectoryScanner

    val result = scanner scan testDirectory
    result.directories.head should be(testDirectory)
  }
}
