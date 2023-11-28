/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.oneforall

import de.oliver_heger.linedj.platform.app.{BaseApplicationManager, ClientApplication}
import net.sf.jguiraffe.gui.app.Application
import net.sf.jguiraffe.gui.builder.window.Window
import org.osgi.service.component.ComponentContext

/**
  * A specialized shutdown manager which triggers the shutdown of the platform
  * when one of the applications available is shutdown.
  *
  * This is a pretty simple shutdown handler. It is sufficient for deployments
  * that contain only a single application or in which the installed
  * applications are closely related to each other.
  *
  * The manager tracks exit events or window closing events from applications
  * installed. When such an event is received from an application the shutdown
  * of the platform is initiated.
  */
class OneForAllShutdownAppManager extends BaseApplicationManager:
  /**
    * Activates this component. Delegates to the activation method of the
    * base trait.
    *
    * @param compCtx the component context
    */
  protected def activate(compCtx: ComponentContext): Unit =
    setUp()

  /**
    * Deactivates this component. Delegates to the deactivation method of the
    * base trait.
    *
    * @param compCtx the component context
    */
  protected def deactivate(compCtx: ComponentContext): Unit =
    tearDown()

  /**
    * @inheritdoc This implementation triggers a shutdown.
    */
  override protected def onApplicationShutdown(app: ClientApplication): Unit =
    triggerShutdown()

  /**
    * @inheritdoc This implementation triggers a shutdown.
    */
  override protected def onWindowClosing(window: Window): Unit =
    triggerShutdown()
