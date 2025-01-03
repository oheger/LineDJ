/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio

import de.oliver_heger.linedj.player.engine.PlayerConfig

import scala.concurrent.duration.*

object Fixtures:
  /** A test player configuration that can be used by all tests. */
  final val TestPlayerConfig = PlayerConfig(inMemoryBufferSize = 48000,
    playbackContextLimit = 24000,
    bufferFileSize = 65536,
    bufferChunkSize = 16384,
    bufferFilePrefix = "Buffer",
    bufferFileExtension = ".buf",
    bufferTempPath = None,
    bufferTempPathParts = List(".lineDj", "temp"),
    downloadInProgressNotificationDelay = 3.minutes,
    downloadInProgressNotificationInterval = 2.minutes,
    timeProgressThreshold = 500.millis,
    blockingDispatcherName = None,
    mediaManagerActor = null,
    actorCreator = null)
