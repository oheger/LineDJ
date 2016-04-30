/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.facade

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerConfig}
import de.oliver_heger.linedj.player.engine.impl.{LineWriterActor, PlaybackActor}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
  * Test class for ''PlayerControl''.
  */
class PlayerControlSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("PlayerControlSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A PlayerControl" should "allow adding a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player addPlaybackContextFactory factory
    helper.probePlaybackActor.expectMsg(PlaybackActor.AddPlaybackContextFactory(factory))
  }

  it should "allow removing a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player removePlaybackContextFactory factory
    helper.probePlaybackActor.expectMsg(PlaybackActor.RemovePlaybackContextFactory(factory))
  }

  it should "allow starting playback" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.startPlayback()
    helper.probePlaybackActor.expectMsg(PlaybackActor.StartPlayback)
  }

  it should "allow stopping playback" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.stopPlayback()
    helper.probePlaybackActor.expectMsg(PlaybackActor.StopPlayback)
  }

  it should "allow stkipping the current source" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.skipCurrentSource()
    helper.probePlaybackActor.expectMsg(PlaybackActor.SkipSource)
  }

  it should "create Props for the line writer when no blocking dispatcher is defined" in {
    val config = PlayerConfig(mediaManagerActor = null, actorCreator = (props, name) => null)

    PlayerControl createLineWriterActorProps config should be(Props[LineWriterActor])
  }

  /**
    * A test helper class managing some dependencies of the test class. It also
    * provides a concrete implementation of the trait under test.
    */
  private class PlayerControlTestHelper {
    /** The test playback actor. */
    val probePlaybackActor = TestProbe()

    /**
      * Creates a test instance of ''PlayerControl''.
      *
      * @return the test instance
      */
    def createPlayerControl(): PlayerControl = new PlayerControl {
      override protected val playbackActor: ActorRef = probePlaybackActor.ref
    }
  }

}
