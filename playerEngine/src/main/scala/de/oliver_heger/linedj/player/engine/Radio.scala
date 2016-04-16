/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine

import java.net.URL
import java.nio.charset.StandardCharsets

import de.oliver_heger.linedj.player.engine.impl.Mp3PlaybackContextFactory

import scala.io.Source

/**
  * Demo class for playing radio streams.
  */
object Radio {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("Usage: Radio <streamURL>")
      System.exit(1)
    }

    var streamUri = determineStreamUrl(args.head)
    val url = new URL(streamUri)
    if (!streamUri.endsWith(".mp3")) {
      streamUri = streamUri + ".mp3"
    }

    val factory = new Mp3PlaybackContextFactory
    val context = factory.createPlaybackContext(url.openStream(), streamUri).get
    var len = 0
    context.line.open(context.format)
    context.line.start()
    val buffer = new Array[Byte](4 * context.bufferSize)

    do {
      len = context.stream.read(buffer)
      if (len > 0) {
        println(s"Playing $len bytes.")
        context.line.write(buffer, 0, len)
      }
    } while (len >= 0)
  }

  /**
    * Determines the URL of the actual stream. If the entry point into the
    * stream is a m3u file, it is read to extract the URL for playback.
    *
    * @param url the input url
    * @return the url for playback
    */
  private def determineStreamUrl(url: String): String = {
    if (url endsWith "m3u") {
      val in = new URL(url).openStream()
      try {
        val lines = Source.fromInputStream(in, StandardCharsets.UTF_8.toString).getLines()
        lines.filter(!_.startsWith("#")).toList.head
      } finally {
        in.close()
      }
    } else url
  }
}
