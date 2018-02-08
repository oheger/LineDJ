/*
 * Copyright 2015-2018 The Developers Team.
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

import java.nio.charset.StandardCharsets

import akka.actor.{Actor, ActorLogging}
import de.oliver_heger.linedj.player.engine.impl.M3uReaderActor.{AudioStreamResolved,
ResolveAudioStream}

import scala.io.{BufferedSource, Source}

private[impl] object M3uReaderActor {

  /**
    * A message class processed by [[M3uReaderActor]] telling it to process the
    * specified URL and determine the correct audio stream.
    *
    * @param streamRef a reference to the stream with the m3u file
    */
  case class ResolveAudioStream(streamRef: StreamReference)

  /**
    * A message sent by [[M3uReaderActor]] when an audio stream could be
    * resolved. The m3u data was read, and the final audio stream URL was
    * discovered. Both references are part of this message.
    *
    * @param m3uStreamRef   the reference to the m3u stream
    * @param audioStreamRef the resolved audio stream reference
    */
  case class AudioStreamResolved(m3uStreamRef: StreamReference, audioStreamRef: StreamReference)

}

/**
  * An actor class that reads the content of a radio stream URL that ends on
  * ''.m3u''.
  *
  * Some radio streams have an ''.m3u'' extension; they do not contain MP3 data
  * directly, but a reference to the actual audio stream. This actor class is
  * responsible for evaluating such a URL and obtain the correct URL to the
  * audio stream.
  *
  * Reading and processing the m3u URL is a blocking operation. Therefore, the
  * actor should run on a dedicated dispatcher.
  *
  * Exceptions during processing of the URL are not handled and thus are passed
  * to the parent actor's supervision strategy (as IOExceptions). If no valid
  * audio stream URL can be extracted, an ''IOException'' is thrown as well.
  */
class M3uReaderActor extends Actor with ActorLogging {
  override def receive: Receive = {
    case ResolveAudioStream(streamRef) =>
      sender ! AudioStreamResolved(streamRef, processM3uFile(streamRef))
  }

  /**
    * Processes the m3u file specified by the given reference and extracts the
    * referenced audio stream. If this is not possible, an ''IOException'' is
    * thrown.
    *
    * @param streamRef the reference to the m3u file
    * @return a reference to the extracted audio stream
    */
  private def processM3uFile(streamRef: StreamReference): StreamReference = {
    log.info("Processing m3u URI {}.", streamRef.uri)
    val source = Source.fromURL(streamRef.uri, StandardCharsets.UTF_8.toString)
    try {
      StreamReference(extractAudioStreamUri(source))
    } finally source.close()
  }

  /**
    * Extracts the URI of the audio stream from the specified source object.
    * Throws an exception if this is not possible.
    *
    * @param source the source
    * @return the extracted URI of the audio stream
    */
  private def extractAudioStreamUri(source: BufferedSource): String = {
    val iter = source.getLines().filter(l => !l.isEmpty && !l.startsWith("#"))
    if (iter.hasNext) iter.next()
    else throw new java.io.IOException("Cannot extract audio stream URI from m3u file!")
  }
}
