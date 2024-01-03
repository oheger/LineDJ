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

package de.oliver_heger.linedj.platform.app.support

import de.oliver_heger.linedj.platform.app.PlatformComponent
import org.osgi.service.component.ComponentContext

/**
  * A test helper trait for checking whether a ''PlatformComponent''-derived
  * trait invokes its super methods for activation and deactivation.
  *
  * This trait can be mixed into a test implementation. It provides counters
  * for the life-cycle methods; so it can be checked whether they have been
  * called from a derived trait.
  */
trait SuperInvocationCheck extends PlatformComponent:
  /** Counter for ''activate()'' calls. */
  var activateCount = 0

  /** Counter for ''deactivate()'' calls. */
  var deactivateCount = 0

  /**
    * @inheritdoc Records this invocation.
    */
  abstract override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)
    activateCount += 1

  /**
    * @inheritdoc Records this invocation.
    */
  abstract override def deactivate(componentContext: ComponentContext): Unit =
    deactivateCount += 1
    super.deactivate(componentContext)
