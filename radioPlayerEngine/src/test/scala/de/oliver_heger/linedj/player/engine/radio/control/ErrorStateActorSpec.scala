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

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe => TypedTestProbe}
import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.ByteString
import akka.{actor => classic}
import de.oliver_heger.linedj.player.engine.actors.{LineWriterActor, PlaybackActor, PlaybackContextFactoryActor}
import de.oliver_heger.linedj.player.engine.radio.RadioEvent
import de.oliver_heger.linedj.player.engine.radio.stream.RadioDataSourceActor
import de.oliver_heger.linedj.player.engine.{ActorCreator, PlayerConfig, PlayerEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object ErrorStateActorSpec {
  /** A prefix for actor names. */
  private val ActorNamePrefix = "ErrorCheck_"

  /** A test player configuration. */
  private val TestPlayerConfig = PlayerConfig(actorCreator = null, mediaManagerActor = null)
}

/**
  * Test class for the [[ErrorStateActor]] module.
  */
class ErrorStateActorSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(classic.ActorSystem("ErrorStateActorSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import ErrorStateActorSpec._

  "playbackActorsFactory" should "create correct playback actors" in {
    val creator = new ActorCreatorForPlaybackActors

    val result = creator.callPlaybackFactory(ErrorStateActor.playbackActorsFactory)

    result.playActor should be(creator.probePlayActor.ref)
    result.sourceActor should be(creator.probeSourceActor.ref)
  }

  it should "create a line writer actor responding to a write command" in {
    val probe = TestProbe()
    val creator = new ActorCreatorForPlaybackActors
    creator.callPlaybackFactory(ErrorStateActor.playbackActorsFactory)

    val writeCommand = LineWriterActor.WriteAudioData(line = null, data = ByteString("some data"),
      replyTo = probe.ref)
    creator.lineWriterActor ! writeCommand

    val writtenMsg = probe.expectMsgType[LineWriterActor.AudioDataWritten]
    writtenMsg.duration.toMillis should be >= 1000L
    writtenMsg.chunkLength should be >= 1024
  }

  it should "create a line writer actor responding to a drain command" in {
    val probe = TestProbe()
    val creator = new ActorCreatorForPlaybackActors
    creator.callPlaybackFactory(ErrorStateActor.playbackActorsFactory)

    creator.lineWriterActor ! LineWriterActor.DrainLine(null, probe.ref)

    probe.expectMsg(LineWriterActor.LineDrained)
  }

  /**
    * A test implementation of [[ActorCreator]] that checks whether correct
    * parameters are provided when creating the expected actors.
    */
  private class ActorCreatorForPlaybackActors extends ActorCreator {
    /** The test probe for the source actor. */
    val probeSourceActor: TestProbe = TestProbe()

    /** The test probe for the playback actor. */
    val probePlayActor: TestProbe = TestProbe()

    /** Test probe for the player event actor. */
    private val probePlayerEventActor: TypedTestProbe[PlayerEvent] = testKit.createTestProbe[PlayerEvent]()

    /** Test probe for the radio event actor. */
    private val probeRadioEventActor: TypedTestProbe[RadioEvent] = testKit.createTestProbe[RadioEvent]()

    /** Test probe for the playback context factory actor. */
    private val probeFactoryActor: TypedTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand] =
      testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** The expected configuration, enriched by this creator. */
    private val config: PlayerConfig = TestPlayerConfig.copy(actorCreator = this)

    /** Stores the line writer actor created via the factory. */
    var lineWriterActor: ActorRef[LineWriterActor.LineWriterCommand] = _

    /**
      * Invokes the given factory for playback actors with the test data
      * managed by this instance.
      *
      * @param factory the factory to invoke
      * @return the result produced by the factory
      */
    def callPlaybackFactory(factory: ErrorStateActor.PlaybackActorsFactory):
    ErrorStateActor.PlaybackActorsFactoryResult =
      factory.createPlaybackActors(ActorNamePrefix, probePlayerEventActor.ref, probeRadioEventActor.ref,
        probeFactoryActor.ref, config)

    override def createActor[T](behavior: Behavior[T],
                                name: String,
                                optStopCommand: Option[T],
                                props: Props): ActorRef[T] = {
      name should be(ActorNamePrefix + "LineWriter")
      optStopCommand shouldBe empty
      lineWriterActor = testKit.spawn(behavior).asInstanceOf[ActorRef[LineWriterActor.LineWriterCommand]]
      lineWriterActor.asInstanceOf[ActorRef[T]]
    }

    override def createActor(props: classic.Props, name: String): classic.ActorRef = {
      name match {
        case s"${ActorNamePrefix}SourceActor" =>
          val expProps = RadioDataSourceActor(config, probeRadioEventActor.ref)
          props should be(expProps)
          probeSourceActor.ref

        case s"${ActorNamePrefix}PlaybackActor" =>
          props.args should have size 5
          val expProps = PlaybackActor(config, probeSourceActor.ref, lineWriterActor,
            probePlayerEventActor.ref, probeFactoryActor.ref)
          props should be(expProps)
          probePlayActor.ref
      }
    }
  }
}
