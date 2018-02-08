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

/**
  * A specialized [[PlatformComponent]] trait that implements management of the
  * [[ClientApplicationContext]].
  *
  * Extending from this trait causes a member field for the
  * ''ClientApplicationContext'' to be added.
  */
trait ClientContextSupport extends PlatformComponent {
  /** The client application context. */
  private var clientContextField: ClientApplicationContext = _

  /**
    * Initializes the reference to the ''ClientApplicationContext''. This
    * method is called by the SCR.
    *
    * @param context the ''ClientApplicationContext''
    */
  override def initClientContext(context: ClientApplicationContext): Unit = {
    clientContextField = context
  }

  /**
    * Returns the ''ClientApplicationContext'' used by this component. This
    * object is available after the initialization of this component.
    *
    * @return the ''ClientApplicationContext''
    */
  override def clientApplicationContext: ClientApplicationContext = clientContextField
}
