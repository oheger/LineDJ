/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.nio.charset.StandardCharsets

import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.io.Source

/**
  * Test class for ''StreamReference''.
  */
class StreamReferenceSpec extends FlatSpec with BeforeAndAfter with Matchers with FileTestHelper {
  after {
    tearDownTestFile()
  }

  "A StreamReference" should "open the referenced stream" in {
    val path = createDataFile()
    val ref = StreamReference(path.toUri.toString)

    val stream = ref.openStream()
    val source = Source.fromInputStream(stream, StandardCharsets.UTF_8.toString)
    val data = source.mkString
    source.close()

    data should be(FileTestHelper.TestData)
  }
}
