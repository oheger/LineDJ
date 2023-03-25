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

import akka.NotUsed
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Framing, Keep, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.io.stream.StreamSizeRestrictionStage
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.radio.stream.M3uReader.{MaxM3uStreamSize, extractUriSink, needToResolveAudioStream, streamRequest}

import scala.concurrent.{ExecutionContext, Future}

object M3uReader {
  /**
    * Constant for the maximum size of m3u streams that are processed by this
    * class. If a stream is longer, processing fails.
    */
  final val MaxM3uStreamSize = 16384

  /** The default line separator. */
  private val LineSeparator = ByteString("\n")

  /** The extension for m3u URIs. */
  private val ExtM3u = ".m3u"

  /**
    * Returns a sink that extracts the first stream URL referenced from the
    * content of an M3u URL.
    *
    * @return the sink to extract the first stream URL
    */
  private def extractUriSink(): Sink[ByteString, Future[String]] =
    splitLines()
      .map(_.utf8String.trim)
      .filterNot(s => s.isEmpty || s.startsWith("#"))
      .toMat(Sink.head[String])(Keep.right)

  /**
    * Returns a flow that splits stream elements at line separators, so that
    * the single lines are extracted.
    *
    * @return the flow that splits stream elements at their lines
    */
  private def splitLines(): Flow[ByteString, ByteString, NotUsed] =
    Framing.delimiter(LineSeparator, 8182, allowTruncation = true)

  /**
    * Returns a flag whether the specified audio stream needs to be resolved
    * first before it can be played. This is the case if the stream does not
    * point to audio data, but to a playlist which references the actual
    * audio stream.
    *
    * @param uri the stream URI in question
    * @return '''true''' if this URI needs to be resolved
    */
  private def needToResolveAudioStream(uri: String): Boolean = uri endsWith ExtM3u

  /**
    * Returns an HTTP request to load the stream referred to by the given URI.
    *
    * @param uri the URI to the stream
    * @return the HTTP request to load this stream
    */
  private def streamRequest(uri: String): HttpRequest = HttpRequest(uri = uri)
}

/**
  * A helper class to resolve the final URL of a radio stream.
  *
  * Some radio streams have an ''.m3u'' extension; they do not contain MP3 data
  * directly, but a reference to the actual audio stream. This class is
  * responsible for evaluating such a URL and obtaining the correct URL to the
  * audio stream. This is done by simply reading the content of the referenced
  * URL and returning the first line that is not empty or starts with a comment
  * character.
  *
  * @param loader the [[HttpStreamLoader]] to use for sending HTTP requests
  */
private class M3uReader(loader: HttpStreamLoader) {
  /**
    * Tries to resolve the given stream URI and returns one that points to the
    * actual audio stream. This function tests whether the passed in URI
    * refers to a file with the ''m3u'' extension. If this is the case, the
    * content of the URL is read and the actual stream URL is extracted.
    * Otherwise, the URI is returned directly.
    *
    * @param config the ''PlayerConfig''
    * @param uri    the reference to resolve
    * @param ec     the execution context
    * @param mat    the object to materialize streams
    * @return a ''Future'' with a reference to the audio stream
    */
  def resolveAudioStream(config: PlayerConfig, uri: String)
                        (implicit ec: ExecutionContext, mat: Materializer): Future[String] =
    if (needToResolveAudioStream(uri)) {
      for {
        response <- loader.sendRequest(streamRequest(uri))
        source = createM3uSource(response)
        streamUri <- source.runWith(extractUriSink())
      } yield streamUri
    } else {
      Future.successful(uri)
    }

  /**
    * Creates a ''Source'' for the stream with m3u data from the given
    * response.
    *
    * @param response the HTTP response for the m3u stream
    * @return the ''Source'' to read the data of the m3u stream
    */
  private def createM3uSource(response: HttpResponse): Source[ByteString, Any] =
    response.entity.dataBytes.via(new StreamSizeRestrictionStage(MaxM3uStreamSize))
}
