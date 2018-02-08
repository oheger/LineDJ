/*
 * Copyright 2015-2018 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package de.oliver_heger.linedj.pleditor.spi

import de.oliver_heger.linedj.platform.audio.model.SongData
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''LocalizedPlaylistReorderer''.
  */
class LocalizedPlaylistReordererSpec extends FlatSpec with Matchers {
  "A LocalizedPlaylistReorderer" should "obtain the correct name" in {
    val order = new LocalizedPlaylistReorderer {
      override val resourceBundleBaseName: String = "LocalizedPlaylistReordererSpec"

      override def reorder(songs: Seq[SongData]): Seq[SongData] = {
        throw new AssertionError("Unexpected invocation!")
      }
    }

    order.name should be("LocalizedPlaylistReordererSpec")
  }
}
