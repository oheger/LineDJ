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

package de.oliver_heger.linedj.client.mediaifc.config

import net.sf.jguiraffe.di.{BeanContext, ClassLoaderProvider}
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.{ClassPathLocator, Locator}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''OpenMediaIfcConfigCommand''.
  */
class OpenMediaIfcConfigCommandSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a test config data object.
    *
    * @return the test config data
    */
  private def createConfigData(): MediaIfcConfigData =
  new MediaIfcConfigData {
    override def configScriptLocator: Locator = ClassPathLocator.getInstance("someScript.jelly")

    override def configClassLoader: ClassLoader = getClass.getClassLoader
  }

  "An OpenMediaIfcConfigCommand" should "pass the locator to the super class" in {
    val data = createConfigData()
    val command = new OpenMediaIfcConfigCommand(data)

    command.getLocator should be(data.configScriptLocator)
  }

  it should "initialize the class loader provider" in {
    val builderData = mock[ApplicationBuilderData]
    val clp = mock[ClassLoaderProvider]
    val beanCtx = mock[BeanContext]
    when(beanCtx.getClassLoaderProvider).thenReturn(clp)
    when(builderData.getParentContext).thenReturn(beanCtx)
    val data = createConfigData()
    val command = new OpenMediaIfcConfigCommand(data)

    command.prepareBuilderData(builderData)
    verify(clp).registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName,
      data.configClassLoader)
  }

  it should "restore the class loader provider after execution" in {
    val builderData = mock[ApplicationBuilderData]
    val clp = mock[ClassLoaderProvider]
    val beanCtx = mock[BeanContext]
    when(beanCtx.getClassLoaderProvider).thenReturn(clp)
    when(builderData.getParentContext).thenReturn(beanCtx)
    val data = createConfigData()
    val command = new OpenMediaIfcConfigCommand(data)
    command.prepareBuilderData(builderData)

    command.onFinally()
    verify(clp).registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName, null)
  }
}
