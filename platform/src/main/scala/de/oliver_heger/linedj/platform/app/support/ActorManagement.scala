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

package de.oliver_heger.linedj.platform.app.support

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.platform.app.PlatformComponent
import org.osgi.service.component.ComponentContext

/**
  * A trait supporting a [[PlatformComponent]] with actor management.
  *
  * A platform component that creates actors during its life time should make
  * sure that these actors are stopped when itself is stopped. This trait
  * helps to achieve this. It can be mixed in and provides an implementation of
  * ''deactivate()'' that stops all actors which have been registered before by
  * calling the ''registerActor()'' method.
  *
  * So a class that wants to use this functionality just has to call
  * ''registerActor()'' for each actor it wants to have managed. (If an actor
  * is stopped by the class itself or via some other means, it does not have to
  * be registered.) Registered actors are stored in a thread-safe map using
  * their names as keys, so that they can be accessed from every thread.
  * Stopping of actors has to be done in the OSGi management thread when the
  * component is deactivated, but actors are typically created in other
  * threads; so thread-safe access is mandatory.
  *
  * An concrete subclass can rely on the storage of actors and access the
  * managed actor references via the ''getActor()'' method. However, due to the
  * thread-safe access, there might be a certain overhead which might not be
  * necessary if actors are only accessed from a specific thread, e.g. the UI
  * thread.
  */
trait ActorManagement extends PlatformComponent {
  /** A map for storing registered actors. */
  private val managedActors = new ConcurrentHashMap[String, ActorRef]

  /**
    * Registers the specified actor under the given key. The actor can then be
    * queried via ''getActor()'', and it is stopped when this component is
    * deactivated.
    *
    * @param name  the name of the actor
    * @param actor the actor
    * @return the passed in actor
    */
  def registerActor(name: String, actor: ActorRef): ActorRef = {
    managedActors.put(name, actor)
    actor
  }

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
    * Returns the actor reference for the specified name. This actor must have
    * been registered before; otherwise, a ''NoSuchElementException''
    * exception is thrown.
    *
    * @param name the name of the desired actor
    * @return the reference to this actor
    */
  def getActor(name: String): ActorRef = {
    val ref = managedActors get name
    if (ref == null)
      throw new NoSuchElementException(s"No actor registered with name '$name'!")
    ref
  }

  /**
    * @inheritdoc This implementation stops all actors that have been
    *             registered.
    */
  abstract override def deactivate(componentContext: ComponentContext): Unit = {
    stopActors()
    super.deactivate(componentContext)
  }

  /**
    * Stops all managed actors and removes them from the internal map. This
    * method is called by ''deactivate()''. It can also be called by derived
    * classes if they want to reset their actors in the middle of their
    * life cycle.
    */
  protected def stopActors(): Unit = {
    import scala.collection.JavaConverters._
    val actors = managedActors.values().asScala
    actors foreach (a => clientApplicationContext.actorSystem stop a)
    managedActors.clear()
  }
}
