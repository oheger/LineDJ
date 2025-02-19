/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.media.MediumChecksum
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.io.IOException
import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
  * Test class for ''PersistentMetaDataFileScanner''.
  */
class PersistentMetadataFileScannerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfter with BeforeAndAfterAll with Matchers with FileTestHelper:
  def this() = this(ActorSystem("PersistentMetaDataFileScannerSpec"))

  after:
    tearDownTestFile()

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A PersistentMetadataFileScanner" should "find all metadata files in a directory" in:
    val FileCount = 8
    val checkSumList = (1 to FileCount) map (i => MediumChecksum(s"checksum_$i"))
    val pathList = checkSumList map (s => writeFileContent(createPathInDirectory(s.checksum + ".mdt"),
      FileTestHelper.TestData))
    // create some other files
    createDataFile()
    createDataFile()

    val expMap = checkSumList.zip(pathList).toMap
    val scanner = new PersistentMetadataFileScanner
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    val futFileMap = scanner.scanForMetadataFiles(testDirectory)
    val fileMap = Await.result(futFileMap, 5.seconds)
    fileMap should contain theSameElementsAs expMap

  it should "return a failed futire if an IO exception is thrown" in:
    import system.dispatcher
    val scanner = new PersistentMetadataFileScanner

    val futFileMap = scanner.scanForMetadataFiles(Paths get "nonExistingPath")
    intercept[IOException]:
      Await.result(futFileMap, 5.seconds)
