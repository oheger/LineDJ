/*
 * Copyright 2015-2023 The Developers Team.
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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''DurationTransformer''.
  */
class DurationTransformerSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /**
    * Checks the result of a transformation.
    *
    * @param duration  the duration to convert
    * @param expResult the expected result
    */
  private def checkTransform(duration: Any, expResult: String): Unit = {
    val context = mock[TransformerContext]
    val transformer = new DurationTransformer

    transformer.transform(duration, context) should be(expResult)
    verifyNoInteractions(context)
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

  it should "produce a string for a long duration" in {
    val duration = (17 * 24 * 60 * 60 + 3 * 60 * 60 + 48 * 60 + 23) * 1000

    val fmt = DurationTransformer.formatLongDuration(duration, "d", "h", "m", "s")
    fmt should be("17d 3h 48m 23s")
  }

  it should "skip 0 values in the string for a long duration" in {
    val duration = (12 * 60 * 60 + 10) * 1000

    val fmt = DurationTransformer.formatLongDuration(duration, "d", "h", "m", "s")
    fmt should be("12h 10s")
  }

  it should "produce an empty string for a 0 long duration" in {
    val fmt = DurationTransformer.formatLongDuration(0, "don't", "care", "about", "units")

    fmt should be("")
  }
}
