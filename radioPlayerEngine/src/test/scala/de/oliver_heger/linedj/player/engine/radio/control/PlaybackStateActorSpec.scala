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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.radio.RadioEvent
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamPlaybackActor
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDateTime
import scala.concurrent.duration.*

/**
  * Test class for [[PlaybackStateActor]].
  */
class PlaybackStateActorSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers
  with EventTestSupport[RadioEvent]:

  /** A test kit to test typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  override protected def eventTimeExtractor: RadioEvent => LocalDateTime = _.time

  "PlaybackStateActor" should "start playback if a source is available" in :
    val source = radioSource(1)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source))
      .expectNoPlaybackCommand()
      .sendCommand(PlaybackStateActor.StartPlayback)
      .expectPlaybackCommand(RadioStreamPlaybackActor.PlayRadioSource(source))
      .expectNoPlaybackCommand()

  it should "start playback if a source becomes available later" in :
    val source = radioSource(1000)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.StartPlayback)
      .expectNoPlaybackCommand()
      .sendCommand(PlaybackStateActor.PlaybackSource(source))
      .expectPlaybackCommand(RadioStreamPlaybackActor.PlayRadioSource(source))
      .expectNoPlaybackCommand()

  it should "ignore a StartPlayback message if playback is already active" in :
    val source = radioSource(2)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .skipPlaybackCommands(1)
      .sendCommand(PlaybackStateActor.StartPlayback)
      .expectNoPlaybackCommand()

  it should "stop playback" in :
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(radioSource(8)))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .skipPlaybackCommands(1)
      .sendCommand(PlaybackStateActor.StopPlayback)
      .expectPlaybackCommand(RadioStreamPlaybackActor.StopPlayback)

  it should "ignore a StopPlayback message if playback is not active" in :
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(radioSource(8)))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .sendCommand(PlaybackStateActor.StopPlayback)
      .skipPlaybackCommands(2)
      .sendCommand(PlaybackStateActor.StopPlayback)
      .expectNoPlaybackCommand()

  it should "switch to another source" in :
    val source1 = radioSource(8)
    val source2 = radioSource(16)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source1))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .skipPlaybackCommands(1)
      .sendCommand(PlaybackStateActor.PlaybackSource(source2))
      .expectPlaybackCommand(RadioStreamPlaybackActor.PlayRadioSource(source2))

  it should "return the initial current playback state" in :
    val probeClient = testKit.createTestProbe[PlaybackStateActor.CurrentPlaybackState]()
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.GetPlaybackState(probeClient.ref))

    probeClient.expectMessage(PlaybackStateActor.CurrentPlaybackState(None, None, playbackActive = false))

  it should "return the current playback state if a source is played" in :
    val source = radioSource(23)
    val probeClient = testKit.createTestProbe[PlaybackStateActor.CurrentPlaybackState]()
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .sendCommand(PlaybackStateActor.SourceSelected(source))
      .sendCommand(PlaybackStateActor.GetPlaybackState(probeClient.ref))

    probeClient.expectMessage(PlaybackStateActor.CurrentPlaybackState(Some(source), Some(source),
      playbackActive = true))

  it should "return the current playback state if a source is available, but playback is disabled" in :
    val source = radioSource(24)
    val probeClient = testKit.createTestProbe[PlaybackStateActor.CurrentPlaybackState]()
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.SourceSelected(source))
      .sendCommand(PlaybackStateActor.PlaybackSource(source))
      .sendCommand(PlaybackStateActor.GetPlaybackState(probeClient.ref))

    probeClient.expectMessage(PlaybackStateActor.CurrentPlaybackState(Some(source), Some(source),
      playbackActive = false))

  it should "return the current playback state if playback is enabled but no source is available" in :
    val probeClient = testKit.createTestProbe[PlaybackStateActor.CurrentPlaybackState]()
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.StartPlayback)
      .sendCommand(PlaybackStateActor.GetPlaybackState(probeClient.ref))

    probeClient.expectMessage(PlaybackStateActor.CurrentPlaybackState(None, None, playbackActive = false))

  /**
    * A test helper class managing an actor under test and its dependencies.
    */
  private class PlaybackActorTestHelper:
    /** Test probe for the playback actor. */
    private val probePlaybackActor = testKit.createTestProbe[RadioStreamPlaybackActor.RadioStreamPlaybackCommand]()

    /** The actor to be tested. */
    private val playbackActor =
      testKit.spawn(PlaybackStateActor.behavior(probePlaybackActor.ref))

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command
      * @return this test helper
      */
    def sendCommand(command: PlaybackStateActor.PlaybackStateCommand): PlaybackActorTestHelper =
      playbackActor ! command
      this

    /**
      * Expects that the given command is sent to the playback actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectPlaybackCommand(command: RadioStreamPlaybackActor.RadioStreamPlaybackCommand): PlaybackActorTestHelper =
      probePlaybackActor.expectMessage(command)
      this

    /**
      * Skips the given number of messages received by the playback actor.
      *
      * @param count the number of messages to skip
      * @return this test helper
      */
    def skipPlaybackCommands(count: Int): PlaybackActorTestHelper =
      (1 to count) foreach { _ =>
        probePlaybackActor.expectMessageType[RadioStreamPlaybackActor.RadioStreamPlaybackCommand]
      }
      this

    /**
      * Expects that no further message was sent to the playback actor.
      *
      * @return this test helper
      */
    def expectNoPlaybackCommand(): PlaybackActorTestHelper =
      probePlaybackActor.expectNoMessage(250.millis)
      this
