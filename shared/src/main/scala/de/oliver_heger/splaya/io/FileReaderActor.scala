package de.oliver_heger.splaya.io

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.nio.file.{Path, StandardOpenOption}

import akka.actor.ActorRef
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.FileReaderActor._

/**
 * Companion object for ''FileReaderActor''.
 */
object FileReaderActor {

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
   * A message requesting that a number of bytes be skipped.
   *
   * On receiving this message, the reader actor just updates its current
   * position into the file by the given amount. The next read operation will
   * then read the data at the new position; so basically, a portion of the
   * file was skipped.
   * @param count the number of bytes to be skipped
   */
  case class SkipData(count: Int)

  /**
   * A message with the result of a read operation.
   *
   * This message is sent by the ''FileReaderActor'' to the requester after a
   * read operation finished successfully. It contains the bytes that have been
   * read. Note that the result array may only be filled partly. Therefore, the
   * length of the data read is stored as an additional attribute.
   * @param data an array with the bytes read
   */
  case class ReadResult(data: Array[Byte], length: Int) extends ArraySource

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
  private case class ChannelReadComplete(target: ActorRef, operationNumber: Long, data:
  Array[Byte] = null, length: Int = 0, exception: Option[Throwable] = None)

  /**
   * Constant for an end of file message with an undefined path. This message
   * is sent on some opportunities, e.g. when requests are received, but no
   * channel is open.
   */
  private val MsgNoChannel = EndOfFile(null)

  /**
   * Constant for a result message which is sent in response on a read request
   * while another request in pending. This message basically indicates that
   * this actor is currently busy.
   */
  private val MsgRequestInProgress = ReadResult(Array.empty, 0)
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
 * Note that another read request is only accepted after the current request has
 * been processed and the answer sent out. So the protocol requires waiting for
 * the read result before a new request is created.
 *
 * Internally, this actor uses features from Java NIO to read portions of a
 * file asynchronously.
 *
 * @param channelFactory the factory for creating file channels
 */
class FileReaderActor(override val channelFactory: FileChannelFactory) extends ChannelHandler {
  /**
   * Creates a new instance of ''FileReaderActor'' using a default
   * ''FileChannelFactory''.
   * @return the newly created instance
   */
  def this() = this(new FileChannelFactory)

  /**
   * @inheritdoc This class opens a channel for read access.
   */
  override val channelOpenOptions = List(StandardOpenOption.READ)

  override def specialReceive: Receive = {
    case ReadData(count) =>
      handleRequest(MsgNoChannel, MsgRequestInProgress) {
        readBytes(count)
      }

    case SkipData(count) =>
      handleRequest(MsgNoChannel, MsgRequestInProgress) {
        position += count
        requestCompleted()
      }

    case c: ChannelReadComplete =>
      processAsyncResult(c.operationNumber, c) { result =>
        handleFailedOperation(c.exception)
        result.target ! processChannelRead(result.data, result.length)
      }
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
    val currentReadOperationNo = currentOperationNumber
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
    currentChannel.get.read(buffer, position, sender(), createCompletionHandler(self, dataArray))
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
}
