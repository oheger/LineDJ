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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.player.engine.{AsyncAudioStreamFactory, AudioStreamFactory}
import de.oliver_heger.linedj.player.engine.AudioStreamFactory.AudioStreamPlaybackData
import de.oliver_heger.linedj.player.engine.stream.AudioEncodingStage.AudioStreamHeader
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, LineWriterStage}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.mockito.MockitoSugar

import java.io.InputStream
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import javax.sound.sampled.{AudioFormat, AudioInputStream, AudioSystem, SourceDataLine}
import scala.concurrent.Future

object AudioPlayerActorSpec:
  /** The sample rate of the fixed audio format used by tests. */
  private val TestSampleRate = 44100.0f

  /** The audio format used by the test audio stream factory. */
  private val TestFormat = new AudioFormat(TestSampleRate, 16, 2, true, false)

  /** The limit used when calling the test audio stream factory. */
  private val TestStreamFactoryLimit = AudioStreamFactory.DefaultAudioBufferSize

  /** The prefix of the request URI for a download. */
  private val FileIdPrefix = "/api/archive/files/"

  /** The suffix of the request URI for a download. */
  private val DownloadSuffix = "/download?stripMetadata=true"

  /** A timeout value when reading from a queue. */
  private val QueueTimeout = 3.seconds

  /** ID for a test media file. */
  private val TestFileID = "test-media-file-id"

  /** The file name of the test media file. */
  private val TestFileName = "audio.mp3"

  /** URI for a test media file. */
  private val TestFileUri = "test/media/file/" + TestFileName

  /** A block with test data simulating the content of a test media file. */
  private val TestData = FileTestHelper.TestData * 8
end AudioPlayerActorSpec

/**
  * Test class for [[AudioPlayerActor]].
  */
