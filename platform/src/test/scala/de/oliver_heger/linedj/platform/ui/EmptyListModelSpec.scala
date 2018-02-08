/*
 * Copyright 2015-2018 The Developers Team.
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

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''EmptyListModel''.
  */
class EmptyListModelSpec extends FlatSpec with Matchers {
  /**
    * Creates a model which can be used for tests.
    *
    * @return the test model
    */
  private def createModel(): EmptyListModel = new EmptyListModel {
    override val getType = classOf[String]
  }

  "An EmptyListModel" should "have size 0" in {
    val model = createModel()
    model.size() should be(0)
  }

  it should "return null display objects" in {
    val model = createModel()
    model getDisplayObject 28 should be(null)
  }

  it should "return null value objects" in {
    val model = createModel()
    model getValueObject 42 should be(null)
  }
}
