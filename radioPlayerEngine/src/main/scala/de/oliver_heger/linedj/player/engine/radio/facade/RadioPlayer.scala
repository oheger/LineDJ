/*
 * Copyright 2015-2022 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.SourceActorCreator
import de.oliver_heger.linedj.player.engine.actors._
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.actors.{RadioDataSourceActor, RadioEventConverterActor}
import de.oliver_heger.linedj.player.engine.radio.actors.schedule.RadioSchedulerActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object RadioPlayer {
  /**
    * Creates a new radio player instance based on the given configuration.
    * This is an asynchronous operation; therefore, this function returns a
    * ''Future''.
    *
    * @param config the player configuration
    * @param system the current ''ActorSystem''
    * @param ec     the ''ExecutionContext''
    * @return a ''Future'' with the new ''RadioPlayer'' instance
    */
  def apply(config: PlayerConfig)(implicit system: ActorSystem, ec: ExecutionContext): Future[RadioPlayer] = {
    val typedSystem = system.toTyped
    implicit val scheduler: Scheduler = typedSystem.scheduler
    implicit val timeout: Timeout = Timeout(10.seconds)

    for {
      eventActors <- PlayerControl.createEventManagerActorWithPublisher[RadioEvent](config.actorCreator,
        "radioEventManagerActor")
      converter = config.actorCreator.createActor(RadioEventConverterActor(eventActors._3),
        "playerEventConverter", Some(RadioEventConverterActor.Stop))
      playerListener <- converter.ask[RadioEventConverterActor.PlayerListenerReference] { ref =>
        RadioEventConverterActor.GetPlayerListener(ref)
      }
    } yield {
      val sourceCreator = radioPlayerSourceCreator(eventActors._3)
      val lineWriterActor = PlayerControl.createLineWriterActor(config, "radioLineWriterActor")
      val facadeActor =
        config.actorCreator.createActor(PlayerFacadeActor(config, playerListener.listener, lineWriterActor,
          sourceCreator), "radioPlayerFacadeActor")
      val schedulerActor = config.actorCreator.createActor(RadioSchedulerActor(eventActors._3),
        "radioSchedulerActor")

      new RadioPlayer(config, facadeActor, schedulerActor, eventActors._1, eventActors._2)
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
  * @param config               the configuration for this player
  * @param playerFacadeActor    reference to the facade actor
  * @param schedulerActor       reference to the scheduler actor
  * @param eventManagerActorOld reference to the legacy event manager actor
  * @param eventManagerActor    reference to the event manager actor
  */
class RadioPlayer private(val config: PlayerConfig,
                          override val playerFacadeActor: classics.ActorRef,
                          schedulerActor: classics.ActorRef,
                          override protected val eventManagerActorOld: classics.ActorRef,
                          override protected val eventManagerActor:
                          ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]])
  extends PlayerControl[RadioEvent] {
  /**
    * Marks a source as the new current source. This does not change the
    * current playback if any. It just means that the source specified is now
    * monitored for forbidden intervals, and corresponding events are
    * generated. To actually play this source, it has to be passed to the
    * ''playSource()'' function.
    *
    * @param source identifies the radio stream to be played
    */
  def makeToCurrentSource(source: RadioSource): Unit = {
    schedulerActor ! source
  }

  /**
    * Initializes information about exclusion intervals for radio sources. If
    * this information is set, the radio player keeps track when a source
    * should not be played. It can then automatically switch to a replacement
    * source until the exclusion interval for the current source is over.
    *
    * @param exclusions a map with information about exclusion intervals
    * @param rankingF   the ranking function for the sources
    */
  def initSourceExclusions(exclusions: Map[RadioSource, Seq[IntervalQuery]],
                           rankingF: RadioSource.Ranking): Unit = {
    schedulerActor ! RadioSchedulerActor.RadioSourceData(exclusions, rankingF)
  }

  /**
    * Forces a check of the current interval against its exclusion intervals.
    * Normally these checks are done by the player engine itself when
    * necessary. If playback of a replacement source fails, however, it can
    * make sense to enforce such a check to select a different replacement
    * source.
    *
    * @param exclusions a set with radio sources to be excluded (i.e. that
    *                   must not be used as replacement sources)
    * @param delay      an optional delay for this operation
    */
  def checkCurrentSource(exclusions: Set[RadioSource],
                         delay: FiniteDuration = PlayerControl.NoDelay): Unit = {
    invokeDelayed(RadioSchedulerActor.CheckCurrentSource(exclusions), schedulerActor, delay)
  }

  /**
    * The central function to start playback of a radio source. The source can
    * be either the current source or a replacement source. When switching the
    * current source, the ''makeCurrent'' flag should be '''true'''. Before
    * switching to another radio source, the player engine should typically be
    * reset to make sure that all audio buffers in use are cleared. Optionally,
    * playback can start after a delay.
    *
    * @param source      the source to be played
    * @param makeCurrent flag whether this should become the current source
    * @param resetEngine flag whether to reset the audio engine
    * @param delay       the delay
    */
  def playSource(source: RadioSource, makeCurrent: Boolean, resetEngine: Boolean = true,
                 delay: FiniteDuration = PlayerControl.NoDelay): Unit = {
    val playMsg = PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor)
    val srcMsg = PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader())
    val startMsg = List((srcMsg, playerFacadeActor), (playMsg, playerFacadeActor))
    val curMsg = if (makeCurrent) (source, schedulerActor) :: startMsg else startMsg
    val resetMsg = if (resetEngine) (PlayerFacadeActor.ResetEngine, playerFacadeActor) :: curMsg else curMsg
    playerFacadeActor ! DelayActor.Propagate(resetMsg, delay)
  }

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    closeActors(List(schedulerActor))

  /**
    * Invokes an actor with a delay. This method sends a corresponding message
    * to the ''PlayerFacadeActor''.
    *
    * @param msg    the message
    * @param target the target actor
    * @param delay  the delay
    */
  private def invokeDelayed(msg: Any, target: classics.ActorRef, delay: FiniteDuration): Unit = {
    playerFacadeActor ! DelayActor.Propagate(msg, target, delay)
  }
}
