package de.oliver_heger.splaya.io

import java.io.ByteArrayOutputStream
import java.nio.file.Path

import akka.actor._
import de.oliver_heger.splaya.io.ChannelHandler.InitFile
import de.oliver_heger.splaya.io.FileOperationActor.FileOperation
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData, ReadResult}
import de.oliver_heger.splaya.utils.ChildActorFactory

/**
 * Companion object.
 */
object FileLoaderActor {
  /** Constant for the chunk size used for read operations. */
  private val ChunkSize = 2048

  /**
   * A message received by ''FileLoaderActor'' telling it to load a specific
   * file. As a reaction of this method, this actor creates a new
   * [[FileReaderActor]] and interacts with it until the whole content of the
   * file was loaded.
   * @param path the path of the file to be loaded
   */
  case class LoadFile(path: Path)

  /**
   * A message sent by ''FileLoaderActor'' with the content of a file that has
   * been read.
   * @param path the path of the file that has been read
   * @param content the content of the file as a single byte array
   */
  case class FileContent(path: Path, content: Array[Byte])

  private class FileLoaderActorImpl extends FileLoaderActor with ChildActorFactory

  /**
   * Creates a ''Props'' object for creating new actors of this class. This
   * method should always be used for creating properties; it ensures that all
   * required dependencies are provided.
   * @return a ''Props'' object for creating new actor instances
   */
  def apply(): Props = Props[FileLoaderActorImpl]
}

/**
 * A specialized actor implementation for loading small files in a single step.
 *
 * [[FileReaderActor]] allows clients to read arbitrary portions of files. This
 * actor in contrast can be used to load the content of a file at once. This is
 * triggered by a single message defining the file to be loaded. As a response,
 * the content of the file as a single byte array is returned. Because the
 * whole content is read in memory this actor should be used for small files
 * only.
 *
 * Under the hood, each load operation is handled by a temporary
 * [[FileReaderActor]]. This makes it possible to handle multiple load
 * operations in parallel. Each operation involves a full interaction with the
 * associated file reader actor. After that, the reader actor is stopped.
 */
class FileLoaderActor extends Actor with FileOperationActor with ActorLogging {
  this: ChildActorFactory =>

  import de.oliver_heger.splaya.io.FileLoaderActor._

  type Operation = FileLoadOperation

  override def specialReceive: Receive = {
    case LoadFile(path) =>
      val reader = createFileReaderActor()
      reader ! InitFile(path)
      reader ! ReadData(ChunkSize)
      addFileOperation(reader, FileLoadOperation(sender(), path))

    case res: ReadResult =>
      handleOperation() { op =>
        op.appendContent(res)
        sender ! ReadData(ChunkSize)
        true
      }

    case EndOfFile(_) =>
      handleOperation() { operation =>
        operation.caller ! FileContent(path = operation.path, content = operation.content)
        context unwatch sender()
        context stop sender()
        false
      }
  }

  /**
   * Creates a new ''FileReaderActor'' using the ''FileReaderActorFactory''.
   * @return the new ''FileReaderActor''
   */
  private def createFileReaderActor(): ActorRef = {
    val readActor = createChildActor(Props[FileReaderActor])
    context watch readActor
    readActor
  }
}

/**
 * An internally used helper class which stores all information required for
 * the handling of a load operation. This includes meta data about the
 * operation, but also a stream for collecting the actual content.
 * @param caller the actor which triggered the request
 * @param path the path of the file to be loaded
 */
case class FileLoadOperation(override val caller: ActorRef, override val path: Path) extends
FileOperation {
  val contentStream = new ByteArrayOutputStream

  /**
   * Appends the specified read result to the content of the represented load
   * operation.
   * @param result the result to be appended
   */
  def appendContent(result: ReadResult): Unit = {
    contentStream.write(result.data, 0, result.length)
  }

  /**
   * Returns the content of this load operation as byte array.
   * @return the content of this load operation
   */
  def content: Array[Byte] = contentStream.toByteArray
}
