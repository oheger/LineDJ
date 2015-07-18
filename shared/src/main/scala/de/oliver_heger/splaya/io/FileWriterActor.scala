package de.oliver_heger.splaya.io

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.nio.file.{OpenOption, StandardOpenOption}

import akka.actor.ActorRef
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.FileWriterActor.{ChannelWriteResult, WriteResult, WriteResultStatus}

/**
 * The companion object for ''FileWriterActor''.
 *
 * This object defines some messages classes which are accepted by the actor.
 */
object FileWriterActor {

  /**
   * An enumeration class defining the different status codes in the result of
   * a write operation.
   *
   * These codes can be used to find out whether the operation was successful
   * or - if not - what was the reason for a failure.
   */
  object WriteResultStatus extends Enumeration {
    /**
     * Result code indicating that the last write operation was successful.
     */
    val Ok = Value

    /**
     * The operation failed because no channel has been initialized. This
     * status code is generated when the actor is passed a write request
     * before the target file has been initialized or after it has been
     * closed.
     */
    val NoChannel = Value
  }

  /**
   * A message class representing the result of a write operation.
   *
   * Messages of this type are sent by the ''FileWriterActor'' as response on
   * write requests. By inspecting the status code, the receiver can find out
   * whether the operation was successful.
   * @param status the status code of the operation
   * @param bytesWritten contains the number of bytes written by this operation;
   *                     note: if the write operation was successful, this number
   *                     is the same as in the original write request; it is
   *                     included here for convenience purposes as it simplifies
   *                     the processing of messages of this type
   */
  case class WriteResult(status: WriteResultStatus.Value, bytesWritten: Int)

  /**
   * An internally used message class to report the completion of an
   * asynchronous write operation. Messages of this type are sent from the
   * ''CompletionHandler'' passed to the channel. They contain all information
   * for the actor to process this result.
   * @param target the actor for sending a write result
   * @param operationNumber the current operation number
   * @param data the data array that was subject of the write operation
   * @param offset the offset into the data array
   * @param length the number of bytes to be written
   * @param bytesWritten the number of bytes actually written; if this is less than ''length'',
   *                     another write operation has to be triggered!
   * @param exception an optional exception which occurred during the operation
   */
  private case class ChannelWriteResult(target: ActorRef, operationNumber: Long, data:
  Array[Byte], offset: Int, length:
  Int, bytesWritten: Int, exception: Option[Throwable] = None)

}

/**
 * An actor which writes a file chunk-wise.
 *
 * This actor is the counterpart of [[FileReaderActor]]. It accepts messages
 * for defining an output file and containing chunks of data to be written.
 * Each write operation is acknowledged with a ''WriteResult'' message. Then
 * the next chunk of data to be written can be processed.
 *
 * @param channelFactory the factory for creating file channels
 */
class FileWriterActor(override val channelFactory: FileChannelFactory) extends ChannelHandler {
  override val channelOpenOptions: Seq[OpenOption] = List(StandardOpenOption.CREATE,
    StandardOpenOption.WRITE)

  /**
   * Creates a new instance of ''FileWriterActor'' with a default
   * ''FileChannelFactory''.
   */
  def this() = this(new FileChannelFactory)

  override protected def specialReceive: Receive = {
    case as: ArraySource =>
      handleRequest(WriteResult(WriteResultStatus.NoChannel, 0), null) {
        writeData(as.data, as.offset, as.length)
      }

    case r: ChannelWriteResult =>
      processAsyncResult(r.operationNumber, r) { r =>
        handleFailedOperation(r.exception)
        processWriteResult(r)
      }
  }

  /**
   * Handles a ''ChannelWriteResult'' message. If not all bytes were written by
   * the asynchronous write operation, another write has to be initiated.
   * @param r the result object
   */
  private def processWriteResult(r: ChannelWriteResult): Unit = {
    position += r.bytesWritten
    if (r.bytesWritten == r.length) {
      r.target ! WriteResult(WriteResultStatus.Ok, r.bytesWritten)
    } else {
      writeData(r.data, r.offset + r.bytesWritten, r.length - r.bytesWritten)
    }
  }

  /**
   * Initiates a write operation for the specified source object.
   * @param data the array with the data to be written
   * @param offset the start offset into the array
   * @param length the number of bytes to be written
   */
  private def writeData(data: Array[Byte], offset: Int, length: Int): Unit = {
    val buffer = ByteBuffer.wrap(data, offset, length)
    val operationNumber = currentOperationNumber
    val completionHandler = new CompletionHandler[Integer, ActorRef] {
      override def completed(result: Integer, attachment: ActorRef): Unit = {
        self ! ChannelWriteResult(target = attachment, bytesWritten = result,
          data = data, offset = offset, length = length, operationNumber = operationNumber)
      }

      override def failed(exc: Throwable, attachment: ActorRef): Unit = {
        self ! ChannelWriteResult(target = attachment, exception = Some(exc),
          bytesWritten = 0, data = data, offset = offset, length = length, operationNumber =
            operationNumber)
      }
    }

    currentChannel.get.write(buffer, position, sender(), completionHandler)
  }
}
