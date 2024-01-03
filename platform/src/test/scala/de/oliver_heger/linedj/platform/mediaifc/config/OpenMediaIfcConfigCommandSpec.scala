/*
 * Copyright 2015-2024 The Developers Team.
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

import net.sf.jguiraffe.di.{BeanContext, ClassLoaderProvider}
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.{ClassPathLocator, Locator}
import net.sf.jguiraffe.resources.ResourceManager
import net.sf.jguiraffe.transform.TransformerContext
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''OpenMediaIfcConfigCommand''.
  */
class OpenMediaIfcConfigCommandSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  /**
    * Creates a test config data object.
    *
    * @return the test config data
    */
  private def createConfigData(): MediaIfcConfigData =
  new MediaIfcConfigData:
    override def configScriptLocator: Locator = ClassPathLocator.getInstance("someScript.jelly")

    override def configClassLoader: ClassLoader = getClass.getClassLoader

  /**
    * Creates a mock ''BuilderData'' object.
    *
    * @return the mock builder data
    */
  private def createBuilderData(): ApplicationBuilderData =
    val data = mock[ApplicationBuilderData]
    val tctx = mock[TransformerContext]
    val resMan = mock[ResourceManager]
    when(data.getTransformerContext).thenReturn(tctx)
    when(tctx.getResourceManager).thenReturn(resMan)
    data

  "An OpenMediaIfcConfigCommand" should "pass the locator to the super class" in:
    val data = createConfigData()
    val command = new OpenMediaIfcConfigCommand(data)

    command.getLocator should be(data.configScriptLocator)

  it should "initialize the class loader provider" in:
    val builderData = createBuilderData()
    val clp = mock[ClassLoaderProvider]
    val beanCtx = mock[BeanContext]
    when(beanCtx.getClassLoaderProvider).thenReturn(clp)
    when(builderData.getParentContext).thenReturn(beanCtx)
    val data = createConfigData()
    val command = new OpenMediaIfcConfigCommand(data)

    command.prepareBuilderData(builderData)
    verify(clp).registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName,
      data.configClassLoader)

  it should "restore the class loader provider after execution" in:
    val builderData = createBuilderData()
    val clp = mock[ClassLoaderProvider]
    val beanCtx = mock[BeanContext]
    when(beanCtx.getClassLoaderProvider).thenReturn(clp)
    when(builderData.getParentContext).thenReturn(beanCtx)
    val data = createConfigData()
    val command = new OpenMediaIfcConfigCommand(data)
    command.prepareBuilderData(builderData)

    command.onFinally()
    verify(clp).registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName, null)
