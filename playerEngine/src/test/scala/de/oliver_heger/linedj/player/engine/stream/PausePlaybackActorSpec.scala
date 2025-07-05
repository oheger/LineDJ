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

package de.oliver_heger.linedj.player.engine.stream

import de.oliver_heger.linedj.ActorTestKitSupport
import org.scalatest.Inspectors.forAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration.*

/**
  * Test class for the pause playback actor implementation in [[PausePlaybackStage]].
  */
class PausePlaybackActorSpec extends AnyFlatSpec with Matchers with ActorTestKitSupport:

  import PausePlaybackStage.PlaybackState

  "Pause playback actor" should "report the initial playback state" in :
    val client = testKit.createTestProbe[PausePlaybackStage.CurrentPlaybackState]()
    val actor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPossible))

    actor ! PausePlaybackStage.GetCurrentPlaybackState(client.ref)

    client.expectMessage(PausePlaybackStage.CurrentPlaybackState(PlaybackState.PlaybackPossible))

  it should "report an updated playback state" in :
    val client = testKit.createTestProbe[PausePlaybackStage.CurrentPlaybackState]()
    val queryMessage = PausePlaybackStage.GetCurrentPlaybackState(client.ref)
    val actor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPaused))

    def expectPlaybackState(expectedState: PlaybackState) =
      actor ! queryMessage
      client.expectMessage(PausePlaybackStage.CurrentPlaybackState(expectedState))

    expectPlaybackState(PlaybackState.PlaybackPaused)

    actor ! PausePlaybackStage.StartPlayback
    expectPlaybackState(PlaybackState.PlaybackPossible)

    actor ! PausePlaybackStage.StopPlayback
    expectPlaybackState(PlaybackState.PlaybackPaused)

  it should "reply to a WaitForPlaybackPossible command when playback is possible" in :
    val client = testKit.createTestProbe[PausePlaybackStage.PlaybackPossible]()
    val actor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPossible))

    actor ! PausePlaybackStage.WaitForPlaybackPossible(client.ref)

    client.expectMessage(PausePlaybackStage.PlaybackPossible())

  it should "not reply to a WaitForPlaybackPossible command when playback is paused" in :
    val client = testKit.createTestProbe[PausePlaybackStage.PlaybackPossible]()
    val actor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPaused))

    actor ! PausePlaybackStage.WaitForPlaybackPossible(client.ref)

    client.expectNoMessage(500.millis)

  it should "answer pending WaitForPlaybackPossible commands when playback is started" in :
    val client1 = testKit.createTestProbe[PausePlaybackStage.PlaybackPossible]()
    val client2 = testKit.createTestProbe[PausePlaybackStage.PlaybackPossible]()
    val client3 = testKit.createTestProbe[PausePlaybackStage.PlaybackPossible]()
    val clients = List(client1, client2, client3)
    val actor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPaused))

    clients.foreach { client => actor ! PausePlaybackStage.WaitForPlaybackPossible(client.ref) }
    actor ! PausePlaybackStage.StartPlayback

    forAll(clients) { client =>
      client.expectMessage(PausePlaybackStage.PlaybackPossible())
    }

  it should "terminate on receiving a Stop command" in :
    val watcher = testKit.createDeadLetterProbe()
    val actor = testKit.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPaused))

    actor ! PausePlaybackStage.Stop

    watcher.expectTerminated(actor)

