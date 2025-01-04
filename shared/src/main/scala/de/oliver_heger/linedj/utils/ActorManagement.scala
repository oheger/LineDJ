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

package de.oliver_heger.linedj.utils

import de.oliver_heger.linedj.utils.ActorManagement.{ActorStopper, ManagedActorData}
import org.apache.pekko.actor.{ActorRef, PoisonPill}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

object ActorManagement:

  /**
    * A trait abstracting over stopping a specific actor.
    *
    * This trait becomes necessary when dealing with typed actors, for which no
    * default mechanism to stop them is available. Therefore, any typed actor
    * registered at an [[ActorManagement]] instance must have an associated
    * ''ActorStopper'' object.
    */
  trait ActorStopper:
    /**
      * Stops the actor associated with this instance.
      */
    def stop(): Unit

  /**
    * A data class used internally to store data about the managed actors.
    *
    * @param ref     the optional actor reference
    * @param stopper the object to stop the actor
    */
  private case class ManagedActorData(ref: Option[ActorRef], stopper: ActorStopper)


/**
  * A trait providing functionality for managing a set of actors that should be
  * stopped when their lifecycle ends.
  *
  * A class that wants to use this functionality just has to call
  * ''registerActor()'' for each actor it wants to have managed. (If an actor
  * is stopped by the class itself or via some other means, it does not have to
  * be registered.) Registered actors are stored in a thread-safe map using
  * their names as keys, so that they can be accessed from every thread.
  *
  * A concrete subclass can rely on the storage of actors and access the
  * managed actor references via the ''getActor()'' method. However, due to the
  * thread-safe access, there might be a certain overhead which might not be
  * necessary if actors are only accessed from a specific thread, e.g. the UI
  * thread.
  *
  * It is also possible to remove registered actors again (e.g. if they have
  * been stopped manually) and to query the names of the actors that are
  * currently managed.
  *
  * This trait has originally been developed to support classic actors. For
  * typed actors, situation is more complex as casts to the correct reference
  * type are complicated, and typed actors cannot be stopped in a generic way.
  * To at least support the automatic stopping of typed actors, the
  * [[ActorStopper]] trait is introduced. There is an overloaded function to
  * register such objects. (In this case, the access to the typed actor
  * reference does not work though.)
  */
trait ActorManagement:
  /** A map for storing registered actors. */
  private val managedActors = new ConcurrentHashMap[String, ManagedActorData]

  /**
    * Registers the specified actor under the given key. The actor can then be
    * queried via ''getActor()'', and it is stopped when the [[stopActors]]
    * function is called.
    *
    * @param name  the name of the actor
    * @param actor the actor
    * @return the passed in actor
    */
  def registerActor(name: String, actor: ActorRef): ActorRef =
    registerActor(name, classicActorStopper(actor), Some(actor))
    actor

  /**
    * Registers an object to stop an actor under the given key. Optionally the
    * actor reference can be provided. If such a reference is available, it can
    * be queried via ''getActor()''. In any case, the ''ActorStopper'' is
    * invoked when the [[stopActors]] function is called.
    *
    * @param name    the name of the actor
    * @param stopper the object to stop this actor
    * @param ref     the optional actor reference
    */
  def registerActor(name: String, stopper: ActorStopper, ref: Option[ActorRef] = None): Unit =
    managedActors.put(name, ManagedActorData(ref, stopper))

  /**
    * Removes the registration for the actor with the specified name. An
    * option with the associated ''ActorRef'' is returned.
    *
    * @param name the name of the actor to be removed
    * @return an ''Option'' with the reference to the removed actor
    */
  def unregisterActor(name: String): Option[ActorRef] =
    unregisterActorData(name).flatMap(_.ref)

  /**
    * Removes the specified actor from this instance and stops it. The return
    * value indicates whether the operation was successful; a result of
    * '''false''' means that no actor with the given name could be found.
    *
    * @param name the name of the actor to be removed
    * @return a flag whether the operation was successful
    */
  def unregisterAndStopActor(name: String): Boolean =
    unregisterActorData(name) match
      case Some(data) =>
        data.stopper.stop()
        true
      case None =>
        false

  /**
    * Returns the actor reference for the specified name. This actor must have
    * been registered before; otherwise, a ''NoSuchElementException''
    * exception is thrown.
    *
    * @param name the name of the desired actor
    * @return the reference to this actor
    */
  def getActor(name: String): ActorRef =
    val optRef = Option(managedActors get name) flatMap (_.ref)
    optRef.getOrElse(throw new NoSuchElementException(s"No actor registered with name '$name'!"))

  /**
    * Returns an ''Iterable'' with the names of the actors that are currently
    * managed by this instance.
    *
    * @return an ''Iterable'' with the names of the managed actors
    */
  def managedActorNames: Iterable[String] = managedActors.keySet().asScala

  /**
    * Stops all managed actors and removes them from the internal map. This
    * method should be called at least at the end of the lifecycle of this
    * object when the actors are no longer needed.
    */
  def stopActors(): Unit =
    val actors = managedActors.values().asScala
    actors foreach (_.stopper.stop())
    managedActors.clear()

  /**
    * Removes the entry from the managed actors map with the given key and
    * returns an ''Option'' with its content.
    *
    * @param name the name of the actor to unregister
    * @return an ''Option'' with the ''ManagedActorData'' registered
    */
  private def unregisterActorData(name: String): Option[ManagedActorData] =
    Option(managedActors remove name)

  /**
    * Returns an ''ActorStopper'' that can stop the given classic actor.
    *
    * @param a the actor to be stopped
    * @return the object to stop this actor
    */
  private def classicActorStopper(a: ActorRef): ActorStopper =
    () => {
      a ! PoisonPill
    }
