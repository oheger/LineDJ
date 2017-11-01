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

import akka.actor.{ActorRef, Props}
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.player.engine.impl.{DelayActor, EventManagerActor, PlaybackActor, RadioDataSourceActor}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.impl.schedule.RadioSchedulerActor
import de.oliver_heger.linedj.player.engine.{PlayerConfig, RadioSource}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object RadioPlayer {
  /**
    * Creates a new radio player instance based on the given configuration.
    *
    * @param config the player configuration
    * @return the new ''RadioPlayer'' instance
    */
  def apply(config: PlayerConfig): RadioPlayer = {
    val eventActor = config.actorCreator(Props[EventManagerActor], "radioEventManagerActor")
    val sourceActor = config.actorCreator(RadioDataSourceActor(config, eventActor),
      "radioDataSourceActor")
    val lineWriterActor = PlayerControl.createLineWriterActor(config, "radioLineWriterActor")
    val playbackActor = config.actorCreator(PlaybackActor(config, sourceActor, lineWriterActor,
      eventActor), "radioPlaybackActor")
    val schedulerActor = config.actorCreator(RadioSchedulerActor(sourceActor),
      "radioSchedulerActor")
    val delayActor = config.actorCreator(DelayActor(), "radioDelayActor")

    new RadioPlayer(config, playbackActor, sourceActor, schedulerActor, eventActor,
      delayActor)
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
  * @param config            the configuration for this player
  * @param playbackActor     reference to the playback actor
  * @param sourceActor       reference to the radio source actor
  * @param schedulerActor    reference to the scheduler actor
  * @param eventManagerActor reference to the event manager actor
  * @param delayActor        reference to the delay actor
  */
class RadioPlayer private(val config: PlayerConfig,
                          playbackActor: ActorRef,
                          sourceActor: ActorRef, schedulerActor: ActorRef,
                          override protected val eventManagerActor: ActorRef,
                          delayActor: ActorRef)
  extends PlayerControl {
  /**
    * Switches to the specified radio source. Playback of the current radio
    * stream - if any - is stopped. Then the new stream is opened and played.
    * It is possible to specify a delay when this should happen.
    *
    * @param source identifies the radio stream to be played
    * @param delay an optional delay for this operation
    */
  def switchToSource(source: RadioSource, delay: FiniteDuration = DelayActor.NoDelay): Unit = {
    invokeDelayed(source, schedulerActor, delay)
  }

  /**
    * Initializes information about exclusion intervals for radio sources. If
    * this information is set, the radio player keeps track when a source
    * should not be played. It can then automatically switch to a replacement
    * source until the exclusion interval for the current source is over.
    *
    * @param exclusions a map with information about exclusion intervals
    * @param rankingF the ranking function for the sources
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
    * @inheritdoc This implementation also sends a clear buffer message to the
    *             source actor to make sure that no outdated audio data is
    *             played. Note: This happens only if no delay is specified!
    *             Normally, for this player playback should not be started with
    *             a delay. Rather, the ''switchToSource()'' method should be
    *             invoked with a delay; this causes the start of audio
    *             streaming at the desired time.
    */
  override def startPlayback(delay: FiniteDuration = PlayerControl.NoDelay): Unit = {
    if (delay <= PlayerControl.NoDelay) {
      sourceActor ! RadioDataSourceActor.ClearSourceBuffer
    }
    super.startPlayback(delay)
  }

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    closeActors(List(playbackActor, sourceActor, schedulerActor, delayActor))

  override protected def invokePlaybackActor(msg: Any, delay: FiniteDuration): Unit = {
    invokeDelayed(msg, playbackActor, delay)
  }

  /**
    * Invokes an actor with a delay. This method sends a corresponding message
    * to the ''DelayActor''.
    *
    * @param msg    the message
    * @param target the target actor
    * @param delay  the delay
    */
  private def invokeDelayed(msg: Any, target: ActorRef, delay: FiniteDuration): Unit = {
    delayActor ! DelayActor.Propagate(msg, target, delay)
  }
}
