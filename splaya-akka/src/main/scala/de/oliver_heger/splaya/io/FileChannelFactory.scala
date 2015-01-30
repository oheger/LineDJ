package de.oliver_heger.splaya.io

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
