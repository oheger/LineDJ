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

package de.oliver_heger.linedj.io

import java.nio.channels.AsynchronousFileChannel
import java.nio.file.{OpenOption, Path}

/**
 * An internally used helper class for creating ''AsynchronousFileChannel''
 * objects.
 *
 * An instance of this class is used by [[FileReaderActor]] to create a new
 * file channel. This simplifies testing as specially prepared channels can be
 * injected.
 */
class FileChannelFactory {
  /**
   * Creates a new channel for reading the specified file.
   * @param path the path to the file to be read
   * @param openOptions a sequence of options for the channel
   * @return the channel for reading this file
   * @throws java.io.IOException if the channel cannot be opened
   */
  def createChannel(path: Path, openOptions: OpenOption*): AsynchronousFileChannel =
    AsynchronousFileChannel.open(path, openOptions: _*)
}
