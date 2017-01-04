/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive.metadata.persistence

import java.nio.file.Paths

import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{BeforeAndAfter, Matchers, FlatSpec}

/**
  * Test class for ''PersistentMetaDataFileScanner''.
  */
class PersistentMetaDataFileScannerSpec extends FlatSpec with BeforeAndAfter with Matchers with
  FileTestHelper {

  after {
    tearDownTestFile()
  }

  "A PersistentMetaDataFileScanner" should "find all meta data files in a directory" in {
    val FileCount = 8
    val checkSumList = (1 to FileCount) map (i => s"checksum_$i")
    val pathList = checkSumList map (s => writeFileContent(createPathInDirectory(s + ".mdt"),
      FileTestHelper.TestData))
    // create some other files
    createDataFile()
    createDataFile()

    val expMap = checkSumList.zip(pathList).toMap
    val scanner = new PersistentMetaDataFileScanner
    val fileMap = scanner scanForMetaDataFiles testDirectory
    fileMap should contain theSameElementsAs expMap
  }

  it should "return an empty map if an IO exception is thrown" in {
    val scanner = new PersistentMetaDataFileScanner

    scanner.scanForMetaDataFiles(Paths get "nonExistingPath") shouldBe 'empty
  }
}
