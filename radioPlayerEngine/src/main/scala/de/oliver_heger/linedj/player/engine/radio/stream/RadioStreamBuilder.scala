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

import de.oliver_heger.linedj.player.engine.PlayerConfig
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

object RadioStreamBuilder:
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

  /** The logger. */
  private val log = LogManager.getLogger(classOf[RadioStreamBuilder])

  /** A counter to generate unique names for kill switches. */
  private val counter = new AtomicInteger

  /**
    * A data class representing the result of [[RadioStreamBuilder]]. The main
    * result is a graph that can be run to process the radio stream and obtain
    * the materialized values from the sinks. The result also contain a
    * [[KillSwitch]] that can be used to cancel the stream.
    *
    * @param resolvedUri       the resolved URI from which the stream is loaded
    * @param graph             the graph representing the radio stream
    * @param killSwitch        a kill switch to cancel the stream
    * @param metadataSupported flag whether the stream supports metadata
    * @tparam AUD  the materialized type of the audio data sink
    * @tparam META the materialized type of the metadata sink
    */
  case class BuilderResult[AUD, META](resolvedUri: String,
                                      graph: RunnableGraph[(AUD, META)],
                                      killSwitch: KillSwitch,
                                      metadataSupported: Boolean)

  /**
    * A data class describing the result of an operation to resolve a radio
    * stream.
    *
    * @param resolvedUri  the URI of the resolved radio stream
    * @param dataSource   the source emitting the stream data
    * @param optChunkSize the chunk size of audio data if metadata is
    *                     supported; ''None'' otherwise
    */
  private case class ResolvedRadioStream(resolvedUri: String,
                                         dataSource: Source[ByteString, Any],
                                         optChunkSize: Option[Int])

  /**
    * Returns a new instance of [[RadioStreamBuilder]]. This instance can be
    * shared and used to process an arbitrary number of radio streams.
    *
    * @param actorSystem the current actor system
    * @return the new builder instance
    */
  def apply()(implicit actorSystem: ActorSystem): RadioStreamBuilder =
    val loader = new HttpStreamLoader()
    val m3uReader = new M3uReader(loader)
    new RadioStreamBuilder(loader, m3uReader)

  /**
    * Constructs a [[RunnableGraph]] from the given source of a radio stream
    * that can be used to process audio data and metadata. If the radio stream
    * supports metadata, it is extracted and routed to the given sink for
    * metadata while audio data goes to the audio data sink. Otherwise, all
    * data flows to the audio data sink.
    *
    * @param dataSource the source of the radio stream
    * @param config     the player configuration
    * @param sinkAudio  the sink for processing audio data
    * @param sinkMeta   the sink for processing metadata
    * @tparam AUD  the materialized type of the audio data sink
    * @tparam META the materialized type of the metadata sink
    * @return a tuple with the graph and a kill switch to cancel the stream
    */
  def createGraphForSource[AUD, META](dataSource: Source[ByteString, Any],
                                      config: PlayerConfig,
                                      sinkAudio: Sink[ByteString, AUD],
                                      sinkMeta: Sink[ByteString, META],
                                      optChunkSize: Option[Int]):
  (RunnableGraph[(AUD, META)], KillSwitch) =
    val source = createStreamSource(dataSource, config)
    val killSwitch = KillSwitches.shared("stopRadioStream" + counter.incrementAndGet())

    val graph = RunnableGraph.fromGraph(GraphDSL.createGraph(sinkAudio, sinkMeta)((_, _)) {
      implicit builder =>
        (sink1, sink2) =>
          import GraphDSL.Implicits._

          val ks = builder.add(killSwitch.flow[ByteString])
          val extractionStage = builder.add(MetadataExtractionStage(optChunkSize))

          source ~> ks ~> extractionStage.in
          extractionStage.out0 ~> sink1
          extractionStage.out1 ~> sink2
          ClosedShape
    }).withAttributes(Attributes.inputBuffer(initial = 1, max = 1))

    (graph, killSwitch)

  /**
    * Creates the ''Source'' for the radio stream. It is obtained from the
    * response entity applying some buffering.
    *
    * @param responseSource the source from the response entity
    * @param config         the player config
    * @return the ''Source'' of the radio stream
    */
  private def createStreamSource(responseSource: Source[ByteString, Any],
                                 config: PlayerConfig): Source[ByteString, Any] =
    responseSource.buffer(config.inMemoryBufferSize / config.bufferChunkSize, OverflowStrategy.backpressure)

  /**
    * Creates a [[RunnableGraph]] to process a radio stream.
    *
    * @param config         the player configuration
    * @param sinkAudio      the sink for processing audio data
    * @param sinkMeta       the sink for processing metadata
    * @param resolvedStream the data object for the resolved radio stream
    * @tparam AUD  the materialized type of the audio data sink
    * @tparam META the materialized type of the metadata sink
    * @return a ''Future'' with the graph and a kill switch to cancel the
    *         stream
    */
  private def createGraph[AUD, META](config: PlayerConfig,
                                     sinkAudio: Sink[ByteString, AUD],
                                     sinkMeta: Sink[ByteString, META],
                                     resolvedStream: ResolvedRadioStream):
  Future[(RunnableGraph[(AUD, META)], KillSwitch)] =
    Future.successful(createGraphForSource(resolvedStream.dataSource,
      config,
      sinkAudio,
      sinkMeta,
      resolvedStream.optChunkSize))

