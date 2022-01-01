/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.remote

import net.sf.jguiraffe.locators.ClassPathLocator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''RemoteMediaIfcConfigData''.
  */
class RemoteMediaIfcConfigDataSpec extends AnyFlatSpec with Matchers {
  "A RemoteMediaIfcConfigData" should "return the correct class loader" in {
    val data = new RemoteMediaIfcConfigData

    data.configClassLoader should be(classOf[RemoteMediaIfcConfigData].getClassLoader)
  }

  it should "return a correct locator to the configuration script" in {
    val data = new RemoteMediaIfcConfigData

    val locator = data.configScriptLocator.asInstanceOf[ClassPathLocator]
    locator.getResourceName should be("remoteconfig.jelly")
    locator.getClassLoader should be(data.configClassLoader)
  }
}
