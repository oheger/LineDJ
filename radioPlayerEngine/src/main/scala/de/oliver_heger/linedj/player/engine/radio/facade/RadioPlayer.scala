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

package de.oliver_heger.linedj.player.engine.radio.facade

import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.mp3.Mp3AudioStreamFactory
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine.radio.control.RadioControlActor
import de.oliver_heger.linedj.player.engine.radio.stream.*
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource}
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.util.Timeout

import scala.collection.immutable.Seq
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object RadioPlayer:
  /**
    * Creates a new radio player instance based on the given configuration.
    * This is an asynchronous operation; therefore, this function returns a
    * ''Future''.
    *
    * @param config                     the radio player configuration
    * @param streamManagerFactory       factory for creating the stream manager
    *                                   actor
    * @param streamHandleManagerFactory factory for creating the stream handle
    *                                   manager actor
    * @param playbackFactory            factory for creating the playback actor
    * @param controlActorFactory        factory for creating the control actor
    * @param system                     the current ''ActorSystem''
    * @param ec                         the ''ExecutionContext''
    * @return a ''Future'' with the new ''RadioPlayer'' instance
    */
  def apply(config: RadioPlayerConfig,
            streamManagerFactory: RadioStreamManagerActor.Factory = RadioStreamManagerActor.behavior,
            streamHandleManagerFactory: RadioStreamHandleManagerActor.Factory =
            RadioStreamHandleManagerActor.behavior,
            playbackFactory: RadioStreamPlaybackActor.Factory = RadioStreamPlaybackActor.behavior,
            controlActorFactory: RadioControlActor.Factory = RadioControlActor.behavior)
           (implicit system: classics.ActorSystem, ec: ExecutionContext): Future[RadioPlayer] =
    val typedSystem = system.toTyped
    implicit val scheduler: Scheduler = typedSystem.scheduler
    implicit val timeout: Timeout = Timeout(10.seconds)
    val creator = config.playerConfig.actorCreator

    for
      eventActors <- PlayerControl.createEventManagerActorWithPublisher[RadioEvent](creator, "radioEventManagerActor")
      converter = creator.createActor(RadioEventConverterActor(eventActors._1),
        "playerEventConverter", Some(RadioEventConverterActor.Stop))
      playerListener <- converter.ask[RadioEventConverterActor.PlayerListenerReference] { ref =>
        RadioEventConverterActor.GetPlayerListener(ref)
      }
    yield
      val streamBuilder = RadioStreamBuilder()
      val scheduledInvocationActor = PlayerControl.createSchedulerActor(creator, "radioSchedulerInvocationActor")
      val streamManagerBehavior = streamManagerFactory(
        config.playerConfig,
        streamBuilder,
        scheduledInvocationActor,
        config.streamCacheTime
      )
      val streamManager = creator.createActor(
        streamManagerBehavior,
        "radioStreamManagerActor",
        Some(RadioStreamManagerActor.Stop)
      )
      val streamHandleManagerBehavior = streamHandleManagerFactory(
        streamBuilder,
        RadioStreamHandle.factory,
        scheduledInvocationActor,
        config.streamCacheTime
      )
      val streamHandleManager = creator.createActor(
        streamHandleManagerBehavior,
        "radioStreamHandleManagerActor",
        Some(RadioStreamHandleManagerActor.Stop)
      )
      val playbackActorConfig = RadioStreamPlaybackActor.RadioStreamPlaybackConfig(
        audioStreamFactory = Mp3AudioStreamFactory,
        handleActor = streamHandleManager,
        eventActor = eventActors._1,
        inMemoryBufferSize = config.playerConfig.inMemoryBufferSize,
        dispatcherName = config.playerConfig.blockingDispatcherName.getOrElse(LineWriterStage.BlockingDispatcherName),
        optStreamFactoryLimit = Some(config.playerConfig.playbackContextLimit)
      )
      val playbackActorBehavior = playbackFactory(playbackActorConfig)
      val playbackActor = creator.createActor(
        playbackActorBehavior,
        "radioStreamPlaybackActor",
        Some(RadioStreamPlaybackActor.Stop)
      )
      val factoryActor = PlayerControl.createPlaybackContextFactoryActor(creator,
        "radioPlaybackContextFactoryActor")
      val controlBehavior = controlActorFactory(
        config,
        eventActors._2,
        eventActors._1,
        playbackActor,
        scheduledInvocationActor,
        factoryActor,
        streamManager
      )
      val controlActor = creator.createActor(
        controlBehavior,
        "radioControlActor",
        Some(RadioControlActor.Stop)
      )

      new RadioPlayer(config,
        eventActors._1,
        factoryActor,
        scheduledInvocationActor,
        null, // TODO: Create a proper DynamicAudioStreamFactory.
        controlActor)(typedSystem)
