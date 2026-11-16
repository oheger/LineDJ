/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.player.server.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.*

/**
  * Test class for [[RadioModel]].
  */
class RadioModelSpec extends AnyFlatSpec with Matchers with RadioModel.RadioJsonSupport:

  import RadioModel.*

  "RadioModel" should "define the expected message type constants" in:
    MessageTypeSourceChanged should be("SourceChanged")
    MessageTypeSourceSelected should be("SourceSelected")
    MessageTypeReplacementStart should be("ReplacementStart")
    MessageTypeReplacementEnd should be("ReplacementEnd")
    MessageTypePlaybackStopped should be("PlaybackStopped")
    MessageTypeTitleInfo should be("TitleInfo")

  it should "convert a PlaybackStatus to JSON and back" in:
    checkRoundTrip(PlaybackStatus(enabled = true))

  it should "convert a RadioSource to JSON and back" in:
    checkRoundTrip(RadioSource("id1", "Test source", 42, favoriteIndex = 1, favoriteName = "Favorite"))

  it should "use default values for optional radio source properties" in:
    val source = RadioSource("id2", "Another source", 7)

    source.favoriteIndex should be(-1)
    source.favoriteName should be("")

  it should "convert a RadioSources object to JSON and back" in:
    checkRoundTrip(RadioSources(List(
      RadioSource("id1", "Source 1", 1),
      RadioSource("id2", "Source 2", 2)
    )))

  it should "convert a RadioSourceStatus to JSON and back" in:
    checkRoundTrip(RadioSourceStatus(Some("current"), Some("replacement"), Some("Title")))

  it should "convert a RadioSourceStatus with empty options to JSON and back" in:
    checkRoundTrip(RadioSourceStatus(None, None, None))

  it should "convert a RadioMessage to JSON and back" in:
    checkRoundTrip(RadioMessage(MessageTypeTitleInfo, "Test title"))

  /**
    * Serializes the given object to JSON and parses it again. The result must
    * be equal to the original object.
    *
    * @param obj the object to be checked
    * @tparam T the type of the object
    */
  private def checkRoundTrip[T](obj: T)(using format: RootJsonFormat[T]): Unit =
    val json = obj.toJson
    json.convertTo[T] should be(obj)
