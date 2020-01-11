/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.config

import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.enablers.ElementEnabler
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''EnablerConfigStateHandler''.
  */
class EnablerConfigStateHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
  "An EnablerConfigStateHandler" should "enable its ElementEnabler" in {
    val builderData = mock[ComponentBuilderData]
    val enabler = mock[ElementEnabler]
    val handler = new EnablerConfigStateHandler(enabler, builderData)

    handler.updateState(configAvailable = true)
    verify(enabler).setEnabledState(builderData, true)
  }

  it should "disable its ElementEnabler" in {
    val builderData = mock[ComponentBuilderData]
    val enabler = mock[ElementEnabler]
    val handler = new EnablerConfigStateHandler(enabler, builderData)

    handler.updateState(configAvailable = false)
    verify(enabler).setEnabledState(builderData, false)
  }
}
