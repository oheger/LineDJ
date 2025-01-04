/*
 * Copyright 2015-2025 The Developers Team.
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
import de.oliver_heger.linedj.utils.ActorManagement
import org.apache.pekko.actor.{ActorRef, Props}
import org.osgi.service.component.ComponentContext

/**
  * A trait supporting a [[PlatformComponent]] with actor management.
  *
  * A platform component that creates actors during its life time should make
  * sure that these actors are stopped when itself is stopped. This trait
  * helps to achieve this by making use of the functionality provided by
  * [[ActorManagement]]. It can be mixed in and provides an implementation of
  * ''deactivate()'' that stops all actors which have been registered before by
  * calling the ''registerActor()'' method.
  */
trait ActorManagementComponent extends ActorManagement with PlatformComponent:
  /**
    * Creates a new actor based on the specified parameters (using the actor
    * factory of the ''ClientApplicationContext'') and registers it. This is a
    * convenience method allowing the creation and registration of an actor in
    * a single step.
    *
    * @param props creation properties for the actor
    * @param name  the actor's name
    * @return the newly created actor
    */
  def createAndRegisterActor(props: Props, name: String): ActorRef =
    registerActor(name, clientApplicationContext.actorFactory.createActor(props, name))

  /**
    * @inheritdoc This implementation stops all actors that have been
    *             registered.
    */
  abstract override def deactivate(componentContext: ComponentContext): Unit =
    stopActors()
    super.deactivate(componentContext)
