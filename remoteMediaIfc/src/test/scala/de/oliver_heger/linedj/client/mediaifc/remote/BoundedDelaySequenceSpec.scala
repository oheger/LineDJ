/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.client.mediaifc.remote

import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''BoundedDelaySequence''.
 */
class BoundedDelaySequenceSpec extends FlatSpec with Matchers {
  "A BoundedDelaySequence" should "return the current sequence value" in {
    val seq = new BoundedDelaySequence(100, 1, 0)
    seq.nextDelay._1 should be(0)
  }

  it should "increment the current value" in {
    val seq = new BoundedDelaySequence(100, 2, 1)
    val (v1, seq2) = seq.nextDelay
    v1 should be(1)
    seq2.nextDelay._1 should be(3)
  }

  it should "return the same object when the maximum is reached" in {
    val seq = new BoundedDelaySequence(100, 1, 100)
    val (v, seq2) = seq.nextDelay
    v should be(seq.maximum)
    seq2 should be theSameInstanceAs seq
  }

  it should "limit the sequence to the maximum" in {
    val seq = new BoundedDelaySequence(10, 4, 8)
    val seq2 = seq.nextDelay._2
    seq2.nextDelay._1 should be(seq.maximum)
  }

  it should "correct an initial value which is too high" in {
    val seq = new BoundedDelaySequence(10, 4, 12)
    val (v, seq2) = seq.nextDelay
    v should be(seq.maximum)
    seq should be theSameInstanceAs seq2
  }
}
