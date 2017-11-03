/*
 * Copyright 2015-2017 The Developers Team.
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
import akka.pattern.AskTimeoutException
import akka.stream.scaladsl.Sink
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport}
import de.oliver_heger.linedj.player.engine.impl.PlayerFacadeActor.{NoDelay, TargetActor, TargetDownloadActor, TargetPlaybackActor}
import de.oliver_heger.linedj.player.engine.impl._
import de.oliver_heger.linedj.player.engine.{AudioSourcePlaylistInfo, PlayerConfig}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

object AudioPlayerSpec {
  /** The name of the dispatcher for blocking actors. */
  private val BlockingDispatcherName = "TheBlockingDispatcher"
}

/**
  * Test class for ''AudioPlayer''.
  */
class AudioPlayerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with
  BeforeAndAfterAll with Matchers with FileTestHelper with MockitoSugar {

  import AudioPlayerSpec._

  def this() = this(ActorSystem("AudioPlayerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  "An AudioPlayer" should "support adding playlist info objects to the playlist" in {
    val info = AudioSourcePlaylistInfo(MediaFileID(MediumID("someMedium", None), "someURI"), 0, 0)
    val helper = new AudioPlayerTestHelper

    helper.player addToPlaylist info
    helper.expectFacadeMessage(info, TargetDownloadActor)
  }

  it should "support an overloaded method of adding songs to the playlist" in {
    val info = AudioSourcePlaylistInfo(MediaFileID(MediumID("someMedium", None), "someURI"),
      20160413222120L, 20160413222133L)
    val helper = new AudioPlayerTestHelper

    helper.player.addToPlaylist(info.sourceID.mediumID, info.sourceID.uri, info.skip, info.skipTime)
    helper.expectFacadeMessage(info, TargetDownloadActor)
  }

  it should "set default values for skip properties in addToPlaylist()" in {
    val info = AudioSourcePlaylistInfo(MediaFileID(MediumID("someMedium", None), "someURI"), 0, 0)
    val helper = new AudioPlayerTestHelper

    helper.player.addToPlaylist(info.sourceID.mediumID, info.sourceID.uri)
    helper.expectFacadeMessage(info, TargetDownloadActor)
  }

  it should "support closing the playlist" in {
    val helper = new AudioPlayerTestHelper

    helper.player.closePlaylist()
    helper.expectFacadeMessage(SourceDownloadActor.PlaylistEnd, TargetDownloadActor)
  }

  it should "support starting playback" in {
    val helper = new AudioPlayerTestHelper

    helper.player.startPlayback()
    helper.expectFacadeMessage(PlaybackActor.StartPlayback, TargetPlaybackActor)
  }

  it should "support stopping playback" in {
    val helper = new AudioPlayerTestHelper

    helper.player.stopPlayback()
    helper.expectFacadeMessage(PlaybackActor.StopPlayback, TargetPlaybackActor)
  }

  it should "allow skipping the current source" in {
    val helper = new AudioPlayerTestHelper

    helper.player.skipCurrentSource()
    helper.expectFacadeMessage(PlaybackActor.SkipSource, TargetPlaybackActor)
  }

  it should "correctly implement the close() method" in {
    val helper = new AudioPlayerTestHelper
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(100.milliseconds)
    intercept[AskTimeoutException] {
      Await.result(helper.player.close(), 1.second)
    }
    helper.expectActorsClosed()
  }

  it should "pass the event actor to the super class" in {
    val helper = new AudioPlayerTestHelper
    val sink = Sink.ignore

    helper.player.registerEventSink(sink)
    helper.eventActor.expectMsgType[EventManagerActor.RegisterSink]
  }

  it should "allow resetting the engine" in {
    val helper = new AudioPlayerTestHelper

    helper.player.reset()
    helper.expectReset()
  }

  /**
    * A test helper class collecting all required dependencies.
    */
  private class AudioPlayerTestHelper {
    /** Test probe for the line writer actor. */
    private val lineWriterActor = TestProbe()

    /** Test probe for the facade actor. */
    private val facadeActor = TestProbe()

    /** Test probe for the event actor. */
    val eventActor = TestProbe()

    /** The test player configuration. */
    val config = createPlayerConfig()

    /** The test player instance. */
    val player = AudioPlayer(config)

    /**
      * Checks that a message was sent to the facade actor.
      *
      * @param msg         the message
      * @param targetActor the target of the message
      * @param delay       the delay
      * @return this test helper
      */
    def expectFacadeMessage(msg: Any, targetActor: TargetActor,
                            delay: FiniteDuration = NoDelay): AudioPlayerTestHelper = {
      facadeActor.expectMsg(PlayerFacadeActor.Dispatch(msg, targetActor, delay))
      this
    }

    /**
      * Expects a reset message sent to the facade actor.
      *
      * @return this test helper
      */
    def expectReset(): AudioPlayerTestHelper = {
      facadeActor.expectMsg(PlayerFacadeActor.ResetEngine)
      this
    }

    /**
      * Expects that close requests have been sent to the affected actors.
      *
      * @return this test helper
      */
    def expectActorsClosed(): AudioPlayerTestHelper = {
      facadeActor.expectMsg(CloseRequest)
      this
    }

    /**
      * An actor creator function. This implementation checks the parameters
      * passed to the several actors and returns test probes.
      *
      * @param props the properties for the new actor
      * @param name  the actor name
      * @return an actor reference
      */
    private def actorCreatorFunc(props: Props, name: String): ActorRef = {
      name match {
        case "lineWriterActor" =>
          classOf[LineWriterActor] isAssignableFrom props.actorClass() shouldBe true
          props.dispatcher should be(BlockingDispatcherName)
          props.args should have size 0
          lineWriterActor.ref

        case "eventManagerActor" =>
          props.actorClass() should be(classOf[EventManagerActor])
          props.args should have size 0
          eventActor.ref

        case "playerFacadeActor" =>
          classOf[PlayerFacadeActor] isAssignableFrom props.actorClass() shouldBe true
          classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
          classOf[CloseSupport] isAssignableFrom props.actorClass() shouldBe true
          props.args should be(List(config, eventActor.ref, lineWriterActor.ref))
          facadeActor.ref
      }
    }

    /**
      * Creates a test audio player configuration.
      *
      * @return the test configuration
      */
    private def createPlayerConfig(): PlayerConfig =
      PlayerConfig(mediaManagerActor = null, actorCreator = actorCreatorFunc,
        blockingDispatcherName = Some(BlockingDispatcherName))
  }

}
