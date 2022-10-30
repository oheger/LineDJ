/*
 * Copyright 2015-2022 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.player.engine.impl

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream._
import akka.util.ByteString
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.impl.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.impl.RadioStreamActor._
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

private object RadioStreamActor {
  /**
    * A message this actor sends to itself when an audio stream could be
    * resolved. The m3u data was read, and the final audio stream URL was
    * discovered.
    *
    * @param audioStreamRef the resolved audio stream reference
    */
  private case class AudioStreamResolved(audioStreamRef: StreamReference)

  /**
    * A message this actor sends to itself when the source for the audio
    * stream becomes available. Then the audio stream can be started.
    *
    * @param source the source for the audio stream to manage
    */
  private case class AudioSourceResolved(source: Source[ByteString, Future[IOResult]])

  /**
    * A message sent by the managed stream when its initialization is complete.
    */
  private case object StreamInitialized

  /**
    * A message sent by the managed stream when it is done. If this happens
    * unexpectedly, this indicates an error of the radio stream.
    */
  private case object StreamDone

  /**
    * A message sent by the managed stream when it encounters an error.
    *
    * @param exception the exception causing the failure
    */
  private case class StreamFailure(exception: Throwable)

  /**
    * The ACK message. This is sent back to the stream to ack receiving of the
    * last chunk of data and requesting the next one.
    */
  private case object Ack

  /**
    * Creates a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param config         the player configuration
    * @param streamRef      the reference to the audio stream for playback
    * @param sourceListener reference to an actor that is sent an audio source
    *                       message when the final audio stream is available
    * @param m3uReader      the object to resolve playlist references
    * @return creation properties for a new actor instance
    */
  def apply(config: PlayerConfig,
            streamRef: StreamReference,
            sourceListener: ActorRef,
            m3uReader: M3uReader = new M3uReader): Props =
    Props(classOf[RadioStreamActor], config, streamRef, sourceListener, m3uReader)
}

/**
  * An actor class that reads audio data from an internet radio stream.
  *
  * This actor implements the functionality of a data source for
  * [[PlaybackActor]]. An instance is initialized with a [[StreamReference]].
  * If this reference points to a m3u file, an [[M3uReader]] object is used to
  * process the file and extract the actual URI of the audio stream. Otherwise,
  * the reference is considered to already represent the audio stream.
  *
  * With the audio stream at hand a stream is created using this actor as sink
  * (with backpressure). The stream has a buffer with a configurable size. This
  * actor then handles ''GetAudioData'' messages by passing the last received
  * chunk of data to the caller. Note that the requested size of audio data is
  * ignored, and always the configured chunk size is used; for an infinite
  * stream of radio data, this should be suitable behavior.
  *
  * It is sometimes necessary to know the actual URL of the audio stream that
  * is played. Therefore, this actor sends an ''AudioSource'' message to a
  * listener actor passed to the constructor when the final audio stream is
  * determined.
  *
  * Supervision is implemented by delegating to the parent actor. When this
  * actor terminates, the managed stream is terminated as well.
  *
  * An instance of this actor class can only be used for reading a single
  * audio stream. It cannot be reused and has to be closed afterwards.
  *
  * @param config         the player configuration
  * @param streamRef      the reference to the audio stream for playback
  * @param sourceListener reference to an actor that is sent an audio source
  *                       message when the final audio stream is available
  * @param m3uReader      the object to resolve playlist references
  */
