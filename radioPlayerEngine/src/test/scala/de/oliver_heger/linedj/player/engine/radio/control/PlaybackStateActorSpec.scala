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

package de.oliver_heger.linedj.player.engine.radio.control

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.testkit.{TestKit, TestProbe}
import akka.{actor => classic}
import de.oliver_heger.linedj.player.engine.actors.{DelayActor, PlaybackActor, PlayerFacadeActor}
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
  * Test class for [[PlaybackStateActor]].
  */
class PlaybackStateActorSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(classic.ActorSystem("PlaybackStateActorSpec"))

  /** A test kit to test typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
    super.afterAll()
  }

  "PlaybackStateActor" should "start playback if a source is available" in {
    val source = radioSource(1)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source))
      .expectNoMessage()
      .sendCommand(PlaybackStateActor.StartPlayback)
      .expectPropagatedMessages(PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader()),
        PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor))
      .expectNoMessage()
  }

  it should "start playback if a source becomes available later" in {
    val source = radioSource(1000)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.StartPlayback)
      .expectNoMessage()
      .sendCommand(PlaybackStateActor.PlaybackSource(source))
      .expectPropagatedMessages(PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader()),
        PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor))
      .expectNoMessage()
  }

  it should "ignore a StartPlayback message if playback is already active" in {
    val source = radioSource(2)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .skipMessages(1)
      .sendCommand(PlaybackStateActor.StartPlayback)
      .expectNoMessage()
  }

  it should "stop playback" in {
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(radioSource(8)))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .skipMessages(1)
      .sendCommand(PlaybackStateActor.StopPlayback)
      .expectPropagatedMessages(
        PlayerFacadeActor.Dispatch(PlaybackActor.StopPlayback, PlayerFacadeActor.TargetPlaybackActor))
  }

  it should "ignore a StopPlayback message if playback is not active" in {
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(radioSource(8)))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .sendCommand(PlaybackStateActor.StopPlayback)
      .skipMessages(2)
      .sendCommand(PlaybackStateActor.StopPlayback)
      .expectNoMessage()
  }

  it should "reset the player engine when restarting playback" in {
    val source = radioSource(16)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .sendCommand(PlaybackStateActor.StopPlayback)
      .skipMessages(2)
      .sendCommand(PlaybackStateActor.StartPlayback)
      .expectPropagatedMessages(PlayerFacadeActor.ResetEngine,
        PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader()),
        PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor))
  }

  it should "reset the player engine when switching to another source" in {
    val source1 = radioSource(8)
    val source2 = radioSource(16)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(PlaybackStateActor.PlaybackSource(source1))
      .sendCommand(PlaybackStateActor.StartPlayback)
      .skipMessages(1)
      .sendCommand(PlaybackStateActor.PlaybackSource(source2))
      .expectPropagatedMessages(PlayerFacadeActor.ResetEngine,
        PlayerFacadeActor.Dispatch(source2, PlayerFacadeActor.TargetSourceReader()),
        PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor))
  }

  /**
    * A test helper class managing an actor under test and its dependencies.
    */
  private class PlaybackActorTestHelper {
    /** Test probe for the facade actor. */
    private val probeFacadeActor = TestProbe()

    /** The actor to be tested. */
    private val playbackActor = testKit.spawn(PlaybackStateActor.behavior(probeFacadeActor.ref))

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command
      * @return this test helper
      */
    def sendCommand(command: PlaybackStateActor.PlaybackStateCommand): PlaybackActorTestHelper = {
      playbackActor ! command
      this
    }

    /**
      * Expects that the facade actor was sent a propagate message with the
      * given sub-messages.
      *
      * @param messages the expected messages
      * @return this test helper
      */
    def expectPropagatedMessages(messages: Any*): PlaybackActorTestHelper = {
      val propagate = probeFacadeActor.expectMsgType[DelayActor.Propagate]
      propagate.sendData.foreach { send =>
        send._2 should be(probeFacadeActor.ref)
      }
      propagate.delay should be(PlayerControl.NoDelay)
      propagate.sendData.map(_._1).toList should contain theSameElementsInOrderAs messages
      this
    }

    /**
      * Skips the given number of messages received by the facade actor.
      *
      * @param count the number of messages to skip
      * @return this test helper
      */
    def skipMessages(count: Int): PlaybackActorTestHelper = {
      (1 to count) foreach { _ =>
        probeFacadeActor.expectMsgType[Any]
      }
      this
    }

    /**
      * Expects that no further message was sent to the facade actor.
      *
      * @return this test helper
      */
    def expectNoMessage(): PlaybackActorTestHelper = {
      probeFacadeActor.expectNoMessage(250.millis)
      this
    }
  }
}
