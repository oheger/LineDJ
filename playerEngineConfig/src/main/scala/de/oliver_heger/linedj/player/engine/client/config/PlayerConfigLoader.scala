/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.client.config

import akka.actor.ActorRef
import de.oliver_heger.linedj.player.engine.{ActorCreator, PlayerConfig}
import org.apache.commons.configuration.Configuration

import java.nio.file.Paths
import scala.concurrent.duration.*

/**
  * A module providing functionality for loading an XML configuration file with
  * options for the audio player. The options that can be defined here
  * correspond to the properties of [[PlayerConfig]].
  */
object PlayerConfigLoader:
  /**
    * Name of the memory buffer size property. This is the size of the buffer
    * with audio data hold in memory by the audio player.
    */
  final val PropMemoryBufferSize = "memoryBufferSize"

  /**
    * Name of the playback context limit property. It defines the amount of
    * audio data that must be available in the in-memory audio buffer before a
    * playback context can be created. For the creation of the playback context
    * some audio data is consumed. Therefore, the audio buffer must be filled
    * up to a certain threshold. Note that when playing MP3 audio this
    * property must be aligned with the ''marklimit'' property of the MP3
    * driver.
    */
  final val PropPlaybackContextLimit = "playbackContextLimit"

  /**
    * Name of the buffer file size property. It defines the maximum size of
    * temporary buffer files created for audio data.
    */
  final val PropBufferFileSize = "bufferFileSize"

  /**
    * Name of the buffer chunk size property. It defines the size of chunks
    * used in I/O operations when reading from or writing to the local buffer.
    */
  final val PropBufferChunkSize = "bufferChunkSize"

  /**
    * Name of the buffer file prefix property. It defines the prefix for the
    * temporary files created by the local buffer.
    */
  final val PropBufferFilePrefix = "bufferFilePrefix"

  /**
    * Name of the buffer file extension property. It defines the extension of
    * the temporary files created by the local buffer.
    */
  final val PropBufferFileExtension = "bufferFileExtension"

  /**
    * Name of the buffer temp path property. Via this property, the path where
    * to store temporary buffer files can be specified directly.
    */
  final val PropBufferTempPath = "bufferTempPath"

  /**
    * Name of the buffer temp path parts property. It is used to construct the
    * temporary path where buffer files are stored if the temporary path is not
    * directly defined. In this case, the names here define sub folders in the
    * user's home directory.
    */
  final val PropBufferTempPathParts = "bufferTempPathParts"

  /**
    * Name of the download in progress notification delay property. It defines
    * the initial delay for a download in progress notification after starting
    * a new download. Such notifications are sent to the server to indicate
    * that a client requesting a download is still alive.
    */
  final val PropDownloadInProgressNotificationDelay = "downloadInProgressNotificationDelay"

  /**
    * Name of the download in progress notification interval property. It
    * defines the interval in which download in progress notifications are
    * sent.
    */
  final val PropDownloadInProgressNotificationInterval = "downloadInProgressNotificationInterval"

  /**
    * Name of the time progress threshold property. It defines the threshold
    * when to send playback progress events. A new event is sent only if the
    * accumulated playback time since the last event exceeds this duration.
    */
  final val PropTimeProgressThreshold = "timeProgressThreshold"

  /**
    * Name of the blocking dispatcher name property. If defined, actors doing
    * blocking operations are scheduled on this dispatcher.
    */
  final val PropBlockingDispatcherName = "blockingDispatcherName"

  /** The default value for the memory buffer size property. */
  final val DefaultMemoryBufferSize = 2097152

  /** The default value for the playback context limit property. */
  final val DefaultPlaybackContextLimit = 1048570

  /** The default value for the buffer file size property. */
  final val DefaultBufferFileSize = 4194304

  /** The default value for the buffer chunk size property. */
  final val DefaultBufferChunkSize = 8192

  /** The default prefix for buffer files. */
  final val DefaultBufferFilePrefix = "Buffer"

  /** The default file extension for buffer files. */
  final val DefaultBufferFileExtension = ".tmp"

  /** The default list of path components for storing temp buffer files. */
  final val DefaultBufferTempPathParts: Seq[String] = List(".lineDJ", "temp")

  /** The default initial delay for sending a download notification. */
  final val DefaultDownloadInProgressNotificationDelay = 2.minutes

  /** The default interval for sending download notifications. */
  final val DefaultDownloadInProgressNotificationInterval = 3.minutes

  /** The threshold when to send playback in progress events. */
  final val DefaultTimeProgressThreshold = 1.second

  /**
    * A [[PlayerConfig]] instance with default values set. Note that the
    * dynamic properties (e.g. the actor creator) are '''null'''.
    */
  final val DefaultPlayerConfig: PlayerConfig =
    PlayerConfig(inMemoryBufferSize = DefaultMemoryBufferSize,
      playbackContextLimit = DefaultPlaybackContextLimit,
      bufferFileSize = DefaultBufferFileSize,
      bufferChunkSize = DefaultBufferChunkSize,
      bufferFilePrefix = DefaultBufferFilePrefix,
      bufferFileExtension = DefaultBufferFileExtension,
      bufferTempPath = None,
      bufferTempPathParts = DefaultBufferTempPathParts,
      downloadInProgressNotificationDelay = DefaultDownloadInProgressNotificationDelay,
      downloadInProgressNotificationInterval = DefaultDownloadInProgressNotificationInterval,
      timeProgressThreshold = DefaultTimeProgressThreshold,
      blockingDispatcherName = None,
      mediaManagerActor = null,
      actorCreator = null)

  /**
    * Creates a [[PlayerConfig]] from the given configuration.
    *
    * @param c                 the configuration
    * @param pathPrefix        the prefix for all keys
    * @param mediaManagerActor the media manager actor
    * @param actorCreator      the actor creator
    * @return the newly created [[PlayerConfig]]
    */
  def loadPlayerConfig(c: Configuration,
                       pathPrefix: String,
                       mediaManagerActor: ActorRef,
                       actorCreator: ActorCreator): PlayerConfig =
    val normalizedPrefix = if pathPrefix.endsWith(".") then pathPrefix
    else pathPrefix + "."

    def key(k: String) = normalizedPrefix + k

    import scala.jdk.CollectionConverters.*

    PlayerConfig(inMemoryBufferSize = c.getInt(key(PropMemoryBufferSize), DefaultMemoryBufferSize),
      playbackContextLimit = c.getInt(key(PropPlaybackContextLimit), DefaultPlaybackContextLimit),
      bufferFileSize = c.getInt(key(PropBufferFileSize), DefaultBufferFileSize),
      bufferChunkSize = c.getInt(key(PropBufferChunkSize), DefaultBufferChunkSize),
      bufferFilePrefix = c.getString(key(PropBufferFilePrefix), DefaultBufferFilePrefix),
      bufferFileExtension = c.getString(key(PropBufferFileExtension), DefaultBufferFileExtension),
      bufferTempPath = Option(c.getString(key(PropBufferTempPath))).map(Paths.get(_)),
      bufferTempPathParts = if c.containsKey(key(PropBufferTempPathParts)) then
        c.getList(key(PropBufferTempPathParts)).asScala.map(String.valueOf).toSeq
      else DefaultBufferTempPathParts,
      downloadInProgressNotificationDelay = c.getDuration(key(PropDownloadInProgressNotificationDelay),
        DefaultDownloadInProgressNotificationDelay),
      downloadInProgressNotificationInterval = c.getDuration(key(PropDownloadInProgressNotificationInterval),
        DefaultDownloadInProgressNotificationInterval),
      timeProgressThreshold = c.getDuration(key(PropTimeProgressThreshold), DefaultTimeProgressThreshold),
      blockingDispatcherName = Option(c.getString(key(PropBlockingDispatcherName))),
      mediaManagerActor = mediaManagerActor,
      actorCreator = actorCreator)

  /**
    * Creates a [[PlayerConfig]] with default values and the given dynamic
    * values.
    *
    * @param mediaManagerActor the media manager actor
    * @param actorCreator      the actor creator
    * @return the newly created [[PlayerConfig]]
    */
  def defaultConfig(mediaManagerActor: ActorRef, actorCreator: ActorCreator): PlayerConfig =
    DefaultPlayerConfig.copy(mediaManagerActor = mediaManagerActor, actorCreator = actorCreator)
