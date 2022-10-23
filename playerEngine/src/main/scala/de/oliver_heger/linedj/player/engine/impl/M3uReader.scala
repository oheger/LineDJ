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
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Framing, Keep, Sink}
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.impl.M3uReader.extractUriSink

import scala.concurrent.{ExecutionContext, Future}

object M3uReader {
  /** The default line separator. */
  private val LineSeparator = ByteString("\n")

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
}

/**
  * An helper class that reads the content of a radio stream URL that ends on
  * ''.m3u''.
  *
  * Some radio streams have an ''.m3u'' extension; they do not contain MP3 data
  * directly, but a reference to the actual audio stream. This class is
  * responsible for evaluating such a URL and obtaining the correct URL to the
  * audio stream. This is done by simply reading the content of the referenced
  * URL and returning the first line that is not empty or starts with a comment
  * character.
  */
private class M3uReader {
  /**
    * Tries to resolve the given reference that points to a ''m3u'' file.
    * Returns a ''Future'' with a reference that points to the actual audio
    * stream.
    *
    * @param config    the ''PlayerConfig''
    * @param reference the reference to resolve
    * @param ec        the execution context
    * @param mat       the object to materialize streams
    * @return a ''Future'' with a reference to the audio stream
    */
  def resolveAudioStream(config: PlayerConfig, reference: StreamReference)
                        (implicit ec: ExecutionContext, mat: Materializer): Future[StreamReference] = {
    for {
      source <- reference.createSource()
      blockingSource = config.applyBlockingDispatcher(source)
      streamUri <- blockingSource.runWith(extractUriSink())
    } yield StreamReference(streamUri)
  }
}