/**
  * A class to create a runnable graph that can be used to process a radio
  * stream.
  *
  * This class performs the following steps:
  *  - It resolves the URI of the radio stream if necessary (if it points to an
  *    m3u file).
  *  - It creates a data source to download the stream data.
  *  - It constructs a graph with sinks to process audio data and metadata. (If
  *    the stream does not support metadata, the metadata sink does not receive
  *    any data.)
  *    By running the graph produced by this builder, the processing of audio data
  *    and metadata can be done.
  *
  * @param streamLoader the object to load HTTP streams
  * @param m3uReader    the object to resolve m3u files
  */
class RadioStreamBuilder private(streamLoader: HttpStreamLoader,
                                 m3uReader: M3uReader):

  import RadioStreamBuilder._

  /**
    * Constructs a [[BuilderResult]] for a radio stream from the given URI
    * using the provided sinks for audio data and metadata. The URI can point
    * to the actual data stream or to an m3u file; in the latter case, it is
    * resolved accordingly.
    *
    * @param config    the audio player configuration
    * @param streamUri the URI of the radio stream
    * @param sinkAudio the ''Sink'' for audio data
    * @param sinkMeta  the ''Sink'' for metadata
    * @param ec        the execution context
    * @param mat       the object to materialize streams
    * @tparam AUD  the materialized type of the audio data sink
    * @tparam META the materialized type of the metadata sink
    * @return a ''Future'' with the result produced by this builder
    */
  def buildRadioStream[AUD, META](config: PlayerConfig,
                                  streamUri: String,
                                  sinkAudio: Sink[ByteString, AUD],
                                  sinkMeta: Sink[ByteString, META])
                                 (implicit ec: ExecutionContext, mat: Materializer):
  Future[BuilderResult[AUD, META]] =
    for
      resolvedStream <- resolveRadioStream(streamUri)
      (graph, kill) <- createGraph(config, sinkAudio, sinkMeta, resolvedStream)
    yield BuilderResult(resolvedStream.resolvedUri, graph, kill, resolvedStream.optChunkSize.isDefined)

  /**
    * Resolves the radio stream defined by the given URI and returns a data
    * object with the required information.
    *
    * @param streamUri the URI of the audio stream
    * @param ec        the execution context
    * @param mat       the object to materialize streams
    * @return a ''Future'' with information about the resolved stream
    */
  private def resolveRadioStream(streamUri: String)
                                (implicit ec: ExecutionContext, mat: Materializer): Future[ResolvedRadioStream] =
    for
      resolvedRef <- m3uReader.resolveAudioStream(streamUri)
      response <- streamLoader.sendRequest(createRadioStreamRequest(resolvedRef))
    yield ResolvedRadioStream(resolvedRef, response.entity.dataBytes, extractChunkSizeHeader(response))

  /**
    * Returns the request to query the radio stream represented by the passed
    * in URI.
    *
    * @param uri the URI to the radio stream
    * @return the HTTP request to load this stream
    */
  private def createRadioStreamRequest(uri: String): HttpRequest =
    HttpRequest(uri = uri, headers = RadioStreamRequestHeaders)

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
          log.error("Invalid $AudioChunkSizeHeader header: '{}'.", header.value(), e)
          Failure(e)
      }.toOption
    }
