/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.stream

import de.oliver_heger.linedj.player.engine.stream.PausePlaybackStage.PlaybackState
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.{BeforeAndAfterAll, Succeeded}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
  * Test class for the stage implementation provided by [[PausePlaybackStage]].
  */
class PausePlaybackStageSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with Matchers with BeforeAndAfterAll:
  def this() = this(classic.ActorSystem("PausePlaybackStageSpec"))

  /** The testkit for dealing with typed actors. */
  private val testKit = ActorTestKit()

  /** The typed actor system in implicit scope required by the stage. */
  private given typedActorSystem: ActorSystem[Nothing] = system.toTyped

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()

  "Pause playback stage" should "pass data through the stream if playback is enabled" in :
    val audioData = List(ByteString("chunk1"), ByteString("chunk"), ByteString("anotherChunk"))
    val pauseActor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPossible))
    val source = Source(audioData)
    val sink = Sink.fold[List[ByteString], ByteString](Nil) { (list, chunk) => chunk :: list }
    val stage = PausePlaybackStage.pausePlaybackStage[ByteString](Some(pauseActor))

    source.via(stage).runWith(sink) map { result =>
      result.reverse should contain theSameElementsInOrderAs audioData
    }

  it should "pass not data through the stream if playback is paused" in :
    val pauseActor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPaused))
    val probe = testKit.createTestProbe[ByteString]
    val source = Source(List(ByteString("foo"), ByteString("bar"), ByteString("baz")))
    val sink = Sink.foreach[ByteString](probe.ref ! _)
    val stage = PausePlaybackStage.pausePlaybackStage[ByteString](Some(pauseActor))

    source.via(stage).runWith(sink)

    probe.expectNoMessage(500.millis)
    Succeeded

  it should "handle an undefined pause actor" in :
    val audioData = List(ByteString("chunk1"), ByteString("chunk"), ByteString("anotherChunk"))
    val source = Source(audioData)
    val sink = Sink.fold[List[ByteString], ByteString](Nil) { (list, chunk) => chunk :: list }
    val stage = PausePlaybackStage.pausePlaybackStage[ByteString](None)

    source.via(stage).runWith(sink) map { result =>
      result.reverse should contain theSameElementsInOrderAs audioData
    }