class AudioPlayerActorSpec extends ScalaTestWithActorTestKit, AnyFlatSpecLike, Matchers, MockitoSugar:

  import AudioPlayerActorSpec.*

  "An AudioPlayerActor" should "set up a correct playlist stream" in :
    val helper = new PlayerActorTestHelper

    helper.expectRequest(TestFileID, TestFileUri, TestData)
      .sendCommand(AudioPlayerActor.AudioPlayerCommand.AddAudioStreamFactory(helper.audioStreamFactory))
      .sendCommand(AudioPlayerActor.AudioPlayerCommand.AppendToPlaylist(TestFileID))
      .sendCommand(AudioPlayerActor.AudioPlayerCommand.ClosePlaylist)

    helper.checkPlaylistResult:
      case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamStart(source, ks) =>
        source should be(TestFileID)
        ks should not be null
    helper.checkPlaylistResult:
      case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamEnd(source, _) =>
        source should be(TestFileID)
    helper.requestedUris() should contain only TestFileName
    helper.playedData().utf8String should be(TestData)
    helper.playedChunks().map(_.size).sum should be(TestData.length)

  it should "handle the removal of an audio stream factory" in :
    val helper = new PlayerActorTestHelper

    helper.expectRequest(TestFileID, TestFileUri, TestData)
      .sendCommand(AudioPlayerActor.AudioPlayerCommand.AddAudioStreamFactory(helper.audioStreamFactory))
      .sendCommand(AudioPlayerActor.AudioPlayerCommand.RemoveAudioStreamFactory(helper.audioStreamFactory))
      .sendCommand(AudioPlayerActor.AudioPlayerCommand.AppendToPlaylist(TestFileID))
      .sendCommand(AudioPlayerActor.AudioPlayerCommand.ClosePlaylist)

    helper.checkPlaylistResult:
      case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamStart(source, ks) =>
        source should be(TestFileID)
        ks should not be null
    helper.checkPlaylistResult:
      case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamFailure(source, _) =>
        source should be(TestFileID)

  /**
    * A test helper class that manages the audio player actor to be tested and
    * all its dependencies.
    */
  private class PlayerActorTestHelper:
    /** The mock archive service used by the player stream. */
    private val archiveService = mock[ArchiveService]

    /** The stub for the audio stream factory used by the player stream. */
    val audioStreamFactory: AsyncAudioStreamFactory = createAudioStreamFactory()

    /** The ''AtomicReference'' storing the audio data written to the line. */
    private val refData = new AtomicReference(ByteString.empty)

    /** The media file IDs for which the audio stream factory has been queried. */
    private val refUris = new AtomicReference[List[String]](Nil)

    /** A queue that receives playlist results from the test actor. */
    private val queuePlaylistResult =
      new LinkedBlockingQueue[AudioStreamPlayerStage.PlaylistStreamResult[String, Any]]

    /** A queue that receives the played audio chunks delivered by the progress callback. */
    private val queuePlayedChunk = new LinkedBlockingQueue[LineWriterStage.PlayedAudioChunk]

    /** The actor to be tested. */
    private val testActor = createTestActor()

    /**
      * Prepares the mock archive service for a download request of the given
      * media file. The corresponding request is expected to be issued by the
      * player stream; it is answered with a response that returns a source with
      * the given audio data and a ''Content-Disposition'' header with a filename
      * derived from the file URI.
      *
      * @param fileID  the media file ID
      * @param fileUri the URI of the media file (used for the filename)
      * @param data    the audio data to be returned
      * @return this test helper
      */
    def expectRequest(fileID: String, fileUri: String, data: String): PlayerActorTestHelper =
      val downloadRequest = HttpRequest(uri = downloadUri(fileID))
      when(archiveService.sendRequest(downloadRequest)).thenReturn(Future.successful(responseFor(fileUri, data)))
      this

    /**
      * Returns the [[ByteString]] containing all the audio data that has been
      * written to the mock line so far.
      *
      * @return the aggregated audio data
      */
    def playedData(): ByteString = refData.get()

    /**
      * Returns the list with all URIs for which the audio stream factory has
      * been queried in the order in which they were received.
      *
      * @return the requested URIs in the order of the requests
      */
    def requestedUris(): List[String] = refUris.get().reverse

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command
      * @return this test helper
      */
    def sendCommand(command: AudioPlayerActor.AudioPlayerCommand): PlayerActorTestHelper =
      testActor ! command
      this

    /**
      * Reads the next result from the playlist stream and applies the given
      * partial function to it to verify its content. If the result does not
      * match the partial function, the test fails.
      *
      * @param pf the partial function performing checks on the result
      */
    def checkPlaylistResult(pf: PartialFunction[AudioStreamPlayerStage.PlaylistStreamResult[String, Any], Unit]): Unit =
      val result = readFromQueue(queuePlaylistResult)
      if !pf.isDefinedAt(result) then fail("Unexpected playlist result: " + result)
      pf(result)

    /**
      * Returns all played audio chunks that are currently available in the
      * queue without waiting for further chunks. This is useful to verify the
      * data reported by the progress callback after playback has completed.
      *
      * @return a list with the chunks stored in the queue
      */
    def playedChunks(): List[LineWriterStage.PlayedAudioChunk] =
      val buffer = scala.collection.mutable.ListBuffer.empty[LineWriterStage.PlayedAudioChunk]
      var optChunk = queuePlayedChunk.poll()
      while optChunk != null do
        buffer += optChunk
        optChunk = queuePlayedChunk.poll()
      buffer.toList

    /**
      * Returns the function that creates the mock [[SourceDataLine]] for a given
      * audio stream header. The mock records all data written to it.
      *
      * @return the function to create the mock line
      */
    private def lineCreatorFunc(): LineWriterStage.LineCreatorFunc =
      (_: AudioStreamHeader) =>
        val line = mock[SourceDataLine]
        when(line.write(any(), any(), any())).thenAnswer((invocation: InvocationOnMock) =>
          val data = invocation.getArgument[Array[Byte]](0)
          val offset = invocation.getArgument[Int](1)
          val len = invocation.getArgument[Int](2)
          refData.set(refData.get() ++ ByteString.fromArray(data, offset, len))
          len)
        line

    /**
      * Creates the stub for the audio stream factory. The factory records all
      * URIs that are passed to it. For each URI it returns an
      * [[AudioStreamPlaybackData]] whose stream creator produces an
      * [[AudioInputStream]] with the fixed test format that is backed by the
      * provided [[InputStream]].
      *
      * @return the audio stream factory stub
      */
    private def createAudioStreamFactory(): AudioStreamFactory =
      (uri: String) =>
        refUris.updateAndGet(uri :: _)
        Some(AudioStreamPlaybackData(createAudioStream, TestStreamFactoryLimit))

    /**
      * Creates an [[AudioInputStream]] with the fixed test format that is
      * backed by the given input stream.
      *
      * @param input the underlying input stream
      * @return the audio input stream
      */
    private def createAudioStream(input: InputStream): AudioInputStream =
      new AudioInputStream(input, TestFormat, AudioSystem.NOT_SPECIFIED)

    /**
      * Returns the response for a download request of a media file. The
      * response contains the given audio data and a ''Content-Disposition''
      * header with a filename derived from the file URI.
      *
      * @param fileUri the URI of the media file
      * @param data    the audio data to be returned
      * @return the response
      */
    private def responseFor(fileUri: String, data: String): HttpResponse =
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/octet-stream`, ByteString(data)),
        headers = List(`Content-Disposition`(ContentDispositionTypes.attachment,
          Map("filename" -> Paths.get(fileUri).getFileName.toString))))

    /**
      * Returns the URI of the download request for the given media file ID.
      *
      * @param fileID the media file ID
      * @return the download request URI
      */
    private def downloadUri(fileID: String): String =
      FileIdPrefix + fileID + DownloadSuffix

    /**
      * Reads a value from a queue with a timeout. Fails if no value can be 
      * read within the timeout.
      *
      * @param queue the queue to read from
      * @tparam A the element type of the queue
      * @return the value that was read
      */
    private def readFromQueue[A <: AnyRef](queue: LinkedBlockingQueue[A]): A =
      val value = queue.poll(QueueTimeout.toMillis, TimeUnit.MILLISECONDS)
      value should not be null
      value

    /**
      * Creates an actor instance to be used by the tests.
      *
      * @return the test actor instance
      */
    private def createTestActor(): ActorRef[AudioPlayerActor.AudioPlayerCommand] =
      val playlistCallback: AudioPlayerActor.PlaylistStreamResultCallback = res =>
        queuePlaylistResult.offer(res)
      val progressCallback: AudioPlayerActor.PlaybackProgressCallback = chunk =>
        queuePlayedChunk.offer(chunk)
      val actorConfig = AudioPlayerActor.Config(
        archiveService = archiveService,
        playlistCallback = playlistCallback,
        progressCallback = progressCallback,
        lineCreatorFunc = lineCreatorFunc()
      )
      testKit.spawn(AudioPlayerActor.newInstance(actorConfig))
  end PlayerActorTestHelper
