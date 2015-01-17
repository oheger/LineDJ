package de.oliver_heger.splaya.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler}
import java.nio.file.Path

import akka.actor.{Actor, ActorRef}
import de.oliver_heger.splaya.io.FileReaderActor._

/**
 * Companion object for ''FileReaderActor''.
 */
object FileReaderActor {

  /**
   * Message for initializing the file to be read.
   *
   * This message tells the file reader actor which file has to be read.
   * The actor will open a channel for this path. After a successful
   * initialization, the file can be read.
   * @param path the path to the file to be read
   */
  case class InitFile(path: Path)

  /**
   * A message indicating the end of the file read by the file reader actor.
   * @param path the path of the currently read file
   */
  case class EndOfFile(path: Path)

  /**
   * A message requesting the given amount of data to be read.
   *
   * This message triggers a read operation. The specified amount of bytes is
   * read from the current file. When this is done a ''ReadResult'' message is
   * sent back. If there is no more data to be read, an ''EndOfFile'' message
   * is sent.
   * @param count the number of bytes to be read
   */
  case class ReadData(count: Int)

  /**
   * A message with the result of a read operation.
   *
   * This message is sent by the ''FileReaderActor'' to the requester after a
   * read operation finished successfully. It contains the bytes that have been
   * read. Note that the result array may only be filled partly. Therefore, the
   * length of the data read is stored as an additional attribute.
   * @param data an array with the bytes read
   */
  case class ReadResult(data: Array[Byte], length: Int)

  /**
   * An internally used message sent when a read operation of the channel was
   * completed.
   *
   * The data is passed as an array which may only be filled partly. The
   * ''length'' attribute contains the number of valid bytes contained in this
   * array. If the length is less than zero, the end of the file has been
   * reached.
   * @param target the target actor for sending the response to
   * @param operationNumber the number of the read operation this result is for
   * @param data the data read from the actor
   * @param length the number of bytes read (may be less than the length of the result array)
   * @param exception an exception that was thrown during the operation
   */
  private[io] case class ChannelReadComplete(target: ActorRef, operationNumber: Long, data:
  Array[Byte] = null, length: Int = 0, exception: Option[Throwable] = None)

}

/**
 * An actor that reads a file chunk-wise.
 *
 * Using this actor a file can be read in single chunks. First the file to be
 * read has to be initialized. Then messages requesting further data are sent
 * repeatedly. They are answered by data messages containing a byte array with
 * the results of read operations. When the end of the file is reached a
 * corresponding end message is sent.
 *
 * Internally, this actor uses features from Java NIO to read portions of a
 * file asynchronously.
 *
 * @param channelFactory the factory for creating file channels
 */
class FileReaderActor(channelFactory: FileChannelFactory) extends Actor {
  /** The path to the file which is currently read. */
  private var currentPath: Path = _

  /** A channel for reading from the current file. */
  private var channel: AsynchronousFileChannel = _

  /** The current position in the file to be read. */
  private var position = 0L

    /**
     * A counter for read operations. This is also used to deal with results
     * of reads from operations which have been canceled.
     */
   private var readOperationNumber = 0L

  /**
   * Creates a new instance of ''FileReaderActor'' using a default
   * ''FileChannelFactory''.
   * @return the newly created instance
   */
  def this() = this(new FileChannelFactory)

  override def receive: Receive = {
    case InitFile(path) =>
      closeChannel()
      channel = channelFactory createChannel path
      currentPath = path
      position = 0
      readOperationNumber += 1

    case ReadData(count) =>
      if (channel == null) {
        sender ! EndOfFile(null)
      } else {
        readBytes(count)
      }

    case ChannelReadComplete(target, operationNo, data, length, ex) =>
      if (readOperationNumber == operationNo && channel != null) {
        if (ex.isDefined) {
          throw wrapInIoException(ex.get)
        }
        target ! processChannelRead(data, length)
      }

    case CloseRequest =>
      closeChannel()
      sender ! CloseAck(self)
  }

  /**
   * Creates a ''CompletionHandler'' to be used by an asynchronous read
   * operation. When the read finishes the handler reports the result to the
   * specified actor.
   * @param actor the actor to be notified by the handler
   * @param dataArray the array for storing the bytes read
   * @return the completion handler
   */
  private[io] def createCompletionHandler(actor: ActorRef, dataArray: Array[Byte]):
  CompletionHandler[Integer, ActorRef] = {
    val currentReadOperationNo = readOperationNumber
    new CompletionHandler[Integer, ActorRef] {

      override def completed(bytesRead: Integer, attachment: ActorRef): Unit = {
        actor ! ChannelReadComplete(attachment, currentReadOperationNo, dataArray, bytesRead)
      }

      override def failed(exc: Throwable, attachment: ActorRef): Unit = {
        actor ! ChannelReadComplete(attachment, currentReadOperationNo, exception = Some(exc))
      }
    }
  }

  /**
   * Reads the given number of bytes from the current channel.
   * @param count the number of bytes to be read
   */
  private def readBytes(count: Int): Unit = {
    val dataArray = new Array[Byte](count)
    val buffer = ByteBuffer wrap dataArray
    channel.read(buffer, position, sender(), createCompletionHandler(self, dataArray))
  }

  /**
   * Handles the results of a channel read. Depending on the passed in option, the
   * result to be sent to the receiver is generated.
   * @param result the result of the read operation
   * @return the message to be sent to the querying actor
   */
  private def processChannelRead(result: Array[Byte], length: Int): Any = {
    if (length >= 0) {
      position += length
      ReadResult(result, length)
    } else {
      closeChannel()
      EndOfFile(currentPath)
    }
  }

  /**
   * Closes the current channel if it exists.
   */
  private def closeChannel() {
    if (channel != null) {
      channel.close()
      channel = null
    }
  }

  /**
   * Wraps the specified exception in an IO exception. If it is already an
   * IOException, it is returned directly.
   * @param ex the exception to be wrapped
   * @return the resulting IOException
   */
  private def wrapInIoException(ex: Throwable): IOException =
    ex match {
      case ioex: IOException => ioex
      case other: Throwable => new IOException(other)
    }
}
