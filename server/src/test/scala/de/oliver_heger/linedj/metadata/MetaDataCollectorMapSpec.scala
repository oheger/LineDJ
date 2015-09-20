/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.linedj.metadata

import java.nio.file.Paths

import de.oliver_heger.linedj.io.FileData
import org.scalatest.{FlatSpec, Matchers}

object MetaDataCollectorMapSpec {
  /** A test path. */
  private val TestPath = Paths get "MetaDataCollectorMapSpec.tst"

  /** A test media file. */
  private val TestFile = FileData(TestPath, 20150912212258L)
}

/**
 * Test class for ''MetaDataCollectorMap''.
 */
class MetaDataCollectorMapSpec extends FlatSpec with Matchers {

  import MetaDataCollectorMapSpec._

  "A MetaDataCollectorMap" should "create a correct collector" in {
    val map = new MetaDataCollectorMap
    val collector = map getOrCreateCollector TestFile
    collector.file should be(TestFile)
  }
}
