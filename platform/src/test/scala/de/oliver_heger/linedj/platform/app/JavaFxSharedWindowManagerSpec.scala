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

package de.oliver_heger.linedj.platform.app

import javafx.application.Platform

import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{StageFactory, StyleSheetProvider}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''JavaFxSharedWindowManager''.
  */
class JavaFxSharedWindowManagerSpec extends FlatSpec with Matchers with MockitoSugar {
  "A JavaFxSharedWindowManager" should "pass the style sheet provider to the base class" in {
    val provider = mock[StyleSheetProvider]

    val manager = new JavaFxSharedWindowManager(provider)
    manager.styleSheetProvider should be(provider)
  }

  it should "obtain the stage factory from the bean context" in {
    val beanContext = mock[BeanContext]
    val factory = mock[StageFactory]
    doReturn(factory).when(beanContext).getBean(JavaFxSharedWindowManager.BeanStageFactory)

    val manager = new JavaFxSharedWindowManager(mock[StyleSheetProvider])
    manager setBeanContext beanContext
    manager.stageFactory should be(factory)
  }

  it should "cache the stage factory after it has been retrieved" in {
    val beanContext = mock[BeanContext]
    val factory = mock[StageFactory]
    doReturn(factory).when(beanContext).getBean(JavaFxSharedWindowManager.BeanStageFactory)

    val manager = new JavaFxSharedWindowManager(mock[StyleSheetProvider])
    manager setBeanContext beanContext
    val currentFactory = manager.stageFactory
    manager.stageFactory should be(currentFactory)
    verify(beanContext, Mockito.atMost(1)).getBean(JavaFxSharedWindowManager.BeanStageFactory)
  }

  it should "disable the Platform implicit exit flag" in {
    val manager = new JavaFxSharedWindowManager(mock[StyleSheetProvider])
    manager setBeanContext mock[BeanContext]

    Platform.isImplicitExit shouldBe false
  }
}
