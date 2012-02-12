package de.oliver_heger.test.actors

import scala.actors.Actor
import scala.collection.mutable.Queue
import java.io.File
import java.io.OutputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.io.InputStream

/**
 *
 * @author hacker
 * @version $Id: $
 */
class SourceReaderActor(tempFileFactory : TempFileFactory,
    bufferManager : SourceBufferManager)
  extends Actor {
  /** Constant for the chunk size.*/
  private val ChunkSize = 1 * 1024 * 1024

  /** Constant of the size of a copy buffer.*/
  private val BufSize = 16 * 1024;

  /** The queue of the files to read.*/
  private val sourceFiles = Queue[String]()

  /** The current stream to the audio file to be copied.*/
  private var stream: InputStream = _

  def act() {
    var running = true

    while (running) {
      receive {
        case Exit =>
          closeCurrentStream()
          running = false

        case AddSourceFile(file) =>
          sourceFiles += file
          println("Added file " + file)

        case ReadChunk => copyChunk
      }
    }

    println("Exit ReadActor")
  }

  /**
   * Closes the current input stream if it is open.
   */
  private def closeCurrentStream() {
    if (stream != null) {
      stream.close()
      stream = null
    }
  }

  /**
   * Obtains the next input stream if necessary. After this method was executed,
   * the internal current stream field is set unless the end of the playlist is
   * reached.
   */
  private def nextInputStream() {
    if (stream == null && !sourceFiles.isEmpty) {
      val file = new File(sourceFiles.dequeue())
      val msg = AudioSource(file.getAbsolutePath(), file.length())
      stream = new BufferedInputStream(new FileInputStream(file))
      Gateway ! Gateway.ActorPlayback -> msg
    }
  }

  /**
   * Copies a chunk of data to newly created temporary files.
   */
  private def copyChunk() {
    val file = tempFileFactory.createFile()
    val out = new BufferedOutputStream(file.outputStream())
    try {
      var written = 0
      do {
        nextInputStream()
        if (stream != null) {
          val remaining = ChunkSize - written
          val count = copyStream(out, stream, remaining)
          if (count < remaining) {
            closeCurrentStream()
          }
          written += count
        }
      } while (written < ChunkSize && (stream != null || !sourceFiles.isEmpty))
    } finally {
      out.close()
    }
    bufferManager += file
    println("Copied chunk.")
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
      val read = stream.read(buf, 0, Math.min(count, buf.length))
      if (read > 0) {
        out.write(buf, 0, read)
        written += read
      }
      continue = read != -1 && written < count
    }
    written
  }
}
