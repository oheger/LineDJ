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

package de.oliver_heger.linedj.archivecommon.download

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''DownloadConfig''.
  */
class DownloadConfigSpec extends AnyFlatSpec with Matchers:
  "A DownloadConfig" should "provide a default instance" in:
    DownloadConfig.DefaultDownloadConfig.downloadChunkSize should be(DownloadConfig.DefaultDownloadChunkSize)
    DownloadConfig.DefaultDownloadConfig.downloadTimeout should be(DownloadConfig.DefaultDownloadActorTimeout)
    DownloadConfig.DefaultDownloadConfig.downloadCheckInterval should be(DownloadConfig.DefaultDownloadCheckInterval)
