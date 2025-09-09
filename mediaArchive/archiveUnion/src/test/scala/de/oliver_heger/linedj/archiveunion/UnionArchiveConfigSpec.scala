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

package de.oliver_heger.linedj.archiveunion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''MediaArchiveConfig''.
  */
class UnionArchiveConfigSpec extends AnyFlatSpec with Matchers:
  "A UnionArchiveConfig" should "adapt the maximum message size if necessary" in:
    val config = UnionArchiveConfig(metadataUpdateChunkSize = 8, initMetadataMaxMsgSize = 150)

    config.metadataMaxMessageSize should be(152)
