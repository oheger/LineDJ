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

package de.oliver_heger.linedj.player.engine.radio

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[CurrentMetadata]].
  */
class CurrentMetadataSpec extends AnyFlatSpec with Matchers:
  "CurrentMetadata" should "return the full data as title if no StreamTitle is found" in:
    val data = "This is metadata without a proper title field."
    val metadata = CurrentMetadata(data)

    metadata.title should be(data)

  it should "return the StreamTitle field as text if available" in:
    val title = "Little River Band - Lonesome Loser"
    val data = s"foo='bar';StreamTitle='$title';baz='some more data';"
    val metadata = CurrentMetadata(data)

    metadata.title should be(title)

  it should "handle a single quote in the StreamTitle field" in:
    val title = "I'd do anything for love"
    val data = s"foo='bar';StreamTitle='$title';baz='some more data';"
    val metadata = CurrentMetadata(data)

    metadata.title should be(title)
