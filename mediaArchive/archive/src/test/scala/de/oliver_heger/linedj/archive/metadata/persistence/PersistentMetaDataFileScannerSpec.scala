/*
 * Copyright 2015-2019 The Developers Team.
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

import java.io.IOException
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for ''PersistentMetaDataFileScanner''.
  */
class PersistentMetaDataFileScannerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfter with BeforeAndAfterAll with Matchers with
  FileTestHelper {
  def this() = this(ActorSystem("PersistentMetaDataFileScannerSpec"))

  after {
    tearDownTestFile()
  }

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
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
    val futFileMap = scanner.scanForMetaDataFiles(testDirectory)(ActorMaterializer(),
      system.dispatcher)
    val fileMap = Await.result(futFileMap, 5.seconds)
    fileMap should contain theSameElementsAs expMap
  }

  it should "return a failed futire if an IO exception is thrown" in {
    import system.dispatcher
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val scanner = new PersistentMetaDataFileScanner

    val futFileMap = scanner.scanForMetaDataFiles(Paths get "nonExistingPath")
    intercept[IOException] {
      Await.result(futFileMap, 5.seconds)
    }
  }
}
