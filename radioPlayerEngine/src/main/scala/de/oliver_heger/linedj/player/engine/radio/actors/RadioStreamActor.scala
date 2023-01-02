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

package de.oliver_heger.linedj.player.engine.radio.actors

import akka.NotUsed
import akka.actor.typed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream._
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.PlaybackActor
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, MetadataNotSupported, RadioEvent, RadioMetadataEvent}
import de.oliver_heger.linedj.player.engine.radio.actors.RadioStreamActor._
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

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
    * A message this actor sends to itself when the response for the GET
    * request to the audio stream becomes available. Then the audio stream can
    * be started.
    *
    * @param response the response for the stream request
    */
  private case class AudioStreamResponse(response: HttpResponse)

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
    * The headers to be added to requests for a radio stream. Here the header
    * asking for metadata is included. If the response contains a corresponding
    * header with the audio data chunk size, the stream actually supports
    * metadata.
    */
  private val RadioStreamRequestHeaders = List(RawHeader("Icy-MetaData", "1"))

  /**
    * The name of the header defining the size of audio chunks if the metadata
    * protocol is supported. If this header is found in the response for a
    * request to a radio stream, metadata extraction is applied.
    */
  private val AudioChunkSizeHeader = "icy-metaint"

  /**
    * Creates a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param config          the player configuration
    * @param streamRef       the reference to the audio stream for playback
    * @param sourceListener  reference to an actor that is sent an audio source
    *                        message when the final audio stream is available
    * @param eventActor      the actor to publish radio events
    * @param optM3uReader    the optional object to resolve playlist references;
    *                        if unspecified, the actor creates its own instance
    * @param optStreamLoader the optional object for loading radio streams; if
    *                        unspecified, the actor creates its own instance
    * @return creation properties for a new actor instance
    */
  def apply(config: PlayerConfig,
            streamRef: StreamReference,
            sourceListener: ActorRef,
            eventActor: typed.ActorRef[RadioEvent],
            optM3uReader: Option[M3uReader] = None,
            optStreamLoader: Option[HttpStreamLoader] = None): Props =
    Props(classOf[RadioStreamActor], config, streamRef, sourceListener, eventActor, optM3uReader, optStreamLoader)
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
  * @param config          the player configuration
  * @param streamRef       the reference to the audio stream for playback
  * @param sourceListener  reference to an actor that is sent an audio source
  *                        message when the final audio stream is available
  * @param eventActor      the actor to publish radio events
  * @param optM3uReader    the optional object to resolve playlist references
  * @param optStreamLoader the optional object to load radio streams
  */
private class RadioStreamActor(config: PlayerConfig,
                               streamRef: StreamReference,
                               sourceListener: ActorRef,
                               eventActor: typed.ActorRef[RadioEvent],
                               optM3uReader: Option[M3uReader],
                               optStreamLoader: Option[HttpStreamLoader]) extends Actor with ActorLogging {
  private implicit val materializer: Materializer = Materializer(context)

  private implicit val ec: ExecutionContext = context.dispatcher

  /**
    * The object for loading radio streams. The object can either be passed at
    * construction time or it is created manually.
    */
  private var streamLoader: HttpStreamLoader = _

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

    implicit val actorSystem: ActorSystem = context.system
    streamLoader = optStreamLoader getOrElse new HttpStreamLoader

    val m3uReader = optM3uReader getOrElse new M3uReader(streamLoader)
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
      streamLoader.sendRequest(createRadioStreamRequest(ref)) onComplete {
        case Success(source) => self ! AudioStreamResponse(source)
        case Failure(exception) => self ! StreamFailure(exception)
      }

    case AudioStreamResponse(response) =>
      log.info("Response of the audio stream has arrived.")
      optKillSwitch = Some(startStream(response))

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
    * Returns the request to query the radio stream represented by the passed
    * in reference.
    *
    * @param ref the reference to the radio stream
    * @return the HTTP request to load this stream
    */
  private def createRadioStreamRequest(ref: StreamReference): HttpRequest =
    HttpRequest(uri = ref.uri, headers = RadioStreamRequestHeaders)

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
    * the response entity, and the buffer is filled. This actor (acting as sink
    * of the stream) gets notified when data is available. If metadata is
    * supported, it is extracted and published via events using the event
    * actor.
    *
    * @param streamResponse the response for the stream request
    * @return a [[KillSwitch]] to terminate the stream
    */
  private def startStream(streamResponse: HttpResponse): KillSwitch = {
    val optChunkSize = extractChunkSizeHeader(streamResponse)
    optChunkSize match {
      case Some(value) =>
        log.info("Metadata is supported with chunk size {}.", value)
      case None =>
        log.info("No support for metadata.")
        eventActor ! RadioMetadataEvent(MetadataNotSupported)
    }

    val source = createStreamSource(streamResponse)
    val killSwitch = KillSwitches.shared("stopRadioStream")
    val mapBufferData = Flow[ByteString].map { data => BufferDataResult(data) }
    val sinkMeta = Sink.foreach[ByteString] { meta =>
      val data = CurrentMetadata(meta.utf8String)
      eventActor ! RadioMetadataEvent(data)
    }

    val graph = RunnableGraph.fromGraph(GraphDSL.createGraph(createStreamSink()) {
      implicit builder =>
        sinkAudio =>
          import GraphDSL.Implicits._

          val ks = builder.add(killSwitch.flow[ByteString])
          val extractionStage = builder.add(MetadataExtractionStage(optChunkSize))

          source ~> ks ~> extractionStage.in
          extractionStage.out0 ~> mapBufferData ~> sinkAudio
          extractionStage.out1 ~> sinkMeta
          ClosedShape
    }).withAttributes(Attributes.inputBuffer(initial = 1, max = 1))
    graph.run()

    killSwitch
  }

  /**
    * Extracts the header with the audio chunk size from the given response. If
    * this header is present and valid, metadata is supported.
    *
    * @param streamResponse the response for the stream request
    * @return the optional audio chunk size
    */
  private def extractChunkSizeHeader(streamResponse: HttpResponse): Option[Int] =
    streamResponse.headers.find(_.name() == AudioChunkSizeHeader).flatMap { header =>
      Try {
        header.value().toInt
      }.recoverWith {
        case e =>
          log.error(e, s"Invalid $AudioChunkSizeHeader header: '${header.value()}'.")
          Failure(e)
      }.toOption
    }

  /**
    * Creates the ''Source'' for the managed stream. It is obtained from the
    * response entity applying some buffering.
    *
    * @param streamResponse the response for the stream request
    * @return the ''Source'' of the managed stream
    */
  private def createStreamSource(streamResponse: HttpResponse): Source[ByteString, Any] =
    streamResponse.entity.dataBytes
      .buffer(config.inMemoryBufferSize / config.bufferChunkSize, OverflowStrategy.backpressure)

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
