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

package de.oliver_heger.linedj.player.engine.radio.facade

import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.util.Timeout
import akka.{actor => classics}
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.SourceActorCreator
import de.oliver_heger.linedj.player.engine.actors._
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.radio.actors.schedule.RadioSchedulerActor
import de.oliver_heger.linedj.player.engine.radio.control.RadioControlActor
import de.oliver_heger.linedj.player.engine.radio.stream.RadioDataSourceActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioPlayerConfig, RadioSource, RadioSourceConfig}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object RadioPlayer {
  /**
    * Creates a new radio player instance based on the given configuration.
    * This is an asynchronous operation; therefore, this function returns a
    * ''Future''.
    *
    * @param config              the radio player configuration
    * @param controlActorFactory factory for creating the control actor
    * @param system              the current ''ActorSystem''
    * @param ec                  the ''ExecutionContext''
    * @return a ''Future'' with the new ''RadioPlayer'' instance
    */
  def apply(config: RadioPlayerConfig,
            controlActorFactory: RadioControlActor.Factory = RadioControlActor.behavior)
           (implicit system: ActorSystem, ec: ExecutionContext): Future[RadioPlayer] = {
    val typedSystem = system.toTyped
    implicit val scheduler: Scheduler = typedSystem.scheduler
    implicit val timeout: Timeout = Timeout(10.seconds)
    val creator = config.playerConfig.actorCreator

    for {
      eventActors <- PlayerControl.createEventManagerActorWithPublisher[RadioEvent](creator, "radioEventManagerActor")
      converter = creator.createActor(RadioEventConverterActor(eventActors._1),
        "playerEventConverter", Some(RadioEventConverterActor.Stop))
      playerListener <- converter.ask[RadioEventConverterActor.PlayerListenerReference] { ref =>
        RadioEventConverterActor.GetPlayerListener(ref)
      }
    } yield {
      val sourceCreator = radioPlayerSourceCreator(eventActors._2)
      val lineWriterActor = PlayerControl.createLineWriterActor(config.playerConfig, "radioLineWriterActor")
      val scheduledInvocationActor = PlayerControl.createSchedulerActor(creator,
        "radioSchedulerInvocationActor")
      val factoryActor = PlayerControl.createPlaybackContextFactoryActor(creator,
        "radioPlaybackContextFactoryActor")
      val facadeActor =
        creator.createActor(PlayerFacadeActor(config.playerConfig, playerListener.listener, scheduledInvocationActor,
          factoryActor, lineWriterActor, sourceCreator), "radioPlayerFacadeActor")
      val schedulerActor = creator.createActor(RadioSchedulerActor(eventActors._2),
        "radioSchedulerActor")
      val controlBehavior = controlActorFactory(config, eventActors._2, eventActors._1, facadeActor,
        scheduledInvocationActor, factoryActor)
      val controlActor = creator.createActor(controlBehavior, "radioControlActor",
        Some(RadioControlActor.Stop))

      new RadioPlayer(config, facadeActor, schedulerActor, eventActors._1, factoryActor, controlActor)
    }
  }

  /**
    * Returns the function to create the source actor for the radio player. As
    * the ''RadioDataSourceActor'' depends on the event actor, this reference
    * has to be passed in.
    *
    * @param eventActor the event actor
    * @return the function to create the source actor for the radio player
    */
  private def radioPlayerSourceCreator(eventActor: ActorRef[RadioEvent]): SourceActorCreator =
    (factory, config) => {
      val srcActor = factory.createChildActor(RadioDataSourceActor(config, eventActor))
      Map(PlayerFacadeActor.KeySourceActor -> srcActor)
    }
}

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
  * @param playerFacadeActor           reference to the facade actor
  * @param schedulerActor              reference to the scheduler actor
  * @param eventManagerActor           reference to the event manager actor
  * @param playbackContextFactoryActor the actor to create playback context
  *                                    objects
  * @param controlActor                reference to the control actor
  */
class RadioPlayer private(val config: RadioPlayerConfig,
                          override val playerFacadeActor: classics.ActorRef,
                          schedulerActor: classics.ActorRef,
                          override protected val eventManagerActor:
                          ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
                          override protected val playbackContextFactoryActor:
                          ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                          controlActor: ActorRef[RadioControlActor.RadioControlCommand])
  extends PlayerControl[RadioEvent] {
  /**
    * Updates the configuration for radio sources. This determines when
    * specific sources can or cannot be played.
    *
    * @param config the configuration for radio sources
    */
  def initRadioSourceConfig(config: RadioSourceConfig): Unit = {
    controlActor ! RadioControlActor.InitRadioSourceConfig(config)
  }

  /**
    * Starts radio playback, provided that a source has been selected.
    */
  def startRadioPlayback(): Unit = {
    controlActor ! RadioControlActor.StartPlayback
  }

  /**
    * Stops radio playback if it is currently ongoing.
    */
  def stopRadioPlayback(): Unit = {
    controlActor ! RadioControlActor.StopPlayback
  }

  /**
    * Sets the given [[RadioSource]] as the new current source. If possible,
    * this source is played directly; otherwise, a replacement source is
    * selected.
    *
    * @param source the new current [[RadioSource]]
    */
  def switchToRadioSource(source: RadioSource): Unit = {
    controlActor ! RadioControlActor.SelectRadioSource(source)
  }

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    closeActors(List(schedulerActor))
}
