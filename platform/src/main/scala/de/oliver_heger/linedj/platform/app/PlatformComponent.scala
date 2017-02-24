/*
 * Copyright 2015-2017 The Developers Team.
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

import org.osgi.service.component.ComponentContext

/**
  * A trait defining some common methods for components running on the LineDJ
  * platform.
  *
  * LineDJ service components are typically implemented as OSGi declarative
  * services components. This trait defines typical life-cycle hooks of such a
  * component. In addition, it contains logic to initialize and query the
  * central [[ClientApplicationContext]] which allows access to core platform
  * services.
  *
  * In addition to defining some common structure, one purpose of this trait
  * is to enable mixin functionality for platform components. The idea is to
  * add special actions on platform activation and deactivation which can be
  * included easily into a concrete implementation, such as specialized cleanup
  * logic.
  */
trait PlatformComponent {
  /**
    * Initializes the reference to the ''ClientApplicationContext''. This
    * method is called by the SCR.
    *
    * @param context the ''ClientApplicationContext''
    */
  def initClientContext(context: ClientApplicationContext): Unit

  /**
    * Returns the ''ClientApplicationContext'' used by this component. This
    * object is available after the initialization of this component.
    *
    * @return the ''ClientApplicationContext''
    */
  def clientApplicationContext: ClientApplicationContext

  /**
    * Activates this component. This method is called by the SCR. It can be
    * overridden in concrete sub classes to implement initialization logic.
    * This base implementation is empty.
    *
    * @param compContext the component context
    */
  def activate(compContext: ComponentContext): Unit = {}

  /**
    * Deactivates this component. This method is called by the SCR when this
    * component is shutdown. It can be overriden in concrete sub classes to
    * implement cleanup logic. This base implementation is empty.
    *
    * @param componentContext the component context
    */
  def deactivate(componentContext: ComponentContext): Unit = {}
}
