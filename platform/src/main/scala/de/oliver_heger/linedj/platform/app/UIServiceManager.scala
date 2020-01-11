/*
 * Copyright 2015-2020 The Developers Team.
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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.app.UIServiceManager.{AddService, ProcessFunc,
ProcessServices, RemoveService}
import de.oliver_heger.linedj.platform.comm.MessageBus

import scala.language.higherKinds

private object UIServiceManager {
  /**
    * Definition of a function for processing the services currently stored by
    * a [[UIServiceManager]] instance. This function is invoked in the UI
    * thread with a collection of services available. If the resulting option
    * is defined, its value is published on the message bus; that way a
    * processing operation can communicate its result or trigger further
    * actions.
    *
    * @tparam S the type of the services to be processed
    */
  type ProcessFunc[S] = Iterable[S] => Option[Any]

  /**
    * A trait defining a message of a given type.
    *
    * This is used to distinguish multiple instances of [[UIServiceManager]].
    * Each instance operates on the same message classes, but the messages have
    * different generic types. The concrete type is determined by a class
    * attribute defined by this trait. There is also a method for casting the
    * message to a given target type.
    *
    * @tparam T the type of this message
    */
  trait TypedMessage[T] {
    /**
      * The type to be used by casts in the ''as()'' method. Derived classes
      * set this type to their own type, so that the return type of ''as()'' is
      * correctly overridden.
      */
    type C[_]

    /**
      * Returns the type of this message as a ''Class'' object.
      *
      * @return the type of this message
      */
    def messageType: Class[T]

    /**
      * Tries to cast this message to the specified type. If the passed in
      * class is the same as of this instance, a defined option with a
      * properly cast reference is returned. Otherwise, result is ''None''.
      * This can be used to process generic messages in a type-safe way.
      *
      * @param cls the target class for the cast
      * @tparam R the type of the
      * @return an option with the result of the cast operation
      */
    def as[R](cls: Class[R]): Option[C[R]] =
      if (messageType == cls) Some(this.asInstanceOf[C[R]])
      else None
  }

  /**
    * A message to add a service to a [[UIServiceManager]] instance. On
    * receiving this message, the service manager adds the contained service
    * object to its internal list, optionally applying a transformation.
    *
    * @param messageType the class of the service object
    * @param service     the service object
    * @param optF        an optional service manipulation function
    * @tparam S the service type
    */
  case class AddService[S](override val messageType: Class[S], service: S,
                           optF: Option[S => S])
    extends TypedMessage[S] {
    override type C[x] = AddService[x]
  }

  /**
    * A message to remove a service from a [[UIServiceManager]] instance.
    * The service specified in the message is removed from the internal list.
    *
    * @param messageType the class of the service object
    * @param service     the service to be removed
    * @tparam S the service type
    */
  case class RemoveService[S](override val messageType: Class[S], service: S)
    extends TypedMessage[S] {
    override type C[x] = RemoveService[x]
  }

  /**
    * A message that triggers a processing of the services managed by a
    * [[UIServiceManager]] instance. The provided processing function is
    * invoked with the current list of services, and its results are published
    * on the message bus.
    *
    * @param messageType the class of the service objects
    * @param f           the processing function
    * @tparam S the service type
    */
  case class ProcessServices[S](override val messageType: Class[S], f: ProcessFunc[S])
    extends TypedMessage[S] {
    override type C[x] = ProcessServices[x]
  }

  /**
    * Creates a new ''UIServiceManager'' instance.
    *
    * @param serviceClass the class of the managed services
    * @param messageBus   the message bus
    * @tparam S the type of the managed services
    * @return the newly created instance
    */
  def apply[S](serviceClass: Class[S], messageBus: MessageBus):
  UIServiceManager[S] = {
    val manager = new UIServiceManager(serviceClass, messageBus)
    val regID = messageBus registerListener manager.receive
    manager.messageBusRegistrationID = regID
    manager
  }
}

/**
  * Internally used helper class for managing services of a specific type that
  * must only be accessed in the event dispatch thread.
  *
  * This class can handle services of a specific type as defined by its
  * generic type parameter. Services can be added and removed, internally
  * synchronization is done via dispatching all method calls on the UI thread.
  *
  * In addition to keeping track on a number of services, an instance allows
  * processing of the services available. For this purpose, a processing
  * function is executed on each service, again on the event dispatch thread.
  *
  * @param serviceClass the class of the managed services
  * @param messageBus   the message bus
  * @tparam S the type of the services managed by this instance
  */
private class UIServiceManager[S] private(val serviceClass: Class[S],
                                          val messageBus: MessageBus) {
  /** The list with managed services. */
  private var serviceList = List.empty[S]

  /** The registration ID for the message bus listener. */
  private var messageBusRegistrationID = 0

  /**
    * Returns a collection with all services stored by this object. '''Note:'''
    * This method must only be called on the UI thread! Otherwise, results are
    * unpredictable.
    *
    * @return a collection with the services currently available
    */
  def services: Iterable[S] = serviceList

  /**
    * Adds the specified service instance to this manager.
    *
    * @param service the service to be added
    * @param optF    an optional service manipulation function
    */
  def addService(service: S, optF: Option[S => S] = None): Unit = {
    messageBus publish AddService(serviceClass, service, optF)
  }

  /**
    * Removes the specified service instance from this manager.
    *
    * @param service the service to be removed
    */
  def removeService(service: S): Unit = {
    messageBus publish RemoveService(serviceClass, service)
  }

  /**
    * Applies the passed in processing function to the list of currently
    * available services. The function is invoked on the UI thread with the
    * list of managed services. If the result it returns is defined, its
    * value is published on the UI message bus. That way a processing operation
    * can publish its results or trigger further actions.
    *
    * @param f the processing function
    */
  def processServices(f: ProcessFunc[S]): Unit = {
    messageBus publish ProcessServices(serviceClass, f)
  }

  /**
    * Shuts down this manager instance. This method must be called when this
    * instance is no longer needed.
    */
  def shutdown(): Unit = {
    messageBus removeListener messageBusRegistrationID
  }

  /**
    * The function for processing messages on the UI message bus.
    *
    * @return the receive function
    */
  private def receive: Receive = {
    case addMsg: AddService[_] =>
      addMsg.as(serviceClass) foreach { m =>
        val f = m.optF.getOrElse(identity[S] _)
        serviceList = f(m.service) :: serviceList
      }

    case removeMsg: RemoveService[_] =>
      removeMsg.as(serviceClass) foreach { m =>
        serviceList = serviceList filterNot (_ == m.service)
      }

    case procMsg: ProcessServices[_] =>
      procMsg.as(serviceClass) foreach { m =>
        val resMsg = m.f(services)
        resMsg foreach messageBus.publish
      }
  }
}
