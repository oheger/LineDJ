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

package de.oliver_heger.linedj.player.engine

import java.nio.file.Path

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.player.engine.PlayerConfig.ActorCreator

import scala.concurrent.duration._

object PlayerConfig {
  /**
    * A type definition for a function that is used by the audio player engine
    * to create actors. This function is invoked in order to create the
    * several helper actors making up the audio player engine implementation.
    * The function expects the actor's ''Props'' and an actor name and returns
    * the reference of the newly created actor.
    */
  type ActorCreator = (Props, String) => ActorRef
}

/**
  * A class collecting configuration options for an audio player.
  *
  * There is a bunch of configuration options interpreted by an audio player
  * object. This class defines all of them - independent on a specific
  * configuration mechanism.
  *
  * For some options meaningful default values are provided. These default
  * values can be used directly; alternatively, they can be overridden to
  * fine-tune a specific audio player instance.
  *
  * @param inMemoryBufferSize the size of the buffer with audio data hold in
  *                           memory by the audio player
  * @param playbackContextLimit the amount of audio data that must be available
  *                             in the in-memory audio buffer before a playback
  *                             context can be created; for the creation of the
  *                             playback context some audio data is consumed;
  *                             therefore, the audio buffer must be filled up
  *                             to a certain threshold; note that when playing
  *                             MP3 audio this property must be aligned with
  *                             the ''marklimit'' property of the MP3 driver
  * @param bufferFileSize the size of a temporary audio file in the local
  *                       buffer; before audio data is played, it is buffered
  *                       on the local file system; for this purpose, temporary
  *                       files of this size are created
  * @param bufferChunkSize the size of chunks used in I/O operations by the
  *                        local buffer
  * @param bufferFilePrefix prefix for temporary files created by the local
  *                         buffer
  * @param bufferFileExtension the extension of temporary files created by the
  *                            local buffer
  * @param bufferTempPath an optional path to a directory in which to store
  *                       temporary files created by the local buffer actor
  * @param bufferTempPathParts a sequence of path names; this property is
  *                            evaluated if ''bufferTempPath'' is undefined; in
  *                            this case, the temporary buffer directory is
  *                            created below the current user's home
  *                            directory; the path components specified here
  *                            are resolved as subdirectories of the user's
  *                            home directory
  * @param downloadInProgressNotificationDelay the initial delay for a download
  *                                            in progress notification; such
  *                                            notifications are sent to the
  *                                            server to indicate that a client
  *                                            requesting a download is still
  *                                            alive
  * @param downloadInProgressNotificationInterval the interval for download
  *                                               in progress notifications;
  *                                               such notifications are sent
  *                                               in this interval to prevent
  *                                               that the server terminates a
  *                                               download that takes too long
  * @param blockingDispatcherName an optional name of a dispatcher for actors
  *                               that do blocking calls; if defined, such
  *                               actors are deployed on this dispatcher
  * @param mediaManagerActor a reference to the ''MediaManagerActor''; this
  *                          actor is queried for downloads of media files
  * @param actorCreator the function for creating new actors
  */
case class PlayerConfig(inMemoryBufferSize: Int = 2097152,
                        playbackContextLimit: Int = 1048570,
                        bufferFileSize: Int = 4194304,
                        bufferChunkSize: Int = 8192,
                        bufferFilePrefix: String = "Buffer",
                        bufferFileExtension: String = ".tmp",
                        bufferTempPath: Option[Path] = None,
                        bufferTempPathParts: Seq[String] = List(".lineDJ", "temp"),
                        downloadInProgressNotificationDelay: FiniteDuration = 2.minutes,
                        downloadInProgressNotificationInterval: FiniteDuration = 3.minutes,
                        blockingDispatcherName: Option[String] = None,
                        mediaManagerActor: ActorRef,
                        actorCreator: ActorCreator)
