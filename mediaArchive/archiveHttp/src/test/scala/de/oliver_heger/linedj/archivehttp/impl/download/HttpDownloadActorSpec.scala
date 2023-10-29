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

package de.oliver_heger.linedj.archivehttp.impl.download

import de.oliver_heger.linedj.archivecommon.download.{DownloadConfig, MediaFileDownloadActor}
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.utils.ChildActorFactory
import de.oliver_heger.linedj.{FileTestHelper, StoppableTestProbe}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.apache.pekko.util.{ByteString, Timeout}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Success

object HttpDownloadActorSpec {
  /** A test transformation function passed to the test actor. */
  private val TransformFunc: MediaFileDownloadActor.DownloadTransformFunc = {
    case "mp3" => Flow[ByteString].map(bs => bs.map(b => (b + 1).toByte))
  }

  /** A test request for a medium file. */
  private val TestMediaFileRequest = MediumFileRequest(MediaFileID(MediumID("someMediumUri", Some("path")),
    "someUri"), withMetaData = true)

  /** A source simulating the data of a download file. */
  private val DownloadDataSource = Source.single(ByteString("The file content to download."))

  /** The property defining the media folder of the test archive. */
  private val MediaPath = Uri.Path("theMediaFolder")

  /** The expected creation properties for the child download actor. */
  private val ExpectedFileDownloadActorProperties = HttpFileDownloadActor(DownloadDataSource,
    TestMediaFileRequest.fileID.uri, TransformFunc)
}

/**
  * Test class for ''HttpDownloadActor''.
  */
class HttpDownloadActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("HttpDownloadActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import HttpDownloadActorSpec._

  "HttpDownloadActor" should "create correct Props" in {
    val SkipSize = 20220325
    val helper = new DownloadActorTestHelper
    val config = helper.createConfig()

    val props = HttpDownloadActor(config, TestMediaFileRequest, TransformFunc, SkipSize)
    classOf[HttpDownloadActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should contain theSameElementsInOrderAs List(config, TestMediaFileRequest, TransformFunc, SkipSize)
  }

  it should "forward an initial download request to a newly created download actor" in {
    val helper = new DownloadActorTestHelper

    helper.prepareDownloader()
      .sendRequestAndCheckForwarding()
  }

  it should "stop itself if the downloader returns a failed future" in {
    val helper = new DownloadActorTestHelper

    helper.prepareDownloader(optData = None)
      .downloadRequest(DownloadData(1024))
      .expectActorTerminated()
  }

  it should "forward further data requests to the managed download actor" in {
    val helper = new DownloadActorTestHelper

    helper.prepareDownloader()
      .sendRequestAndCheckForwarding()
      .sendRequestAndCheckForwarding()
  }

  it should "handle another download request while waiting for the server response" in {
    val promise = Promise[Source[ByteString, Any]]()
    val request1 = DownloadData(1024)
    val downloadResult = DownloadDataResult(ByteString("A result"))
    val request2 = DownloadData(2048)
    val helper = new DownloadActorTestHelper

    helper.prepareDownloader(promise.future)
      .downloadRequest(request1)
      .downloadRequest(request2)
      .expectNoForwardedDownloadRequest()
    expectMsg(MediaFileDownloadActor.ConcurrentRequestResponse)

    promise.complete(Success(DownloadDataSource))
    helper.expectForwardedDownloadRequest(request1)
      .downloadResponse(downloadResult)
    expectMsg(downloadResult)
  }

  it should "stop itself when the wrapped actor terminates" in {
    val helper = new DownloadActorTestHelper

    helper.prepareDownloader()
      .sendRequestAndCheckForwarding()
      .downloadRequest(DownloadData(128))
      .stopWrappedDownloadActor()
      .expectActorTerminated()
  }

  it should "support skipping data from the download source" in {
    val ChunkSize = 16
    val ChunksToSkip = 2
    val chunks = FileTestHelper.testBytes().grouped(ChunkSize).take(ChunksToSkip + 1).toVector
    val request = DownloadData(ChunkSize)
    val helper = new DownloadActorTestHelper(ChunksToSkip * ChunkSize)

    helper.prepareDownloader()
      .downloadRequest(request)
      .handleMultipleRequests(request, chunks)
    expectMsg(DownloadDataResult(ByteString(chunks.last)))
    helper.checkRequestServed()
  }

  it should "support skipping data from the source actor if the chunk size does not fit the skip size" in {
    val ChunkSize = 32
    val BytesToSkip = 50
    val expChunk = FileTestHelper.TestData.substring(BytesToSkip, 64)
    val chunks = FileTestHelper.testBytes().grouped(ChunkSize).take(2).toVector
    val request = DownloadData(ChunkSize)
    val helper = new DownloadActorTestHelper(BytesToSkip)

    helper.prepareDownloader()
      .downloadRequest(request)
      .handleMultipleRequests(request, chunks)
    expectMsg(DownloadDataResult(ByteString(expChunk)))
    helper.checkRequestServed()
  }

  it should "stop itself when the wrapped actor terminates while skipping" in {
    val helper = new DownloadActorTestHelper(1024)

    helper.prepareDownloader()
      .sendRequestAndCheckForwarding()
      .stopWrappedDownloadActor()
      .expectActorTerminated()
  }

  it should "handle another download request while skipping" in {
    val helper = new DownloadActorTestHelper(2048)

    helper.prepareDownloader()
      .sendRequestAndCheckForwarding()
      .downloadRequest(DownloadData(42))
    expectMsg(MediaFileDownloadActor.ConcurrentRequestResponse)
  }

  it should "handle a download completion message while skipping" in {
    val request = DownloadData(27)
    val helper = new DownloadActorTestHelper(512)

    helper.prepareDownloader()
      .downloadRequest(request)
      .handleMultipleRequests(request,
        List("foo".getBytes(StandardCharsets.UTF_8), "bar".getBytes(StandardCharsets.UTF_8)))
      .expectForwardedDownloadRequest(request)
      .sendDownloadComplete()
    expectMsg(DownloadComplete)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    *
    * @param bytesToSkip the bytes to skip to pass to the test actor
    */
  private class DownloadActorTestHelper(bytesToSkip: Long = 0) {
    /** Mock for the downloader. */
    private val downloader = mock[MediaDownloader]

    /** A test probe to represent the wrapped file download actor. */
    private val probeFileDownloadActor = StoppableTestProbe()

    /** A counter for the number of child actor creations. */
    private val childActorCount = new AtomicInteger

    /** The test actor instance. */
    private val testDownloadActor = createTestActor()

    /**
      * Creates a test archive configuration.
      *
      * @return the test configuration
      */
    def createConfig(): HttpArchiveConfig =
      HttpArchiveConfig(downloader = downloader, archiveBaseUri = Uri("/archive/base"), archiveName = "anArchive",
        contentPath = Uri.Path("someContent.json"), mediaPath = MediaPath,
        metaDataPath = Uri.Path("data"), processorTimeout = Timeout(11.seconds), processorCount = 2,
        propagationBufSize = 1024, maxContentSize = 8192, downloadBufferSize = 2222,
        downloadMaxInactivity = 12.seconds, downloadReadChunkSize = 4444, timeoutReadSize = 5555,
        downloadConfig = DownloadConfig(downloadTimeout = 22.seconds, downloadCheckInterval = 33.seconds,
          downloadChunkSize = 7777))

    /**
      * Prepares the mock downloader to expect a download request. Depending on
      * the passed in optional data, the request is either successful or fails.
      *
      * @param optData an optional source with the download result
      * @return this test helper
      */
    def prepareDownloader(optData: Option[Source[ByteString, Any]] = Some(DownloadDataSource)):
    DownloadActorTestHelper = {
      val futResult =
        optData.fold(Future.failed[Source[ByteString, Any]](new IOException("Error from HTTP archive!"))) { src =>
          Future.successful(src)
        }
      prepareDownloader(futResult)
    }

    /**
      * Prepares the mock downloader to expect a download request, which is
      * answered using the given result.
      *
      * @param result the result of the download request
      * @return this test helper
      */
    def prepareDownloader(result: Future[Source[ByteString, Any]]): DownloadActorTestHelper = {
      when(downloader.downloadMediaFile(MediaPath, TestMediaFileRequest.fileID.uri)).thenReturn(result)
      this
    }

    /**
      * Passes the given download request to the test actor.
      *
      * @param request the request
      * @return this test helper
      */
    def downloadRequest(request: DownloadData): DownloadActorTestHelper =
      sendMessage(request)

    /**
      * Checks that the given request was forwarded to the wrapped download
      * actor.
      *
      * @param request the expected request
      * @return this test helper
      */
    def expectForwardedDownloadRequest(request: DownloadData): DownloadActorTestHelper = {
      probeFileDownloadActor.expectMsg(request)
      this
    }

    /**
      * Combines sending a download request with expecting of the forwarding.
      *
      * @return this test helper
      */
    def sendRequestAndCheckForwarding(): DownloadActorTestHelper = {
      val request = DownloadData(1234)
      downloadRequest(request)
      expectForwardedDownloadRequest(request)
    }

    /**
      * Tests that no download request was forwarded to the wrapped download
      * actor.
      *
      * @return this test helper
      */
    def expectNoForwardedDownloadRequest(): DownloadActorTestHelper = {
      val probeMsg = new Object
      probeFileDownloadActor.ref ! probeMsg
      probeFileDownloadActor.expectMsg(probeMsg)
      this
    }

    /**
      * Simulates a result of a download request from the wrapped download
      * actor.
      *
      * @param result the result to be sent
      * @return this test helper
      */
    def downloadResponse(result: DownloadDataResult): DownloadActorTestHelper = {
      probeFileDownloadActor.reply(result)
      this
    }

    /**
      * Sends a message about a completed download to the test actor.
      *
      * @return this test helper
      */
    def sendDownloadComplete(): DownloadActorTestHelper =
      sendMessage(DownloadComplete)

    /**
      * Expects that multiple requests are sent to the download actor and
      * answers them using the specified chunks. For each chunk one request is
      * expected.
      *
      * @param request the request expected for each chunk
      * @param chunks  the sequence of chunks
      * @return this test helper
      */
    def handleMultipleRequests(request: DownloadData, chunks: Iterable[Array[Byte]]): DownloadActorTestHelper = {
      chunks.foreach { chunk =>
        expectForwardedDownloadRequest(request)
        downloadResponse(DownloadDataResult(ByteString(chunk)))
      }
      this
    }

    /**
      * Tests whether the actor under test is in a state that it can handle
      * download requests.
      *
      * @return this test helper
      */
    def checkRequestServed(): DownloadActorTestHelper = {
      val nextResponse = DownloadDataResult(ByteString("more data"))
      sendRequestAndCheckForwarding()
      downloadResponse(nextResponse)
      expectMsg(nextResponse)
      this
    }

    /**
      * Tests whether the actor under test has terminated.
      *
      * @return this test helper
      */
    def expectActorTerminated(): DownloadActorTestHelper = {
      val watcherProbe = TestProbe()
      watcherProbe watch testDownloadActor
      watcherProbe.expectTerminated(testDownloadActor)
      this
    }

    /**
      * Stops the wrapped download actor to test whether this scenario is
      * handled.
      *
      * @return this test helper
      */
    def stopWrappedDownloadActor(): DownloadActorTestHelper = {
      probeFileDownloadActor.stop()
      this
    }

    /**
      * Sends the given message to the test actor.
      *
      * @param msg the message
      * @return this test helper
      */
    private def sendMessage(msg: Any): DownloadActorTestHelper = {
      testDownloadActor ! msg
      this
    }

    /**
      * Creates a test actor instance.
      *
      * @return the test actor
      */
    private def createTestActor(): ActorRef =
      system.actorOf(Props(new HttpDownloadActor(createConfig(), TestMediaFileRequest, TransformFunc, bytesToSkip)
        with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          childActorCount.incrementAndGet() should be(1)
          p should be(ExpectedFileDownloadActorProperties)
          probeFileDownloadActor.ref
        }
      }))
  }
}