private class RadioStreamActor(config: PlayerConfig,
                               streamRef: StreamReference,
                               sourceListener: ActorRef,
                               m3uReader: M3uReader) extends Actor with ActorLogging {
  private implicit val materializer: Materializer = Materializer(context)

  private implicit val ec: ExecutionContext = context.dispatcher

  /** Stores a kill switch to terminate the managed stream. */
  private var optKillSwitch: Option[KillSwitch] = None

  /** Stores a reference to a caller that requested data from this actor. */
  private var optDataRequest: Option[ActorRef] = None

  /**
    * Stores the current block of data received from the stream, together with
    * the actor reference to send an Ack message.
    */
  private var optStreamData: Option[(BufferDataResult, ActorRef)] = None

  override def preStart(): Unit = {
    super.preStart()

    m3uReader.resolveAudioStream(config, streamRef) onComplete { triedReference =>
      val resultMsg = triedReference match {
        case Failure(exception) =>
          StreamFailure(new IllegalStateException("Resolving of stream reference failed.", exception))
        case Success(value) => AudioStreamResolved(value)
      }
      self ! resultMsg
    }
  }

  override def receive: Receive = {
    case AudioStreamResolved(ref) =>
      log.info("Playing audio stream from {}.", ref.uri)
      sourceListener ! AudioSource(ref.uri, Long.MaxValue, 0, 0)
      ref.createSource(chunkSize = config.bufferChunkSize) foreach { source =>
        self ! AudioSourceResolved(source)
      }

    case AudioSourceResolved(source) =>
      log.info("Source of the audio stream has been resolved.")
      optKillSwitch = Some(startStream(source))

    case StreamInitialized =>
      log.info("Audio stream has been initialized.")
      sender() ! Ack

    case data: BufferDataResult =>
      optStreamData = Some((data, sender()))
      sendDataIfPossible()

    case StreamDone =>
      streamError(new IllegalStateException("Unexpected end of audio stream."))

    case StreamFailure(exception) =>
      log.error(exception, "Error from managed stream. Terminating this actor.")
      self ! PoisonPill

    case PlaybackActor.GetAudioData(_) =>
      optDataRequest = Some(sender())
      sendDataIfPossible()

    case CloseRequest =>
      optKillSwitch foreach (_.shutdown())
      optStreamData foreach (_._2 ! Ack)
      context.become(closing(sender()))
  }

  /**
    * A receive function that becomes active when the actor receives a close
    * request. It waits for the stream to terminate to send the close ack to
    * the client. Also, the case is handled that the audio stream has not yet
    * been resolved.
    *
    * @param client the client actor expecting the close ack
    * @return the receive function for closing state
    */
  private def closing(client: ActorRef): Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case BufferDataResult(_) =>
      sender() ! Ack

    case PlaybackActor.GetAudioData(_) =>
      sender() ! BufferDataComplete

    case msg =>
      log.info("Received message {} interpreted as stream end.", msg)
      client ! CloseAck(self)
  }

  /**
    * Starts the managed audio stream for the given source. Data is read from
    * the source, and the buffer is filled. This actor (acting as sink of the
    * stream) gets notified when data is available.
    *
    * @param source the source of the managed audio stream
    * @return a [[KillSwitch]] to terminate the stream
    */
  private def startStream(source: Source[ByteString, Future[IOResult]]): KillSwitch =
    source.buffer(config.inMemoryBufferSize / config.bufferChunkSize, OverflowStrategy.backpressure)
      .map { data => BufferDataResult(data) }
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(createStreamSink())(Keep.left)
      .withAttributes(Attributes.inputBuffer(initial = 1, max = 1))
      .run()

  /**
    * Creates the ''Sink'' for the managed stream. This is the actor itself
    * receiving messages with stream data or lifecycle updates.
    *
    * @return the ''Sink'' for the managed stream
    */
  private def createStreamSink(): Sink[BufferDataResult, NotUsed] =
    Sink.actorRefWithBackpressure[BufferDataResult](ref = self, onInitMessage = StreamInitialized,
      onCompleteMessage = StreamDone, ackMessage = Ack, onFailureMessage = StreamFailure.apply)

  /**
    * Checks whether both data and a request for data is available. In this
    * case, the request can be served. Then the stream can be ACKed, and the
    * state of this actor can be reset.
    */
  private def sendDataIfPossible(): Unit = {
    val sendOperation = for {
      client <- optDataRequest
      data <- optStreamData
    } yield () => {
      client ! data._1
      data._2 ! Ack
    }

    sendOperation foreach { op =>
      op()
      optDataRequest = None
      optStreamData = None
    }
  }

  /**
    * Raises an error by sending a failure message with the given exception to
    * this actor. This will cause the actor to terminate.
    *
    * @param exception the exception
    */
  private def streamError(exception: Throwable): Unit = {
    self ! StreamFailure(exception)
  }
}
