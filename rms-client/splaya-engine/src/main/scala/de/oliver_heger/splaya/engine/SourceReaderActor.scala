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
 *
 * @author hacker
 * @version $Id: $
 */
class SourceReaderActor(resolver: SourceResolver, tempFileFactory: TempFileFactory,
  bufferManager: SourceBufferManager, chunkSize: Int)
  extends Actor {
  /** Constant of the size of a copy buffer.*/
  private val BufSize = 16 * 1024;

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
          sourceStreams += strm
          copy()

        case ReadChunk =>
          bytesToWrite += chunkSize
          copy()
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
    if (currentInputStream == null && !sourceStreams.isEmpty) {
      val srcStream = sourceStreams.dequeue()
      val resolvedStream = resolver.resolve(srcStream.uri)
      val msg = AudioSource(srcStream.uri, srcStream.index, resolvedStream.size)
      currentInputStream = new BufferedInputStream(resolvedStream.openStream())
      Gateway ! Gateway.ActorPlayback -> msg
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
      bufferManager += currentTempFile
      closeCurrentOutputStream(false)
    }
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
        val count = copyStream(currentOutputStream, currentInputStream, remaining)
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
   * @param out the target output stream
   * @param stream the source stream to be copied
   * @param count the maximum number of bytes to copy
   * @return the number of bytes copied
   */
  private def copyStream(out: OutputStream, stream: InputStream, count: Int): Int = {
    val buf = new Array[Byte](BufSize)
    var continue = true
    var written = 0
    while (continue) {
      val read = stream.read(buf, 0, Math.min(count - written, buf.length))
      if (read > 0) {
        out.write(buf, 0, read)
        written += read
      }
      continue = read != -1 && written < count
    }
    written
  }

  /**
   * Copies data from input sources to temporary files. This method copies
   * single chunks until the temporary buffer is full.
   */
  private def copy() {
    while (bytesToWrite > 0 && hasMoreData) {
      nextOutputStream()
      copyChunk()
      closeChunk()
    }
  }
}
