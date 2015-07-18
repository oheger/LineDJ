package de.oliver_heger.splaya.io

import java.nio.file.Path

import akka.actor.{Actor, ActorRef, Props}
import de.oliver_heger.splaya.io.ChannelHandler.{ArraySource, InitFile}
import de.oliver_heger.splaya.io.FileOperationActor.FileOperation
import de.oliver_heger.splaya.io.FileSaverActor.{FileSaved, SaveFile}
import de.oliver_heger.splaya.io.FileWriterActor.WriteResult
import de.oliver_heger.splaya.utils.ChildActorFactory

/**
 * Companion object.
 */
object FileSaverActor {
  /** Constant for the chunk size used by this actor. */
  private val ChunkSize = 2048

  /**
   * A message processed by ''FileSaverActor'' telling it to write the data of
   * a file to disk. A file is created at the specified path location, and the
   * content of the array is written into it.
   * @param path the path of the file to be saved
   * @param content the content of the file
   */
  case class SaveFile(path: Path, content: Array[Byte])

  /**
   * A message sent by ''FileSaverActor'' to indicate that a file save
   * operation has been completed successfully.
   * @param path the path of the file that has been saved
   */
  case class FileSaved(path: Path)

  private class FileSaveActorImpl extends FileSaverActor with ChildActorFactory

  /**
   * Returns a ''Props'' object for creating a concrete actor instance. This
   * method should normally be used when creating new actor instances; it
   * ensures that all dependencies are satisfied.
   * @return the ''Props'' object for creating an actor instance
   */
  def apply(): Props = Props[FileSaveActorImpl]
}

/**
 * A specialized actor implementation for saving small files in a single step.
 *
 * This actor stands in the same relation to [[FileWriterActor]] as
 * [[FileLoaderActor]] to [[FileReaderActor]]. It supports multiple concurrent
 * write operations that store the whole content of files in a single (logic)
 * step.
 */
class FileSaverActor extends Actor with FileOperationActor {
  this: ChildActorFactory =>

  type Operation = FileSaveOperation

  override def specialReceive: Receive = {
    case SaveFile(path, content) =>
      val writer = createChildActor(Props[FileWriterActor])
      writer ! InitFile(path)

      val operation = FileSaveOperation(sender(), path, content)
      writer ! operation.nextSource(FileSaverActor.ChunkSize)
      addFileOperation(writer, operation)
      context watch writer

    case WriteResult(_, _) =>
      handleOperation() { operation =>
        if (operation.complete) {
          sender() ! CloseRequest
          operation.caller ! FileSaved(operation.path)
          context unwatch sender()
          false
        } else {
          sender ! operation.nextSource(FileSaverActor.ChunkSize)
          true
        }
      }

    case CloseAck(actor) =>
      // This message indicates the completion of the write operation
      context stop actor
  }
}

/**
 * An internally used data class representing a file save operation.
 * An instance stores some meta data about the operation, but also the content
 * to be written, and the current position.
 * @param caller the actor triggering this operation
 * @param path the path to be written
 * @param content the file content
 */
case class FileSaveOperation(override val caller: ActorRef, override val path: Path, content:
Array[Byte]) extends FileOperation {
  /** The current write position in the file. */
  private var position = 0

  /**
   * Returns an ''ArraySource'' object to write data from the current write
   * position.
   * @param chunkSize the chunk size
   * @return the ''ArraySource''
   */
  def nextSource(chunkSize: Int): ArraySource = {
    val currentLength = math.min(content.length - position, chunkSize)
    val currentOffset = position
    position += currentLength
    new ArraySource {
      override val data = content
      override val length = currentLength
      override val offset = currentOffset
    }
  }

  /**
   * Checks whether the whole content has already been written.
   * @return a flag whether the whole content has been written
   */
  def complete: Boolean = position >= content.length
}
