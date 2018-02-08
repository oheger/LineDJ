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

package de.oliver_heger.linedj.archive.media

import java.nio.file.{Files, Path, Paths}

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.io.stream.ActorSource
import de.oliver_heger.linedj.io.stream.ActorSource.{ActorCompletionResult, ActorDataResult}
import de.oliver_heger.linedj.shared.archive.media.{DownloadComplete, DownloadData, DownloadDataResult}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * A test class that tests whether the download of a media file can be
  * performed using a stream. It checks the collaboration between a
  * ''MediaFileDownloadActor'' and an ''ActorSource''.
  */
class DownloadStreamSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("DownloadStreamSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Reads the content of a file as a byte array.
    *
    * @param p the path to the test file
    * @return the content of this file as byte array
    */
  private def readFile(p: Path): Array[Byte] =
    Files readAllBytes p

  "A media file download" should "be possible via a stream" in {
    val ChunkSize = 2048
    val testPath = Paths get getClass.getResource("/test.mp3").toURI
    val target = createPathInDirectory("copy.mp3")
    implicit val mat = ActorMaterializer()
    implicit val ec = system.dispatcher
    val downloadActor = system.actorOf(Props(classOf[MediaFileDownloadActor], testPath,
      ChunkSize, MediaFileDownloadActor.IdentityTransform))
    val source = ActorSource[ByteString](downloadActor, DownloadData(ChunkSize)) {
      case DownloadDataResult(data) => ActorDataResult(data)
      case DownloadComplete => ActorCompletionResult()
    }
    val sink = FileIO.toPath(target)

    val streamResult = source.runWith(sink)
    val ioRes = Await.result(streamResult, 5.seconds)
    ioRes.count should be(Files.size(testPath))
    val contentOrg = readFile(testPath)
    val contentCopy = readFile(target)
    contentCopy should be(contentOrg)
  }
}
