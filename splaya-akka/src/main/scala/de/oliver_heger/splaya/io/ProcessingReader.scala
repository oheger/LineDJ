package de.oliver_heger.splaya.io

import akka.actor.{Actor, ActorRef}
import de.oliver_heger.splaya.io.ChannelHandler.InitFile
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData, ReadResult}

/**
 * A trait supporting the modification of data produced by a
 * [[FileReaderActor]] before it gets passed to the caller.
 *
 * This trait is kind of an equivalent of the ''FilterInputStream'' class from
 * the Java library. It defines a framework for wrapping a reader actor (nested
 * to an arbitrary level) in which each actor may process data produced by
 * earlier members in the chain.
 *
 * The basic idea is that a ''ReadData'' request is handled by requesting a
 * corresponding amount of data from the wrapped actor. When this data arrives
 * it can be processed and altered if necessary. Processed data can then be
 * published - this causes the data to be sent to the original caller.
 *
 * This trait implements the framework for processing data in this way, but it
 * does not do any processing on its own. The base implementation merely copies
 * the data received from the wrapped actor. Concrete implementations can
 * inject their specific processing logic. A concrete implementation also has
 * to provide the wrapped reader actor.
 *
 * Processing mainly takes place in the methods ''readRequestReceived()'', and
 * ''dataRead()''. The former is called when a request to read data of a
 * certain amount is received. The default implementation forwards this request
 * to the underlying reader actor, but a concrete implementation is free to
 * override this behavior and do different things. ''dataRead()'' is
 * called with data retrieved from the underlying actor. A concrete can
 * evaluate the data received, process it, and finally produce results to be
 * sent to the caller by invoking ''publish()''. Note that it is important
 * that in ''dataRead()'' either ''publish()'' is called or new data is
 * requested from the underlying actor. Otherwise, no data is sent for the
 * original read request!
 */
trait ProcessingReader extends Actor {
  /** The wrapped reader actor. */
  val readerActor: ActorRef

  /** A stream for accumulating read results. */
  private val receivedData = new DynamicInputStream

  /** A stream for accumulating published data. */
  private val publishedData = new DynamicInputStream

  /**
   * An option storing the current caller of a read operation. This is used to
   * propagate published results.
   */
  private var optCaller: Option[ActorRef] = None

  /**
   * An option storing the reference to an actor which requested to close this
   * actor. This is the actor to send the closing acknowledge to.
   */
  private var optClosingActor: Option[ActorRef] = None

  /** The number of bytes requested from the wrapped actor. */
  private var bytesRequestedFromWrappedActor = 0

  /** The number of bytes requested for the current read operation. */
  private var currentOperationCount = 0

  /**
   * A flag that keeps track whether a response has been sent to the current
   * caller.
   */
  private var responseSent = false

  /**
   * An option storing an end-of-file message received from the wrapped
   * reader. This is also used to indicate that the current file has been read
   * completely.
   */
  private var optEndOfFile: Option[EndOfFile] = Some(EndOfFile(null))

  override def receive: Receive = {
    case init: InitFile =>
      optEndOfFile = None
      receivedData.clear()
      publishedData.clear()
      readerActor ! init
      readOperationInitialized(init)

    case ReadData(count) =>
      if (handleReadRequest(sender(), count) <= 0) {
        optEndOfFile match {
          case Some(eof) =>
            sender ! eof

          case None =>
            optCaller = Some(sender())
            currentOperationCount = count
            readRequestReceived(count)
        }
      }

    case result: ReadResult =>
      handleReadResult(result)

    case eof: EndOfFile =>
      handleEndOfFile(eof)

    case CloseRequest =>
      readerActor ! CloseRequest
      optClosingActor = Some(sender())

    case CloseAck(actor) if actor == readerActor =>
      optClosingActor foreach (_ ! CloseAck(self))
      optClosingActor = None
  }

  /**
   * Returns a flag whether the end of the current file has been reached.
   * @return a flag whether the end of the current file has been reached
   */
  def endOfFile = optEndOfFile.isDefined

  /**
   * Returns the number of bytes that was requested by the currently processed
   * request.
   * @return the number of bytes to be read by the current request
   */
  protected def currentReadRequestSize: Int = currentOperationCount

  /**
   * Triggers a read operation from the wrapped actor. This method sends a
   * request to the wrapped actor. When an answer is received and the requested
   * amount of data is available (or the end of file is reached) the data is
   * passed to ''readResultReceived()''. If the end of the current file has
   * already been reached, no message is sent out to the underlying actor.
   * @param count the number of bytes to be read
   */
  protected def readFromWrappedActor(count: Int): Unit = {
    if (!endOfFile) {
      readerActor ! ReadData(count)
      bytesRequestedFromWrappedActor = count
    }
  }

