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

package de.oliver_heger.linedj.player.engine.facade

import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport}
import de.oliver_heger.linedj.player.engine.actors.ActorCreatorForEventManagerTests.{ActorCheckFunc, ClassicActorCheckFunc}
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.{NoDelay, TargetActor, TargetPlaybackActor, TargetSourceReader}
import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.*
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, FishingOutcomes}
import org.apache.pekko.actor.typed.Props
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Await
import scala.concurrent.duration.*

object AudioPlayerSpec:
  /** The name of the dispatcher for blocking actors. */
  private val BlockingDispatcherName = "TheBlockingDispatcher"

/**
  * Test class for ''AudioPlayer''.
  */
class AudioPlayerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper with MockitoSugar with AsyncTestHelper:

  import AudioPlayerSpec._

  def this() = this(ActorSystem("AudioPlayerSpec"))

  /** The test kit for typed actors. */
  private val testKit = ActorTestKit()

  import system.dispatcher

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
    tearDownTestFile()

  /**
    * Creates an [[AudioPlayerTestHelper]] test helper object and runs the
    * given test function with it. Afterward, the player is closed, so that all
    * resources are freed.
    *
    * @param test the test function
    */
  private def runTest(test: AudioPlayerTestHelper => Unit): Unit =
    given Timeout = Timeout(100.milliseconds)

    val helper = new AudioPlayerTestHelper
    try
      test(helper)
    finally
      helper.player.close()

  "An AudioPlayer" should "support adding playlist info objects to the playlist" in :
    val info = AudioSourcePlaylistInfo(MediaFileID(MediumID("someMedium", None), "someURI"), 0, 0)
    runTest { helper =>
      helper.player addToPlaylist info
      helper.expectFacadeMessage(info, TargetSourceReader("AudioPlayer.DownloadActor"))
    }

  it should "support an overloaded method of adding songs to the playlist" in :
    val info = AudioSourcePlaylistInfo(MediaFileID(MediumID("someMedium", None), "someURI"),
      20160413222120L, 20160413222133L)
    runTest { helper =>
      helper.player.addToPlaylist(info.sourceID.mediumID, info.sourceID.uri, info.skip, info.skipTime)
      helper.expectFacadeMessage(info, TargetSourceReader("AudioPlayer.DownloadActor"))
    }

  it should "set default values for skip properties in addToPlaylist()" in :
    val info = AudioSourcePlaylistInfo(MediaFileID(MediumID("someMedium", None), "someURI"), 0, 0)
    runTest { helper =>
      helper.player.addToPlaylist(info.sourceID.mediumID, info.sourceID.uri)
      helper.expectFacadeMessage(info, TargetSourceReader("AudioPlayer.DownloadActor"))
    }

  it should "support closing the playlist" in :
    runTest { helper =>
      helper.player.closePlaylist()
      helper.expectFacadeMessage(SourceDownloadActor.PlaylistEnd, TargetSourceReader("AudioPlayer.DownloadActor"))
    }

  it should "support starting playback" in :
    runTest { helper =>
      helper.player.startPlayback()
      helper.expectFacadeMessage(PlaybackActor.StartPlayback, TargetPlaybackActor)
    }

  it should "support stopping playback" in :
    runTest { helper =>
      helper.player.stopPlayback()
      helper.expectFacadeMessage(PlaybackActor.StopPlayback, TargetPlaybackActor)
    }

  it should "allow skipping the current source" in :
    runTest { helper =>
      helper.player.skipCurrentSource()
      helper.expectFacadeMessage(PlaybackActor.SkipSource, TargetPlaybackActor)
    }

  it should "allow resetting the engine" in :
    runTest { helper =>
      helper.player.reset()

      helper.expectMessageToFacadeActor(PlayerFacadeActor.ResetEngine)
    }

  it should "correctly implement the close() method" in :
    val helper = new AudioPlayerTestHelper
    implicit val timeout: Timeout = Timeout(100.milliseconds)
    intercept[AskTimeoutException]:
      Await.result(helper.player.close(), 1.second)
    helper.expectActorsClosed()

  it should "pass the event actor to the super class" in :
    val probeListener = testKit.createTestProbe[PlayerEvent]()
    runTest { helper =>
      helper.player.addEventListener(probeListener.ref)

      helper.actorCreator.probeEventActor.fishForMessagePF(3.seconds):
        case EventManagerActor.RegisterListener(listener) if listener == probeListener.ref =>
          FishingOutcomes.complete
        case _ => FishingOutcomes.continueAndIgnore
    }

  it should "pass the schedule actor to the super class" in :
    val Delay = 10.minutes
    runTest { helper =>
      helper.player.startPlayback(Delay)

      val command = helper.expectScheduleCommand()

      command.delay should be(Delay)
    }

  it should "create a correct audio stream factory" in :
    runTest { helper =>
      // As long as the factory is not actually used, it can only be tested that no exception occurs when adding a
      // child factory.
      helper.player.addAudioStreamFactory(mock)
    }

  /**
    * A test helper class collecting all required dependencies.
    */
  private class AudioPlayerTestHelper:
    /**
      * The function to check the classic actors created during tests.
      */
    private val classicActorChecks: ClassicActorCheckFunc = props => {
      case "playerFacadeActor" =>
        classOf[PlayerFacadeActor] isAssignableFrom props.actorClass() shouldBe true
        classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
        classOf[CloseSupport] isAssignableFrom props.actorClass() shouldBe true
        props.args should be(List(config, actorCreator.probePublisherActor.ref, scheduleActor.ref,
          factoryActor.ref, lineWriterActor.ref, AudioPlayer.AudioPlayerSourceCreator))
        facadeActor.ref
    }

    /** The function to check the typed actors created during tests. */
    private val typedActorChecks: ActorCheckFunc = (_, optStop, props) => {
      case "schedulerActor" =>
        props should be(Props.empty)
        optStop should be(Some(ScheduledInvocationActor.Stop))
        scheduleActor.ref

      case "playbackContextFactoryActor" =>
        props should be(Props.empty)
        optStop should be(Some(PlaybackContextFactoryActor.Stop))
        factoryActor.ref

      case "lineWriterActor" =>
        props should not be Props.empty // It seems impossible to extract the dispatcher name.
        lineWriterActor.ref
    }

    /** The stub implementation for creating actors. */
    val actorCreator: ActorCreatorForEventManagerTests[PlayerEvent] = createActorCreator()

    /** Test probe for the line writer actor. */
    private val lineWriterActor = testKit.createTestProbe[LineWriterActor.LineWriterCommand]()

    /** Test probe for the facade actor. */
    private val facadeActor = TestProbe()

    /** Test probe for the scheduler actor. */
    private val scheduleActor = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the playback context factory actor. */
    private val factoryActor = testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** The test player configuration. */
    val config: PlayerConfig = createPlayerConfig()

    /** The test player instance. */
    val player: AudioPlayer = futureResult(AudioPlayer(config))

    /**
      * Expects that the specified message has been sent to the facade actor.
      *
      * @param msg the message
      * @return this test helper
      */
    def expectMessageToFacadeActor(msg: Any): AudioPlayerTestHelper =
      facadeActor.expectMsg(msg)
      this

    /**
      * Checks that a message was sent to the facade actor.
      *
      * @param msg         the message
      * @param targetActor the target of the message
      * @param delay       the delay
      * @return this test helper
      */
    def expectFacadeMessage(msg: Any, targetActor: TargetActor,
                            delay: FiniteDuration = NoDelay): AudioPlayerTestHelper =
      expectMessageToFacadeActor(PlayerFacadeActor.Dispatch(msg, targetActor, delay))

    /**
      * Checks that a command was sent to the scheduler actor.
      *
      * @return the received command
      */
    def expectScheduleCommand(): ScheduledInvocationActor.ActorInvocationCommand =
      scheduleActor.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]

    /**
      * Expects that close requests have been sent to the affected actors.
      *
      * @return this test helper
      */
    def expectActorsClosed(): AudioPlayerTestHelper =
      facadeActor.expectMsg(CloseRequest)
      this

    /**
      * Creates a stub [[ActorCreator]] for the configuration of the test
      * player. This implementation checks the parameters passed to actors and
      * returns test probes for them.
      *
      * @return the stub [[ActorCreator]]
      */
    private def createActorCreator(): ActorCreatorForEventManagerTests[PlayerEvent] =
      new ActorCreatorForEventManagerTests[PlayerEvent](testKit, "eventManagerActor",
        customClassicChecks = classicActorChecks, customChecks = typedActorChecks) with Matchers

    /**
      * Creates a test audio player configuration.
      *
      * @return the test configuration
      */
    private def createPlayerConfig(): PlayerConfig =
      PlayerConfigSpec.TestPlayerConfig.copy(actorCreator = actorCreator,
        blockingDispatcherName = Some(BlockingDispatcherName))
