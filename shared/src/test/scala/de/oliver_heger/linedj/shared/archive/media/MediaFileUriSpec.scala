/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.shared.archive.media

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[MediaFileUri]].
  */
class MediaFileUriSpec extends AnyFlatSpec with Matchers:
  "A MediaFileUri" should "return the name of the URI" in:
    val uri = MediaFileUri("some%20medium/some%20artist/some%20album/01%20A%20nice%2Fcool%20song.mp3")

    val name = uri.name

    name should be("01 A nice/cool song")
