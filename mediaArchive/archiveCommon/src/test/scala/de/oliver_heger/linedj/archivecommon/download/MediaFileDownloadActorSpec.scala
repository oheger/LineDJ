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

package de.oliver_heger.linedj.archivecommon.download

import java.nio.file.{Path, Paths}

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, Props, Terminated}
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.shared.archive.media.{DownloadComplete, DownloadData, DownloadDataResult}
import de.oliver_heger.linedj.{FileTestHelper, SupervisionTestActor}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.duration._

object MediaFileDownloadActorSpec {
  /** Chunk size for test read operations. */
  private val ChunkSize = 16

  /** The name of a test MP3 file. */
  private val TestMp3File = "/testID3v2Data.bin"

  /**
    * Returns a source transformation function that adds a delay to a source.
    *
    * @param delay the delay to apply to the source
    * @return the transformation function
    */
  private def sourceWithDelay(delay: FiniteDuration = 25.millis):
  (Source[ByteString, Any]) => Source[ByteString, Any] =
    src => src.delay(delay, DelayOverflowStrategy.backpressure)

  /**
    * Returns a path to the test MP3 file.
    *
    * @return the path to the test MP3 file
    */
  private def pathToTestFile: Path = {
    val fileURI = getClass.getResource(TestMp3File).toURI
    Paths get fileURI
  }
}

/**
  * Test class for ''MediaFileDownloadActor''.
  */
class MediaFileDownloadActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {

  import MediaFileDownloadActorSpec._

  def this() = this(ActorSystem("MediaFileDownloadActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Creates a test actor instance for the specified parameters.
    *
    * @param path           the path of the file to be read
    * @param filterMetaData flag whether meta data is to be filtered
    * @param srcTransform   a function to transform the original source
    * @return the test actor instance
    */
  private def createDownloadActor(path: Path, filterMetaData: Boolean = false,
                                  srcTransform: Source[ByteString, Any] => Source[ByteString, Any]
                                  = identity): ActorRef = {
    val props = Props(new MediaFileDownloadActor(path, ChunkSize, filterMetaData) {
      override private[download] def createSource(): Source[ByteString, Any] =
        srcTransform(super.createSource())
    })
    val strategy = OneForOneStrategy() {
      case _ => Stop
    }
    val supervisor = SupervisionTestActor(system, strategy, props)
    supervisor.underlyingActor.childActor
  }

  /**
    * Simulates a download operation by reading a whole test file.
    *
    * @param actor     the download actor to be tested
    * @param chunkSize the chunk size to be used
    * @param delay     a delay for data processing
    * @return a byte string with the downloaded data
    */
  private def download(actor: ActorRef, chunkSize: Int = ChunkSize, delay: Int = 0): ByteString = {
    @tailrec def doDownload(data: ByteString): ByteString = {
      if (delay > 0) {
        Thread.sleep(delay)
      }
      actor ! DownloadData(chunkSize)
      expectMsgType[Any] match {
        case DownloadDataResult(block) =>
          block.length should be <= chunkSize
          doDownload(data ++ block)
        case DownloadComplete =>
          data
      }
    }

    doDownload(ByteString.empty)
  }

  "A MediaFileDownloadActor" should "read a test file" in {
    val path = createDataFile()
    val actor = createDownloadActor(path)

    val result = download(actor)
    result.toArray should be(FileTestHelper.testBytes())
  }

  it should "handle slower clients" in {
    val path = createDataFile()
    val actor = createDownloadActor(path)

    val result = download(actor, delay = 25)
    result.toArray should be(FileTestHelper.testBytes())
  }

  it should "handle a slower file source" in {
    val path = createDataFile()
    val actor = createDownloadActor(path, srcTransform = sourceWithDelay())

    val result = download(actor)
    result.toArray should be(FileTestHelper.testBytes())
  }

  it should "send repeating download complete messages for a completed download" in {
    val path = createDataFile("foo")
    val actor = createDownloadActor(path)
    download(actor)

    actor ! DownloadData(ChunkSize)
    expectMsg(DownloadComplete)
    actor ! DownloadData(ChunkSize)
    expectMsg(DownloadComplete)
  }

  it should "reject a request if one is already pending" in {
    val path = createDataFile()
    val data = ByteString("Some data")
    val probe = TestProbe()
    val actor = createDownloadActor(path, srcTransform = sourceWithDelay(delay = 1.minute))
    actor ! DownloadData(ChunkSize)

    actor.tell(DownloadData(ChunkSize), probe.ref)
    actor.tell(data, probe.ref)
    expectMsg(DownloadDataResult(data))
    probe.expectMsg(DownloadDataResult(ByteString.empty))
  }

  it should "handle smaller chunk sizes in requests" in {
    val path = createDataFile()
    val actor = createDownloadActor(path)

    val result = download(actor, chunkSize = ChunkSize / 2)
    result.toArray should be(FileTestHelper.testBytes())
  }

  it should "stop itself if an error occurs" in {
    val actor = createDownloadActor(Paths.get("aNonExistingPath.unk"))

    actor ! DownloadData(ChunkSize)
    val probe = TestProbe()
    probe watch actor
    probe.expectMsgType[Terminated]
  }

  it should "support filtering out ID3 data" in {
    val actor = createDownloadActor(pathToTestFile, filterMetaData = true)

    val result = download(actor)
    result.utf8String should startWith("Lorem ipsum")
  }

  it should "only filter out ID3 data if this is enabled" in {
    val actor = createDownloadActor(pathToTestFile)

    val result = download(actor)
    result.take(3).utf8String should be("ID3")
  }
}
