package de.oliver_heger.splaya.io

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import de.oliver_heger.splaya.io.ChannelHandler.{IOOperationError, InitFile}
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData, ReadResult}

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
 *
 * @param factory the factory for creating new file reader actors
 */
class FileLoaderActor(factory: FileReaderActorFactory) extends Actor with ActorLogging {

  import de.oliver_heger.splaya.io.FileLoaderActor._

  /** A map for keeping track of the currently active load operations. */
  private val operations = collection.mutable.Map.empty[ActorRef, FileLoadOperation]

  def this() = this(new FileReaderActorFactory)

  /**
   * The supervisor strategy used by this actor stops the affected child on
   * receiving an IO exception. This mechanism is used to report failed load
   * operations to callers.
   */
  override val supervisorStrategy = OneForOneStrategy() {
    case _: IOException => Stop
  }

  override def receive: Receive = {
    case LoadFile(path) =>
      val reader = createFileReaderActor()
      reader ! InitFile(path)
      reader ! ReadData(ChunkSize)
      operations += reader -> FileLoadOperation(sender(), path)

    case res: ReadResult =>
      handleOperation { op =>
        op.appendContent(res)
        sender ! ReadData(ChunkSize)
      }

    case EndOfFile(_) =>
      handleOperation { operation =>
        operation.caller ! FileContent(path = operation.path, content = operation.content)
        context unwatch sender()
        context stop sender()
        operations -= sender()
      }

    case term: Terminated =>
      log.warning("Child reader actor was stopped due to an exception!")
      operations.get(term.actor) foreach { operation =>
        operation.caller ! IOOperationError(operation.path,
          new IOException("Read operation failed!"))
      }
  }

  /**
   * Creates a new ''FileReaderActor'' using the ''FileReaderActorFactory''.
   * @return the new ''FileReaderActor''
   */
  private def createFileReaderActor(): ActorRef = {
    val readActor = factory createFileReaderActor context
    context watch readActor
    readActor
  }

  /**
   * Executes the specified handler function on the load operation associated
   * with the sending actor. If the sending actor is unknown, this message is
   * ignored.
   * @param f the function to be executed
   */
  private def handleOperation(f: FileLoadOperation => Unit): Unit = {
    operations.get(sender()) foreach f
  }
}

/**
 * An internally used helper class which stores all information required for
 * the handling of a load operation. This includes meta data about the
 * operation, but also a stream for collecting the actual content.
 * @param caller the actor which triggered the request
 * @param path the path of the file to be loaded
 */
private case class FileLoadOperation(caller: ActorRef, path: Path) {
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
