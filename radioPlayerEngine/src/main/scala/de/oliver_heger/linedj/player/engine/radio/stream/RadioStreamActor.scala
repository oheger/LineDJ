/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.stream

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, typed}
import akka.stream._
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import akka.{Done, NotUsed}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.PlaybackActor
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamActor._
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, MetadataNotSupported, RadioEvent, RadioMetadataEvent, RadioSource}
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

private object RadioStreamActor {
  /**
    * A message this actor sends to itself when the audio stream to play has
    * been created. The create operation is done asynchronously by the
    * [[RadioStreamBuilder]].
    *
    * @param builderResult the result from the stream builder
    */
  private case class AudioStreamCreated(builderResult: RadioStreamBuilder.BuilderResult[NotUsed, Future[Done]])

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
    * @param streamSource   the radio source to the audio stream for playback
    * @param sourceListener reference to an actor that is sent an audio source
    *                       message when the final audio stream is available
    * @param eventActor     the actor to publish radio events
    * @param streamBuilder  the object to build the radio stream
    * @return creation properties for a new actor instance
    */
  def apply(config: PlayerConfig,
            streamSource: RadioSource,
            sourceListener: ActorRef,
            eventActor: typed.ActorRef[RadioEvent],
            streamBuilder: RadioStreamBuilder): Props =
    Props(classOf[RadioStreamActor], config, streamSource, sourceListener, eventActor, streamBuilder)
}

/**
  * An actor class that reads audio data from an internet radio stream.
  *
  * This actor implements the functionality of a data source for
  * [[PlaybackActor]]. An instance is initialized with a stream URI, which
  * either references the radio audio stream directly or points to a m3u file.
  * An [[M3uReader]] object is used to obtain the actual URI of the audio
  * stream.
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
  * determined. In addition, if supported by the radio stream, metadata is
  * extracted and converted to radio metadata events, which are sent to the
  * provided event publisher actor.
  *
  * Supervision is implemented by delegating to the parent actor. When this
  * actor terminates, the managed stream is terminated as well.
  *
  * An instance of this actor class can only be used for reading a single
  * audio stream. It cannot be reused and has to be closed afterwards.
  *
  * @param config         the player configuration
  * @param streamSource   the radio source pointing to the audio stream for
  *                       playback
  * @param sourceListener reference to an actor that is sent an audio source
  *                       message when the final audio stream is available
  * @param eventActor     the actor to publish radio events
  * @param streamBuilder  the object to build the radio stream
  */
private class RadioStreamActor(config: PlayerConfig,
                               streamSource: RadioSource,
                               sourceListener: ActorRef,
                               eventActor: typed.ActorRef[RadioEvent],
                               streamBuilder: RadioStreamBuilder) extends Actor with ActorLogging {
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

    val sinkAudio = createAudioSink()
    val sinkMeta = createMetadataSink()

    streamBuilder.buildRadioStream(config, streamSource.uri, sinkAudio, sinkMeta) onComplete { triedResult =>
      val resultMsg = triedResult match {
        case Failure(exception) =>
          StreamFailure(new IllegalStateException("Resolving of stream reference failed.", exception))
        case Success(value) => AudioStreamCreated(value)
      }
      self ! resultMsg
    }
  }

  override def receive: Receive = {
    case AudioStreamCreated(result) =>
      log.info("Playing audio stream from {}.", result.resolvedUri)
      sourceListener ! AudioSource(result.resolvedUri, Long.MaxValue, 0, 0)
      if (!result.metadataSupported) {
        log.info("No support for metadata.")
        eventActor ! RadioMetadataEvent(MetadataNotSupported)
      }
      optKillSwitch = Some(result.killSwitch)
      result.graph.run()

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
    * Creates the ''Sink'' for the audio data of the managed stream. This is
    * the actor itself receiving messages with stream data or lifecycle
    * updates.
    *
    * @return the audio ''Sink'' for the managed stream
    */
  private def createAudioSink(): Sink[ByteString, NotUsed] = {
    val sink = Sink.actorRefWithBackpressure[BufferDataResult](ref = self, onInitMessage = StreamInitialized,
      onCompleteMessage = StreamDone, ackMessage = Ack, onFailureMessage = StreamFailure.apply)
    sink.contramap[ByteString] { data => BufferDataResult(data) }
  }

  /**
    * Creates the ''Sink'' to process the metadata of the managed stream. This
    * sink generates radio metadata events and passes them to the event actor.
    *
    * @return the sink for processing metadata
    */
  private def createMetadataSink(): Sink[ByteString, Future[Done]] =
    Sink.foreach[ByteString] { meta =>
      val data = CurrentMetadata(meta.utf8String)
      eventActor ! RadioMetadataEvent(data)
    }

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
