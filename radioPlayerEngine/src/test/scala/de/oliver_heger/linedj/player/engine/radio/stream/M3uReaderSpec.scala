/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.stream

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamTestHelper.MonitoringStream
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import scala.concurrent.Future

object M3uReaderSpec {
  /** A test URI of an audio stream. */
  private val AudioStreamUri = "http://aud.io/music.mp3"

  /** A test URI pointing to a playlist which needs to be resolved. */
  private val PlaylistUri = "https://www.example.org/music/playlist.m3u"

  /** The default newline character. */
  private val Newline = "\n"

  /**
    * Generates an ''HttpResponse'' with an entity based on the given source.
    *
    * @param source the source with the content of the entity
    * @return the ''HttpResponse'' with this entity
    */
  private def responseWithContent(source: Source[ByteString, Any]): HttpResponse =
    HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, source))
}

/**
  * Test class for ''M3uReader''.
  */
class M3uReaderSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with MockitoSugar
  with FileTestHelper with AsyncTestHelper {

  import M3uReaderSpec._
  import system.dispatcher

  def this() = this(ActorSystem("M3uReaderSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  override protected def afterEach(): Unit = {
    tearDownTestFile()
    super.afterEach()
  }

  /**
    * Creates a mock [[HttpStreamLoader]] that is prepared to handle a request
    * for the playlist stream reference. It yields a successful response with
    * the specified content.
    *
    * @param content the content source for the response
    * @return the mock stream loader
    */
  private def createStreamLoaderMock(content: Source[ByteString, Any]): HttpStreamLoader = {
    val loader = mock[HttpStreamLoader]

    val expRequest = HttpRequest(uri = PlaylistUri)
    val response = responseWithContent(content)
    when(loader.sendRequest(expRequest)).thenReturn(Future.successful(response))

    loader
  }

  /**
    * Creates a mock [[HttpStreamLoader]] that is prepared to handle a request
    * for the playlist stream reference by returning a response with multiple
    * lines of text with a specific newline character.
    *
    * @param newline the newline character
    * @param content the content of the file (as single lines)
    * @return the mock stream loader
    */
  private def createStreamLoaderMock(newline: String, content: String*): HttpStreamLoader = {
    val data = ByteString(content.mkString(newline)).grouped(128)
    val source = Source(data.toList)
    createStreamLoaderMock(source)
  }

  /**
    * Tests an invocation of the function to resolve an audio stream.
    *
    * @param loader the mock stream loader to use
    */
  private def checkResolveAudioStream(loader: HttpStreamLoader): Unit = {
    val reader = new M3uReader(loader)

    val result = futureResult(reader.resolveAudioStream(PlaylistUri))

    result should be(AudioStreamUri)
  }

  "A M3uReaderActor" should "extract the audio stream URI from a simple m3u file" in {
    checkResolveAudioStream(createStreamLoaderMock(Newline, AudioStreamUri))
  }

  it should "skip comment lines" in {
    checkResolveAudioStream(createStreamLoaderMock(Newline, "# Comment", "#other comment", AudioStreamUri))
  }

  it should "skip empty lines" in {
    checkResolveAudioStream(createStreamLoaderMock(Newline, "", "#Header", AudioStreamUri))
  }

  it should "deal with \\r\\n as line separator" in {
    checkResolveAudioStream(createStreamLoaderMock("\r\n", "# Comment", "", "#other comment", AudioStreamUri))
  }

  it should "return a failed future if no stream URI is found" in {
    val loader = createStreamLoaderMock(Newline, "#only comments", "", "# and empty lines", "")
    val reader = new M3uReader(loader)

    expectFailedFuture[NoSuchElementException](reader.resolveAudioStream(PlaylistUri))
  }

  it should "handle a failed result from the stream loader" in {
    val exception = new IOException("Test exception: Could not load stream :-(")
    val loader = mock[HttpStreamLoader]
    when(loader.sendRequest(any())).thenReturn(Future.failed(exception))
    val reader = new M3uReader(loader)

    val actualException =
      expectFailedFuture[IOException](reader.resolveAudioStream(PlaylistUri))
    actualException should be(exception)
  }

  it should "directly return a reference that does not need to be resolved" in {
    val loader = mock[HttpStreamLoader]
    val reader = new M3uReader(loader)

    val result = futureResult(reader.resolveAudioStream(AudioStreamUri))

    result should be theSameInstanceAs AudioStreamUri
    verifyNoInteractions(loader)
  }

  it should "only process streams up to a certain size" in {
    val stream = new MonitoringStream("# An infinite stream with non m3u data." + Newline)
    val streamSource = RadioStreamTestHelper.createSourceFromStream(stream = stream, chunkSze = 512)
    val loader = createStreamLoaderMock(streamSource)
    val reader = new M3uReader(loader)

    expectFailedFuture[IllegalStateException](reader.resolveAudioStream(PlaylistUri))
    stream.expectReadsUntil(M3uReader.MaxM3uStreamSize)
    stream.bytesCount.get() should be < 2L * M3uReader.MaxM3uStreamSize
  }
}
