/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.impl

import java.nio.file.Paths

import akka.actor.ActorRef
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.PlayerConfig.ActorCreator
import org.apache.commons.configuration.Configuration

import scala.concurrent.duration._

object PlayerConfigFactory {
  /** Property for the size of the buffer with audio data in memory. */
  val PropInMemoryBufferSize = "inMemoryBufferSize"

  /**
    * Property for the amount of data that must be loaded into memory before
    * a playback context can be created.
    */
  val PropPlaybackContextLimit = "playbackContextLimit"

  /**
    * Property for the size of a buffer file. The player engine will copy
    * audio data to files in the local buffer with this size.
    */
  val PropBufferFileSize = "bufferFileSize"

  /**
    * Property for the chunk size when reading or writing data from and to the
    * local buffer.
    */
  val PropBufferChunkSize = "bufferChunkSize"

  /**
    * Property for the prefix used for temporary files in the local buffer for
    * audio data.
    */
  val PropBufferFilePrefix = "bufferFilePrefix"

  /**
    * Property for the file extension to be used by files in the local buffer
    * for audio data.
    */
  val PropBufferFileExt = "bufferFileExt"

  /**
    * Property for the path where to store temporary files for the local buffer
    * for audio data. Defaults to the system's temp directory.
    */
  val PropBufferTempPath = "bufferTempPath"

  /**
    * Property defining further sub components of the path to the local audio
    * buffer. If the buffer is created in the system temp directory, with this
    * property further sub directories can be specified.
    */
  val PropBufferTempPathParts = "bufferTempPathParts"

  /**
    * Property for the initial delay of download in-progress notifications.
    * While downloading audio data from the archive during playback, the
    * player engine has to send notifications that the download is still in
    * progress. With this property the delay until sending the first
    * notification can be specified.
    */
  val PropDownloadProgressNotificationDelay = "downloadProgressNotificationDelay"

  /**
    * Property for the interval in which download in-progress notifications
    * are to be sent.
    */
  val PropDownloadProgressNotificationInterval = "downloadProgressNotificationInterval"

  /**
    * Property for the name of the blocking dispatcher.
    */
  val PropBlockingDispatcherName = "blockingDispatcherName"

  /** Default for in-memory buffer size. */
  val DefInMemoryBufferSize = 2097152

  /** Default playback context limit. */
  val DefPlaybackContextLimit = 1048570

  /** Default buffer file size. */
  val DefBufferFileSize = 4194304

  /** Default chunk size for buffer I/O operations. */
  val DefBufferChunkSize = 8192

  /** Default buffer file prefix. */
  val DefBufferFilePrefix = "Buffer"

  /** Default buffer file extension. */
  val DefBufferFileExt = ".tmp"

  /** Default delay for the first download in-progress notification. */
  val DefDownloadProgressNotificationDelay: FiniteDuration = 15.minutes

  /** Default interval for download in-progress notifications. */
  val DefDownloadProgressNotificationInterval: FiniteDuration = 30.minutes
}

/**
  * An internally used helper class for creating the configuration of the
  * audio player engine based on the platform configuration.
  */
private class PlayerConfigFactory {

  import PlayerConfigFactory._

  /**
    * Creates a ''PlayerConfig'' whose settings are extracted from the
    * specified ''Configuration'' object.
    *
    * @param c            the configuration to be read
    * @param prefix       the prefix for all configuration settings
    * @param mediaManager the media manager actor
    * @param actorCreator the actor creation function
    * @return the ''PlayerConfig''
    */
  def createPlayerConfig(c: Configuration, prefix: String, mediaManager: ActorRef,
                         actorCreator: ActorCreator): PlayerConfig = {
    import collection.JavaConverters._
    val p = if (prefix.endsWith(".")) prefix else prefix + '.'
    PlayerConfig(inMemoryBufferSize = c.getInt(p + PropInMemoryBufferSize, DefInMemoryBufferSize),
      playbackContextLimit = c.getInt(p + PropPlaybackContextLimit, DefPlaybackContextLimit),
      bufferFileExtension = c.getString(p + PropBufferFileExt, DefBufferFileExt),
      bufferFilePrefix = c.getString(p + PropBufferFilePrefix, DefBufferFilePrefix),
      bufferFileSize = c.getInt(p + PropBufferFileSize, DefBufferFileSize),
      bufferChunkSize = c.getInt(p + PropBufferChunkSize, DefBufferChunkSize),
      bufferTempPath = Option(c.getString(p + PropBufferTempPath)) map (Paths.get(_)),
      bufferTempPathParts = c.getList(p + PropBufferTempPathParts).asScala map String.valueOf,
      downloadInProgressNotificationDelay =
        c.getInt(p + PropDownloadProgressNotificationDelay,
          DefDownloadProgressNotificationDelay.toSeconds.toInt).seconds,
      downloadInProgressNotificationInterval =
        c.getInt(p + PropDownloadProgressNotificationInterval,
          DefDownloadProgressNotificationInterval.toSeconds.toInt).seconds,
      blockingDispatcherName = Option(c.getString(p + PropBlockingDispatcherName)),
      mediaManagerActor = mediaManager,
      actorCreator = actorCreator)
  }
}