  /**
   * Publishes data that is to be sent to the caller. This method can be
   * called every time a chunk of data has been completed. The data
   * passed is used to answer (pending or future) read requests to this actor.
   * @param data the data to be published
   */
  protected def publish(data: Array[Byte]): Unit = {
    publishedData append readResultFor(data)
    optCaller foreach (handleReadRequest(_, currentOperationCount))
    optCaller = None
  }

  /**
   * Notifies this actor that a new read operation has been initialized. A
   * concrete implementation can use this callback to perform some
   * initializations. This base implementation is empty
   * @param initFile the ''InitFile'' message
   */
  protected def readOperationInitialized(initFile: InitFile): Unit = {
  }

  /**
   * Notifies this actor that a request for reading data has been received.
   * Per default, this request is propagated to the wrapped reader actor. A
   * concrete implementation can hook in for instance to request data of a
   * different size or to ignore data.
   * @param count the number of bytes from the read request
   */
  protected def readRequestReceived(count: Int): Unit = {
    readFromWrappedActor(count)
  }

  /**
   * Notifies this object that results of a read operation from the wrapped
   * actor have been received. This class tries to read the full amount of
   * data that has been requested. If necessary, multiple read requests are
   * sent to the wrapped actor. When all data is available (or the end of
   * the current file is reached) it is passed to this method. Here the
   * main processing logic can be placed.
   * @param data the array with the data that has been read
   */
  protected def dataRead(data: Array[Byte]): Unit = {
    publish(data)
  }

  /**
   * Notifies this object that the current file has been completely processed.
   * A concrete implementation can use this callback for instance to do some
   * cleanup or to write an addendum into the current stream (data passed to
   * ''publish()'' is still delivered to the caller). This base implementation
   * is empty.
   */
  protected def afterProcessing(): Unit = {
  }

  /**
   * Serves a read request from the buffer with published data. If currently
   * data is available, a response is sent based on it (even if there is less
   * data than requested). The return value indicates the number of bytes sent
   * in the result.
   * @param caller the calling actor
   * @param count the number of requested bytes
   * @return the number of bytes sent in the response (0 for no response)
   */
  private def handleReadRequest(caller: ActorRef, count: Int): Int = {
    val len = math.min(publishedData.available(), count)
    if (len > 0) {
      val resultData = new Array[Byte](len)
      publishedData read resultData
      answerCaller(caller, readResultFor(resultData))
    }
    len
  }

  /**
   * Handles the result of a read operation. If now all bytes requested are
   * available, the data is passed to ''dataRead()''.
   * @param readResult the read result object
   */
  private def handleReadResult(readResult: ReadResult): Unit = {
    receivedData append readResult

    if (receivedData.available() >= bytesRequestedFromWrappedActor) {
      processReadData(bytesRequestedFromWrappedActor)
    } else {
      readerActor ! ReadData(bytesRequestedFromWrappedActor - receivedData.available())
    }
  }

  /**
   * Reads the given amount of bytes from the stream with received bytes
   * and passes it to the ''dataRead()'' method.
   * @param count the number of bytes to read
   */
  private def processReadData(count: Int): Unit = {
    val data = new Array[Byte](count)
    receivedData.read(data)
    dataRead(data)
  }

  /**
   * Handles an end of file message. If there is still data to be processed,
   * this is done now. Also, the ''afterProcessing()'' callback is invoked.
   * @param eof the end of file message
   */
  private def handleEndOfFile(eof: EndOfFile): Unit = {
    responseSent = false
    optEndOfFile = Some(eof)
    if (receivedData.available() > 0) {
      processReadData(receivedData.available())
    }
    afterProcessing()
    if (!responseSent) {
      answerCaller(eof)
    }
  }

  /**
   * Sends the specified message to the current caller actor. The return
   * value indicates whether a message could be sent ('''false''' means that
   * there is no current caller.
   * @param msg the message to be sent
   * @return a flag whether the message was sent
   */
  private def answerCaller(msg: Any): Boolean = {
    optCaller exists { actor =>
      answerCaller(actor, msg)
      true
    }
  }

  /**
   * Sends the specified message to the provided caller actor. This
   * method also keeps track the a response to the caller has been sent.
   * @param caller the caller actor to be notified
   * @param msg the message to be sent
   */
  private def answerCaller(caller: ActorRef, msg: Any): Unit = {
    caller ! msg
    responseSent = true
  }

  /**
   * Wraps an array inside a ''ReadResult'' object.
   * @param data the array with data to be wrapped
   * @return the ''ReadResult''
   */
  private def readResultFor(data: Array[Byte]): ReadResult = ReadResult(data, data.length)
}
