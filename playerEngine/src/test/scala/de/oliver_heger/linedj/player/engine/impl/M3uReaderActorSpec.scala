/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object M3uReaderActorSpec {
  /** A test URI of an audio stream. */
  private val AudioStreamUri = "http://aud.io/music.mp3"

  /** A reference to the resolved audio stream. */
  private val AudioStreamRef = StreamReference(AudioStreamUri)
}

/**
  * Test class for ''M3uReaderActor''.
  */
class M3uReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {

  import M3uReaderActorSpec._

  def this() = this(ActorSystem("M3uReaderActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Creates a test m3u file with the specified content and returns a
    * reference to it. The content can consist of multiple lines; each provided
    * string is added as a single line, terminated by a newline character.
    *
    * @param content the content of the file (as single lines)
    * @return a reference to the newly created file
    */
  private def createM3uFile(content: String*): StreamReference = {
    val file = createDataFile(content.mkString("\n"))
    StreamReference(file.toUri.toString)
  }

  /**
    * Tests an invocation of the test actor to resolve an audio stream.
    *
    * @param ref the reference to the audio stream
    */
  private def checkResolveAudioStream(ref: StreamReference): Unit = {
    val actor = system.actorOf(Props[M3uReaderActor])

    actor ! M3uReaderActor.ResolveAudioStream(ref)
    expectMsg(M3uReaderActor.AudioStreamResolved(ref, AudioStreamRef))
  }

  "A M3uReaderActor" should "extract the audio stream URI from a simple m3u file" in {
    checkResolveAudioStream(createM3uFile(AudioStreamUri))
  }

  it should "skip comment lines" in {
    checkResolveAudioStream(createM3uFile("# Comment", "#other comment", AudioStreamUri))
  }

  it should "skip empty lines" in {
    checkResolveAudioStream(createM3uFile("", "#Header", AudioStreamUri))
  }

  it should "throw an exception if no stream URI is found" in {
    val ref = createM3uFile("#only comments", "", "# and empty lines", "")
    val actor = TestActorRef[M3uReaderActor](Props[M3uReaderActor])

    intercept[java.io.IOException] {
      actor.underlyingActor receive M3uReaderActor.ResolveAudioStream(ref)
    }
  }
}
