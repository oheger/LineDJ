/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.app

import akka.actor.ActorSystem

/**
  * A trait which abstracts the creation of the main application.
  *
  * This trait is used by the bundle activator [[BrowserActivator]] to create
  * an application instance. Having a trait for this purpose also makes
  * testing easier.
  */
private trait ApplicationFactory {
  /**
    * Creates a ''BrowserApp'' instance with the given ''ActorSystem''.
    * @param optActorSystem an optional actor system to be passed to the
    *                       application
    * @return the newly created application
    */
  def createApplication(optActorSystem: Option[ActorSystem]): BrowserApp
}
