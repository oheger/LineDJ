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

package de.oliver_heger.linedj.platform.app

import net.sf.jguiraffe.di.{BeanContext, BeanContextClient}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{StageFactory, StyleSheetProvider,
JavaFxWindowManager}

object JavaFxSharedWindowManager {
  /** The name of the bean with the stage factory. */
  val BeanStageFactory = "lineDJ_stageFactory"
}

/**
  * A specialized ''WindowManager'' implementation to be used by LineDJ client
  * applications.
  *
  * On the LineDJ client platform multiple applications are running in a
  * single physical JavaFX application. This has to be taken into account when
  * windows are created. The platform uses a shared ''StageFactory'' which is
  * created and provided by [[ClientManagementApplication]]. It has to be
  * ensured that this factory is used by the ''WindowManager'' used by a visual
  * client application.
  *
  * This class is a special window manager implementation which differs from
  * its base class by obtaining the ''StageFactory'' in a different way: It is
  * fetched from the bean context under a reserved name. This makes it possible
  * to access a factory which has been stored into the context dynamically.
  *
  * @param styleSheetProvider the ''StyleSheetProvider''
  */
class JavaFxSharedWindowManager(styleSheetProvider: StyleSheetProvider) extends
JavaFxWindowManager(styleSheetProvider, null) with BeanContextClient {

  import JavaFxSharedWindowManager._

  /**
    * The stage factory which is obtained from the bean context on first
    * access.
    */
  override lazy val stageFactory = fetchStageFactoryFromBeanContext()

  /** The bean context. */
  private var beanContext: BeanContext = _

  /**
    * Sets the bean context. This method is called by the dependency injection
    * framework.
    * @param context the bean context
    */
  override def setBeanContext(context: BeanContext): Unit = {
    beanContext = context
  }

  /**
    * Obtains the ''StageFactory'' from the ''BeanContext''.
    * @return the bean for the ''StageFactory''
    */
  private def fetchStageFactoryFromBeanContext(): StageFactory =
    beanContext.getBean(BeanStageFactory).asInstanceOf[StageFactory]
}
