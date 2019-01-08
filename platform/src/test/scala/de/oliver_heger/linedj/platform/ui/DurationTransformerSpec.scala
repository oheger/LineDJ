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

package de.oliver_heger.linedj.platform.ui

import net.sf.jguiraffe.transform.TransformerContext
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''DurationTransformer''.
 */
class DurationTransformerSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
   * Checks the result of a transformation.
   * @param duration the duration to convert
   * @param expResult the expected result
   */
  private def checkTransform(duration: Any, expResult: String): Unit = {
    val context = mock[TransformerContext]
    val transformer = new DurationTransformer

    transformer.transform(duration, context) should be(expResult)
    verifyZeroInteractions(context)
  }

  "A DurationTransformer" should "support a simple conversion" in {
    checkTransform((5 * 60 + 25) * 1000L, "5:25")
  }

  it should "add leading zeros if necessary" in {
    checkTransform((1 * 60 * 60 + 2 * 60 + 8) * 1000L, "1:02:08")
  }

  it should "always display minutes, even for small durations" in {
    checkTransform(2000, "0:02")
  }

  it should "return a special string for an undefined duration" in {
    checkTransform(-1, "?")
  }

  it should "return a special string for invalid input" in {
    checkTransform("not a valid duration", "?")
  }

  it should "round a duration to seconds" in {
    checkTransform(60501, "1:01")
  }
}
