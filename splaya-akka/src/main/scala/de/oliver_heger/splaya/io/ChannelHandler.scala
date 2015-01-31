package de.oliver_heger.splaya.io

import java.io.IOException
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.{OpenOption, Path}

import akka.actor.Actor

object ChannelHandler {

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
   * A trait defining access to a byte array.
   *
   * Arrays can be used to transport data from a source to a sink. Sometimes,
   * the array is only partly filled. With this trait it is possible to find
   * out which parts of the array actually contain valid data.
   */
  trait ArraySource {
    /** The full data array stored in this object. */
    val data: Array[Byte]

    /** The offset into the data array on which valid data starts. */
    val offset = 0

    /** The length of the area containing valid data. */
    val length: Int
  }

}

/**
 * A trait providing common functionality for managing an
 * ''AsynchronousFileChannel'' object.
 *
 * This trait is mixed into actor implementations for reading and writing files
 * using non-blocking, asynchronous IO. A major part of the functionality
 * required for dealing with a file channel is already implemented here which
 * simplifies concrete actor implementations.
 */
trait ChannelHandler extends Actor {

  import de.oliver_heger.splaya.io.ChannelHandler._

  /** The channel factory used by this object. */
  val channelFactory: FileChannelFactory

  /**
   * A sequence with the options to be passed to the ''FileChannelFactory'' when creating a new
   * channel.
   */
  val channelOpenOptions: Seq[OpenOption]

  /**
   * The current position in the file to be processed. This member can be read and
   * updated by sub classes.
   */
  var position = 0L

  /** The path to the file which is currently read. */
  private var path: Path = _

  /** A channel for reading from the current file. */
  private var channel: Option[AsynchronousFileChannel] = None

  /**
   * A counter for IO operations. This is also used to deal with results
   * of IO operations which have been canceled.
   */
  private var operationNumber = 0L

  /**
   * A flag whether currently a request is processed by this actor. This actor
   * only accepts a new request after the former one has been processed.
   */
  private var requestPending = false

  /**
   * Returns the ''Path'' to the file currently processed. This member is only
   * defined if currently a file path was set; i.e. if a channel is open.
   * @return the path to the file currently processed
   */
  def currentPath = path

  /**
   * Returns the current operation number. The operation number is increased
   * every time a new file is initialized. It is used to avoid that results
   * of an older read operation are processed for a new file. (This could be
   * a problem as read operations are performed asynchronously.)
   * @return the current operation number
   */
  def currentOperationNumber = operationNumber

  /**
   * The ''Receive'' function of this actor. This implementation uses a combined
   * function with handlers for messages special to a concrete sub class
   * and handlers related to the managed channel.
   * @return the ''Receive'' function of this actor
   */
  override final def receive: Actor.Receive = specialReceive orElse channelReceive

  /**
   * A special ''Receive'' function used by a concrete implementation. From this
   * function and an internal function which handles channel-specific messages,
   * the final ''Receive'' function is constructed.
   * @return a function for handling special messages
   */
  protected def specialReceive: Receive

  /**
   * Returns the current file channel.
   * @return an option with the current file channel
   */
  protected def currentChannel = channel

  /**
   * Closes the current channel if it exists.
   */
  protected def closeChannel() {
    if (channel.isDefined) {
      channel.get.close()
      channel = None
    }
  }

  /**
   * Wraps the specified exception in an IO exception. If it is already an
   * IOException, it is returned directly.
   * @param ex the exception to be wrapped
   * @return the resulting IOException
   */
  protected def wrapInIoException(ex: Throwable): IOException =
    ex match {
      case ioex: IOException => ioex
      case other: Throwable => new IOException(other)
    }

  /**
   * Checks whether the passed in ''Option'' contains an exception. If this
   * is the case, it is wrapped in an ''IOException'' and thrown.
   * @param ex an optional exception
   */
  protected def handleIOException(ex: Option[Throwable]): Unit = {
    if (ex.isDefined) {
      throw wrapInIoException(ex.get)
    }
  }

  /**
   * Executes a function on the result of an asynchronous operation if this is
   * still possible. This method checks whether the result is still valid; it
   * may be outdated when the channel has been closed in the meantime or a new
   * file was opened. In this case, the function is ignored; otherwise, it is
   * called with the passed in parameter.
   * @param operationNo the operation number for the result
   * @param result the result object to be processed
   * @param f the function for processing the result
   * @tparam R the type of the result to be processed
   */
  protected def processAsyncResult[R](operationNo: Long, result: R)(f: R => Unit): Unit = {
    if (operationNumber == operationNo && channel.isDefined) {
      requestPending = false
      f(result)
    }
  }

  /**
   * Handles a new request for a channel operation. This method checks whether
   * a new request can be accepted. If not - because no channel is open or
   * another request is currently processed -, a specific result is sent to the
   * caller. Otherwise, a function is executed actually handling the request.
   * @param noChannelResult the result to sent if no channel is open
   * @param pendingResult the result to sent if this actor is busy
   * @param f the function for handling the request
   */
  protected def handleRequest(noChannelResult: => Any, pendingResult: => Any)(f: => Unit): Unit = {
    if (currentChannel.isEmpty) {
      sender ! noChannelResult
    } else {
      if (requestPending) {
        sender ! pendingResult
      } else {
        f
        requestPending = true
      }
    }
  }

  /**
   * A partial function handling messages related to the managed channel.
   * The overall ''receive'' implementation is using this function.
   * @return a function for handling messages related to the managed channel
   */
  private def channelReceive: Receive = {
    case InitFile(filePath) =>
      closeChannel()
      channel = Option(channelFactory.createChannel(filePath, channelOpenOptions: _*))
      path = filePath
      position = 0
      operationNumber += 1
      requestPending = false

    case CloseRequest =>
      closeChannel()
      sender ! CloseAck(self)
  }
}
