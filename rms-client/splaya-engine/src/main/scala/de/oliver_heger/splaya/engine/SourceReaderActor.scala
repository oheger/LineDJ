package de.oliver_heger.splaya.engine

import scala.actors.Actor
import scala.collection.mutable.Queue
import java.io.File
import java.io.OutputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.Closeable
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * An actor which reads files from a source directory and copies them to a
 * temporary buffer.
 *
 * This actor mainly processes two kinds of messages:
 * - Messages which add new source files to be streamed.
 * - Messages which indicate that more data needs to be copied to the temporary
 * buffer.
 *
 * The actor communicates with the actor managing playback of audio data. This
 * actor is notified when copying of new audio streams starts and when a chunk
 * of temporary data has been written. When the playback actor has played a
 * chunk of audio data it sends back a message and requests new data.
 */
class SourceReaderActor(resolver: SourceResolver, tempFileFactory: TempFileFactory,
  chunkSize: Int) extends Actor {
  /** Constant of the size of a copy buffer.*/
  private[engine] val BufSize = 16 * 1024;

  /** The logger. */
  val log = LoggerFactory.getLogger(classOf[SourceReaderActor])

  /** The queue of the files to read.*/
  private val sourceStreams = Queue[AddSourceStream]()

  /** The current stream to the audio file to be copied.*/
  private var currentInputStream: InputStream = _

  /** The current temporary file. */
  private var currentTempFile: TempFile = _

  /** The current output stream. */
  private var currentOutputStream: OutputStream = _

  /** The number of bytes to write until the buffer is full. */
  private var bytesToWrite = 2 * chunkSize

  /** The number of bytes written in the current chunk. */
  private var chunkBytes = 0

  /** The number of bytes read from the current file. */
  private var fileBytes = 0L

  /** A flag whether the end of the playlist has been reported. */
  private var playlistEnd = false

  /**
   * The main method of this actor.
   */
  def act() {
    var running = true

    while (running) {
      receive {
        case ex: Exit =>
          closeCurrentInputStream()
          closeCurrentOutputStream(true)
          running = false
          ex.confirmed(this)

        case strm: AddSourceStream =>
          appendSource(strm)

        case ReadChunk =>
          bytesToWrite += chunkSize
          copy()

        case PlaylistEnd =>
          appendSource(new AddSourceStream)
      }
    }
  }

  /**
   * Returns a string representation for this object. This implementation just
   * returns a symbolic name for this actor.
   * @return a string for this object
   */
  override def toString = "SourceReaderActor"

  /**
   * Adds another source to the playlist of this actor.
   */
  private def appendSource(strm: AddSourceStream) {
    if (playlistEnd) {
      log.warn("Adding a source after end of playlist! Ignoring...");
    } else {
      sourceStreams += strm
      if (!strm.isDefined) {
        playlistEnd = true
      }
      copy()
    }
  }

  /**
   * Helper method for closing an object ignoring all exceptions.
   * @param cls the object to be closed
   */
  private def closeSilent(cls: Closeable) {
    try {
      cls.close()
    } catch {
      case ioex: IOException =>
        log.warn("Error when closing stream!", ioex)
    }
  }

  /**
   * Closes the current input stream if it is open.
   */
  private def closeCurrentInputStream() {
    if (currentInputStream != null) {
      closeSilent(currentInputStream)
      currentInputStream = null
    }
  }

  /**
   * Closes the current output stream if it is open. If specified, the temporary
   * file is deleted.
   * @param deleteTemp a flag whether the temporary file is to be closed
   */
  private def closeCurrentOutputStream(deleteTemp: Boolean) {
    if (currentTempFile != null) {
      closeSilent(currentOutputStream)
      currentOutputStream = null
      if (deleteTemp) {
        currentTempFile.delete()
      }
      currentTempFile = null
    }
  }

  /**
   * Obtains the next input stream if necessary. After this method was executed,
   * the internal current stream field is set unless the end of the playlist is
   * reached.
   */
  private def nextInputStream() {
    while (currentInputStream == null && !sourceStreams.isEmpty) {
      val srcStream = sourceStreams.dequeue()
      if (srcStream.isDefined) {
        fileBytes = 0
        try {
          val resolvedStream = resolver.resolve(srcStream.uri)
          val msg = AudioSource(srcStream.uri, srcStream.index, resolvedStream.size)
          currentInputStream = new BufferedInputStream(resolvedStream.openStream())
          Gateway ! Gateway.ActorPlayback -> msg
        } catch {
          case ex: Exception =>
            Gateway.publish(PlaybackError("Error opening source " + srcStream,
              ex, false))
        }
      }
    }

    checkPlaylistEnd()
  }

  /**
   * Checks whether the end of the playlist has been reached. If so, the
   * playback actor is informed.
   */
  private def checkPlaylistEnd() {
    if (playlistEnd && currentInputStream == null) {
      if (chunkBytes > 0) {
        sendChunkToPlayback()
      }
      Gateway ! Gateway.ActorPlayback -> PlaylistEnd
    }
  }

  /**
   * Obtains the next output stream if necessary. After this method was
   * executed, data can safely be written into the output stream.
   */
  private def nextOutputStream() {
    if (currentTempFile == null) {
      currentTempFile = tempFileFactory.createFile()
      currentOutputStream = new BufferedOutputStream(currentTempFile.outputStream())
      chunkBytes = 0
    }
  }

  /**
   * Closes the current temporary file if a complete chunk has been written.
   */
  private def closeChunk() {
    if (chunkBytes >= chunkSize) {
      sendChunkToPlayback()
    }
  }

  /**
   * Sends a message with the current temporary file to the playback actor so
   * it can be played.
   */
  private def sendChunkToPlayback() {
    Gateway ! Gateway.ActorPlayback -> currentTempFile
    closeCurrentOutputStream(false)
  }

  /**
   * Checks whether more audio data is available which can be copied.
   */
  private def hasMoreData = currentInputStream != null || !sourceStreams.isEmpty

  /**
   * Copies a chunk of data to a temporary file. This method expects that the
   * input and output files have already been initialized. It stops when the
   * output file has been written completely.
   */
  private def copyChunk() {
    do {
      nextInputStream()
      if (currentInputStream != null) {
        val remaining = chunkSize - chunkBytes
        val count = copyStream(remaining)
        if (count < remaining) {
          closeCurrentInputStream()
        }
        chunkBytes += count
        bytesToWrite -= count
      }
    } while (chunkBytes < chunkSize && hasMoreData)
  }

  /**
   * Copies data from the given input stream to the output stream. The maximum
   * number of bytes to copy is specified by the {@code count} parameter. If
   * the end of the input stream is reached before, the method returns.
   * @param count the maximum number of bytes to copy
   * @return the number of bytes copied
   */
  private def copyStream(count: Int): Int = {
    val buf = new Array[Byte](BufSize)
    var continue = true
    var written = 0
    while (continue) {
      val read = readSource(buf, scala.math.min(count - written, buf.length))
      if (read > 0) {
        currentOutputStream.write(buf, 0, read)
        written += read
        fileBytes += read
      }
      continue = read != -1 && written < count
    }
    written
  }

  /**
   * Reads a number of bytes from the current input stream. Handles exceptions.
   * @param buf the target buffer
   * @param count the number of bytes to read
   * @return the number of bytes actually read
   */
  private def readSource(buf: Array[Byte], count: Int): Int = {
    var read: Int = 0;
    try {
      read = currentInputStream.read(buf, 0, count)
    } catch {
      case ioex: IOException =>
        throw new IOReadException(ioex)
    }
    read
  }

  /**
   * Copies data from input sources to temporary files. This method copies
   * single chunks until the temporary buffer is full.
   */
  private def copy() {
    while (bytesToWrite > 0 && hasMoreData) {
      try {
        nextOutputStream()
        copyChunk()
        closeChunk()
      } catch {
        case iorex: IOReadException =>
          Gateway ! Gateway.ActorPlayback -> SourceReadError(fileBytes)
          Gateway.publish(PlaybackError("Error when reading audio source!",
            iorex.getCause(), false))
          closeCurrentInputStream()
        case ex: Exception =>
          Gateway.publish(PlaybackError("Error when copying audio source!",
            ex, true))
          bytesToWrite = 0
      }
    }
  }
}

/**
 * A specialized IO exception class for reporting exceptions which occurred
 * during a read operation. Such exceptions can typically be handled by just
 * skipping the problematic stream; so they are not fatal.
 */
private class IOReadException(cause: Throwable) extends IOException(cause)
