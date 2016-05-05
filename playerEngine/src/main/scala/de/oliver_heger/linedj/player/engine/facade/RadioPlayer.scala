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

import akka.actor.ActorRef
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.player.engine.impl.{PlaybackActor, RadioDataSourceActor}
import de.oliver_heger.linedj.player.engine.{PlayerConfig, RadioSource}

import scala.concurrent.{ExecutionContext, Future}

object RadioPlayer {
  /**
    * Creates a new radio player instance based on the given configuration.
    *
    * @param config the player configuration
    * @return the new ''RadioPlayer'' instance
    */
  def apply(config: PlayerConfig): RadioPlayer = {
    val sourceActor = config.actorCreator(RadioDataSourceActor(config), "radioDataSourceActor")
    val lineWriterActor = PlayerControl.createLineWriterActor(config, "radioLineWriterActor")
    val playbackActor = config.actorCreator(PlaybackActor(config, sourceActor, lineWriterActor),
      "radioPlaybackActor")

    new RadioPlayer(config, playbackActor, sourceActor)
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
  * @param config        the configuration for this player
  * @param playbackActor reference to the playback actor
  * @param sourceActor   reference to the radio source actor
  */
class RadioPlayer private(val config: PlayerConfig,
                          override protected val playbackActor: ActorRef,
                          sourceActor: ActorRef)
  extends PlayerControl {
  /**
    * Switches to the specified radio source. Playback of the current radio
    * stream - if any - is stopped. Then the new stream is opened and played.
    *
    * @param source identifies the radio stream to be played
    */
  def switchToSource(source: RadioSource): Unit = {
    playbackActor ! PlaybackActor.SkipSource
    sourceActor ! source
  }

  /**
    * @inheritdoc This implementation also sends a clear buffer message to the
    *             source actor to make sure that no outdated audio data is
    *             played.
    */
  override def startPlayback(): Unit = {
    sourceActor ! RadioDataSourceActor.ClearSourceBuffer
    super.startPlayback()
  }

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    closeActors(List(playbackActor, sourceActor))
}
