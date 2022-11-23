/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.actors

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.FileIO
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.PlayerConfig.ActorCreator
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object M3uReaderSpec {
  /** A test URI of an audio stream. */
  private val AudioStreamUri = "http://aud.io/music.mp3"

  /** A test file name pointing to a playlist which needs to be resolved. */
  private val PlaylistFile = "playlist.m3u"

  /** A reference to the resolved audio stream. */
  private val AudioStreamRef = StreamReference(AudioStreamUri)

  /** The default newline character. */
  private val Newline = "\n"
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
    * Returns a test configuration.
    *
    * @return the test configuration
    */
  private def createConfig(): PlayerConfig =
    PlayerConfig(mediaManagerActor = mock[ActorRef], actorCreator = mock[ActorCreator])

  /**
    * Creates a test m3u file with the specified content and returns a
    * reference to it. The content can consist of multiple lines; each provided
    * string is added as a single line, terminated by the given newline
    * character.
    *
    * @param newline the newline character
    * @param content the content of the file (as single lines)
    * @return a reference to the newly created file
    */
  private def createM3uFile(newline: String, content: String*): StreamReference = {
    val file = writeFileContent(createPathInDirectory(PlaylistFile), content.mkString(newline))
    StreamReference(file.toUri.toString)
  }

  /**
    * Tests an invocation of the function to resolve an audio stream.
    *
    * @param ref the reference to the audio stream
    */
  private def checkResolveAudioStream(ref: StreamReference): Unit = {
    val reader = new M3uReader()

    val result = futureResult(reader.resolveAudioStream(createConfig(), ref))

    result should be(AudioStreamRef)
  }

  "A M3uReaderActor" should "extract the audio stream URI from a simple m3u file" in {
    checkResolveAudioStream(createM3uFile(Newline, AudioStreamUri))
  }

  it should "skip comment lines" in {
    checkResolveAudioStream(createM3uFile(Newline, "# Comment", "#other comment", AudioStreamUri))
  }

  it should "skip empty lines" in {
    checkResolveAudioStream(createM3uFile(Newline, "", "#Header", AudioStreamUri))
  }

  it should "deal with \\r\\n as line separator" in {
    checkResolveAudioStream(createM3uFile("\r\n", "# Comment", "", "#other comment", AudioStreamUri))
  }

  it should "return a failed future if no stream URI is found" in {
    val ref = createM3uFile(Newline, "#only comments", "", "# and empty lines", "")
    val reader = new M3uReader()

    expectFailedFuture[NoSuchElementException](reader.resolveAudioStream(createConfig(), ref))
  }

  it should "open the stream on a blocking dispatcher" in {
    val dummyRef = createM3uFile(Newline, "anotherURI")
    val orgSource = futureResult(dummyRef.createSource())
    val ref = mock[StreamReference]
    val config = mock[PlayerConfig]
    val file = createDataFile(AudioStreamUri)
    val blockingSource = FileIO.fromPath(file)
    when(ref.uri).thenReturn(PlaylistFile)
    when(ref.createSource(any())(any())).thenReturn(Future.successful(orgSource))
    when(config.applyBlockingDispatcher(orgSource)).thenReturn(blockingSource)
    val reader = new M3uReader()

    val result = futureResult(reader.resolveAudioStream(config, ref))

    result should be(AudioStreamRef)
  }

  it should "directly return a reference that does not need to be resolved" in {
    val ref = StreamReference("stream.mp3")
    val reader = new M3uReader

    val result = futureResult(reader.resolveAudioStream(createConfig(), ref))

    result should be theSameInstanceAs ref
  }
}
