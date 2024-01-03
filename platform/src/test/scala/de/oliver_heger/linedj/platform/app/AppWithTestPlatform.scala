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

package de.oliver_heger.linedj.platform.app

import net.sf.jguiraffe.gui.app.Application
import net.sf.jguiraffe.locators.{ClassPathLocator, Locator}

/**
  * A trait that can be mixed in a test application implementation to enable
  * the JGUIraffe test platform.
  *
  * This trait overrides the locator for the platform beans to refer to the
  * test platform. That way an application can start up and even display its
  * main window without having actual interaction with the UI; only dummy
  * objects are created.
  */
trait AppWithTestPlatform extends Application:
  /**
    * @inheritdoc Returns the locator to the test platform beans definition
    *             file.
    */
  override def getPlatformBeansLocator: Locator =
    ClassPathLocator.getInstance("testplatformbeans.jelly")