end RadioPlayer

/**
  * A facade on the player engine that allows playing radio streams.
  *
  * This class sets up all required actors for playing internet radio. It
  * offers an interface for controlling playback and selecting the radio
  * stream to be played.
  *
  * Instances are created using the factory method from the companion object.
  * As this class is a facade of multiple actors, accessing it from multiple
  * threads is safe.
  *
  * @param config                      the configuration for this player
  * @param eventManagerActor           reference to the event manager actor
  * @param playbackContextFactoryActor the actor to create playback context
  *                                    objects
  * @param scheduledInvocationActor    the actor for scheduled invocations
  * @param dynamicAudioStreamFactory   the factory for audio streams
  * @param controlActor                reference to the control actor
  */
class RadioPlayer private(val config: RadioPlayerConfig,
                          override protected val eventManagerActor:
                             ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
                          override protected val playbackContextFactoryActor:
                             ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                          override protected val scheduledInvocationActor:
                             ActorRef[ScheduledInvocationActor.ActorInvocationCommand],
                          override protected val dynamicAudioStreamFactory: DynamicAudioStreamFactory,
                          controlActor: ActorRef[RadioControlActor.RadioControlCommand])
                         (implicit actorSystem: ActorSystem[_])
  extends PlayerControl[RadioEvent]:
  /**
    * Updates the configuration for radio sources. This determines when
    * specific sources can or cannot be played.
    *
    * @param config the configuration for radio sources
    */
  def initRadioSourceConfig(config: RadioSourceConfig): Unit =
    controlActor ! RadioControlActor.InitRadioSourceConfig(config)

  /**
    * Updates the metadata configuration. This allows disabling radio sources
    * temporarily based on the stuff they are playing.
    *
    * @param config the metadata configuration
    */
  def initMetadataConfig(config: MetadataConfig): Unit =
    controlActor ! RadioControlActor.InitMetadataConfig(config)

  /**
    * Sets the given [[RadioSource]] as the new current source. If possible,
    * this source is played directly; otherwise, a replacement source is
    * selected.
    *
    * @param source the new current [[RadioSource]]
    */
  def switchToRadioSource(source: RadioSource): Unit =
    controlActor ! RadioControlActor.SelectRadioSource(source)

  /**
    * Queries the current playback state and returns a [[Future]] with the
    * result.
    *
    * @return the ''Future'' with the current playback state
    */
  def currentPlaybackState: Future[RadioControlActor.CurrentPlaybackState] =
    implicit val timeout: Timeout = Timeout(5.seconds)
    controlActor.ask(RadioControlActor.GetPlaybackState.apply)

  override protected def startPlaybackInvocation: ScheduledInvocationActor.ActorInvocation =
    ScheduledInvocationActor.typedInvocation(controlActor, RadioControlActor.StartPlayback)

  override protected def stopPlaybackInvocation: ScheduledInvocationActor.ActorInvocation =
    ScheduledInvocationActor.typedInvocation(controlActor, RadioControlActor.StopPlayback)

  // TODO: Refactor base trait to not expect a facade actor any more.
  override protected val playerFacadeActor: classics.ActorRef = null

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    Future.successful(Seq.empty)
