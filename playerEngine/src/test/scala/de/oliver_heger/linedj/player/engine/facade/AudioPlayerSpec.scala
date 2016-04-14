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
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.player.engine.impl._
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerConfig}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object AudioPlayerSpec {
  /** Prefix for buffer files. */
  private val BufferFilePrefix = "TestBufferFilePrefix"

  /** Extension for buffer files. */
  private val BufferFileExt = ".buf"

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

  "An AudioPlayer" should "allow adding a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new AudioPlayerTestHelper

    helper.player addPlaybackContextFactory factory
    helper.playbackActor.expectMsg(PlaybackActor.AddPlaybackContextFactory(factory))
  }

  it should "allow removing a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new AudioPlayerTestHelper

    helper.player removePlaybackContextFactory factory
    helper.playbackActor.expectMsg(PlaybackActor.RemovePlaybackContextFactory(factory))
  }

  it should "deploy the line writer actor on a default dispatcher if no blocking one is defined" in {
    val helper = new AudioPlayerTestHelper
    val config = helper.config.copy(blockingDispatcherName = None)

    val props = AudioPlayer createLineWriterActorProps config
    props should be(Props[LineWriterActor])
  }

  it should "support adding playlist info objects to the playlist" in {
    val info = AudioSourcePlaylistInfo(AudioSourceID(MediumID("someMedium", None), "someURI"), 0, 0)
    val helper = new AudioPlayerTestHelper

    helper.player addToPlaylist info
    helper.sourceDownloadActor.expectMsg(info)
  }

  it should "support an overloaded method of adding songs to the playlist" in {
    val info = AudioSourcePlaylistInfo(AudioSourceID(MediumID("someMedium", None), "someURI"),
      20160413222120L, 20160413222133L)
    val helper = new AudioPlayerTestHelper

    helper.player.addToPlaylist(info.sourceID.mediumID, info.sourceID.uri, info.skip, info.skipTime)
    helper.sourceDownloadActor.expectMsg(info)
  }

  it should "set default values for skip properties in addToPlaylist()" in {
    val info = AudioSourcePlaylistInfo(AudioSourceID(MediumID("someMedium", None), "someURI"), 0, 0)
    val helper = new AudioPlayerTestHelper

    helper.player.addToPlaylist(info.sourceID.mediumID, info.sourceID.uri)
    helper.sourceDownloadActor.expectMsg(info)
  }

  it should "support closing the playlist" in {
    val helper = new AudioPlayerTestHelper

    helper.player.closePlaylist()
    helper.sourceDownloadActor.expectMsg(SourceDownloadActor.PlaylistEnd)
  }

  it should "support starting playback" in {
    val helper = new AudioPlayerTestHelper

    helper.player.startPlayback()
    helper.playbackActor.expectMsg(PlaybackActor.StartPlayback)
  }

  it should "support stopping playback" in {
    val helper = new AudioPlayerTestHelper

    helper.player.stopPlayback()
    helper.playbackActor.expectMsg(PlaybackActor.StopPlayback)
  }

  it should "allow skipping the current source" in {
    val helper = new AudioPlayerTestHelper

    helper.player.skipCurrentSource()
    helper.playbackActor.expectMsg(PlaybackActor.SkipSource)
  }

  /**
    * A test helper class collecting all required dependencies.
    */
  private class AudioPlayerTestHelper {
    /** Test probe for the playback actor. */
    val playbackActor = TestProbe()

    /** Test probe for the local buffer actor. */
    val bufferActor = TestProbe()

    /** Test probe for the line writer actor. */
    val lineWriterActor = TestProbe()

    /** Test probe for the source reader actor. */
    val sourceReaderActor = TestProbe()

    /** Test probe for the source download actor. */
    val sourceDownloadActor = TestProbe()

    /** The test player configuration. */
    val config = createPlayerConfig()

    /** The test player instance. */
    val player = AudioPlayer(config)

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
        case "localBufferActor" =>
          classOf[LocalBufferActor] isAssignableFrom props.actorClass() shouldBe true
          classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
          props.args should have size 2
          props.args.head should be(config)
          val bufMan = props.args(1).asInstanceOf[BufferFileManager]
          bufMan.prefix should be(BufferFilePrefix)
          bufMan.extension should be(BufferFileExt)
          bufMan.directory should be(config.bufferTempPath.get)
          bufferActor.ref

        case "sourceReaderActor" =>
          classOf[SourceReaderActor] isAssignableFrom props.actorClass() shouldBe true
          props.args should have size 1
          props.args.head should be(bufferActor.ref)
          sourceReaderActor.ref

        case "lineWriterActor" =>
          classOf[LineWriterActor] isAssignableFrom props.actorClass() shouldBe true
          props.dispatcher should be(BlockingDispatcherName)
          props.args should have size 0
          lineWriterActor.ref

        case "sourceDownloadActor" =>
          classOf[SourceDownloadActor] isAssignableFrom props.actorClass() shouldBe true
          classOf[SchedulerSupport] isAssignableFrom props.actorClass() shouldBe true
          props.args should contain theSameElementsAs List(config, bufferActor.ref,
            sourceReaderActor.ref)
          sourceDownloadActor.ref

        case "playbackActor" =>
          classOf[PlaybackActor] isAssignableFrom props.actorClass() shouldBe true
          props.args should contain theSameElementsAs List(config, sourceReaderActor.ref,
            lineWriterActor.ref)
          playbackActor.ref
      }
    }

    /**
      * Creates a test audio player configuration.
      *
      * @return the test configuration
      */
    private def createPlayerConfig(): PlayerConfig =
      PlayerConfig(mediaManagerActor = null, actorCreator = actorCreatorFunc,
        bufferTempPath = Some(createPathInDirectory("tempBufferDir")),
        bufferFilePrefix = BufferFilePrefix, bufferFileExtension = BufferFileExt,
        blockingDispatcherName = Some(BlockingDispatcherName))
  }

}
