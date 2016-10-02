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

package de.oliver_heger.linedj.platform.mediaifc.ext

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''DefaultConsumerIDFactory''.
  */
class DefaultConsumerIDFactorySpec extends FlatSpec with Matchers {
  "A DefaultConsumerIDFactory" should "produce different IDs for different objects" in {
    val factory = new DefaultConsumerIDFactory

    val id1 = factory createID new Object
    val id2 = factory createID this
    id1 should not be id2
  }

  it should "produce unique IDs for the same object reference" in {
    val factory = new DefaultConsumerIDFactory

    val ids = (1 to 1000).map(_ => factory.createID(this)).toSet
    ids should have size 1000
  }

  it should "produce IDs not easy to guess" in {
    val factory = new DefaultConsumerIDFactory

    val ids = (1 to 1000).map(_ => factory.createID(this))
    val (deltas, _) = ids.foldLeft((List.empty[Int], 0)) { (s, id) =>
      val cid = id.asInstanceOf[ConsumerIDImpl]
      (cid.identity - s._2 :: s._1, cid.identity)
    }
    deltas.toSet.size should be > 50
  }
}
