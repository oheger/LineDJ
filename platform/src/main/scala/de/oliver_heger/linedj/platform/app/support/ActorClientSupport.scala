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
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport.{ActorRequest, FutureUICallback}
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.osgi.service.component.ComponentContext

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.Try

object ActorClientSupport:

  /**
    * A trait describing a request to the actor. The request can either be
    * executed yielding a ''Future'', or the result can be passed to a callback
    * on the UI thread.
    */
  trait ActorRequest:
    /**
      * Executes this request and returns a ''Future'' mapped to the expected
      * result type.
      *
      * @param c class tag for the result type
      * @tparam R the result type
      * @return a ''Future'' for the result of the invocation
      */
    def execute[R](implicit c: ClassTag[R]): Future[R]

    /**
      * Executes this request and obtains the result, mapped to the specified
      * result type. This result is then passed to the provided callback in the
      * UI thread. This is useful for requests issued from UI components.
      *
      * @param callback the callback for propagating the result
      * @param c        class tag for the result type
      * @tparam R the result type
      * @tparam U the return type of the callback (just ignored)
      */
    def executeUIThread[R, U](callback: Try[R] => U)(implicit c: ClassTag[R]): Unit

  /**
    * Internally used helper class to run an ''onComplete()'' callback of a
    * ''Future'' in the UI thread.
    *
    * @param callback the callback to be invoked
    * @param result   the result of the ''Future''
    * @tparam T the type of the result
    * @tparam U the return type of the callback (just ignored)
    */
  private case class FutureUICallback[T, U](callback: Try[T] => U, result: Try[T]):
    /**
      * Invokes the managed callback with the result object.
      */
    def invokeCallback(): Unit =
      callback(result)


/**
  * A support trait that simplifies working with actors.
  *
  * This trait can be mixed in by platform components that need to access
  * actors created by other components. It provides methods to resolve actors
  * by path names.
  *
  * Querying actor references and interactions with actors via the ''ask''
  * pattern typically yields results of type ''Future''. This traits
  * provides some implicits to simplify work with such objects:
  *
  *  - It defines an ''ExecutionContext'', so that ''Future'' objects can be
  *    processed without having to care about this context; the '''implicit'''
  *    parameter is resolved automatically.
  *  - Platform components often need to handle results in the UI thread, e.g.
  *    to update UI components. For this purpose, an implicit conversion from a
  *    ''Future'' to a ''UIFuture'' is added. This type offers a method to
  *    invoke a callback in the UI thread when the ''Future'' completes.
  */
trait ActorClientSupport extends PlatformComponent:
  /** Stores the registration ID for the message bus. */
  private var messageBusRegistrationID = 0

  /**
    * Provides access to the central ''ActorSystem'' in implicit scope. This is
    * useful for clients that do operations requiring an actor system.
    *
    * @return the central ''ActorSystem''
    */
  implicit def actorSystem: ActorSystem = clientApplicationContext.actorSystem

  /**
    * Provides access to an ''ExecutionContext'' based on the central actor
    * system. This '''implicit''' allows derived classes to operate on
    * ''Future'' objects without having to provide such a context manually.
    *
    * @return a central ''ExecutionContext''
    */
  implicit def executionContext: ExecutionContext =
    clientApplicationContext.actorSystem.dispatcher

  /**
    * Implicit conversion function to enhance ''Future'' by a method to
    * complete on the UI thread. This is provided by an internal wrapper
    * class.
    *
    * @param f the ''Future'' to be extended
    * @tparam T the type of the ''Future'' result
    * @return the enriched ''Future''
    */
  implicit def toUIFuture[T](f: Future[T]): UIFuture[T] = new UIFuture[T](f)

  /**
    * Resolves an actor by its path and returns a ''Future'' to its reference.
    *
    * @param path    the path to the actor
    * @param timeout a timeout
    * @return a future for the resolved actor reference
    */
  def resolveActor(path: String)(implicit timeout: Timeout): Future[ActorRef] =
    val selection = clientApplicationContext.actorSystem.actorSelection(path)
    selection.resolveOne()

  /**
    * Resolves an actor by its path and invokes the specified callback with
    * the result in the UI thread. This is useful if UI-related components
    * (e.g. controller classes) need to access actors.
    *
    * @param path    the path to the actor
    * @param f       the callback to be invoked with the result of the resolve
    *                operation
    * @param timeout a timeout
    * @tparam U the return value of the callback (just ignored)
    */
  def resolveActorUIThread[U](path: String)(f: Try[ActorRef] => U)
                             (implicit timeout: Timeout): Unit =
    resolveActor(path) onCompleteUIThread f

  /**
    * Produces an ''ActorRequest'' for the specified actor and message. The
    * request can then be executed in various ways. A timeout has to be
    * provided either directly or implicitly.
    *
    * @param actor   the actor to be invoked
    * @param msg     the message to be sent to the actor
    * @param timeout a timeout
    * @return an ''ActorRequest'' which can be executed
    */
  def actorRequest(actor: ActorRef, msg: Any)(implicit timeout: Timeout): ActorRequest =
    ActorRequestImpl(actor, msg, timeout)

  /**
    * @inheritdoc This implementation installs a message bus receiver to handle
    *             synchronization with the UI thread.
    */
  abstract override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)
    messageBusRegistrationID =
      clientApplicationContext.messageBus registerListener messageBusReceive

  /**
    * @inheritdoc This implementation performs cleanup.
    */
  abstract override def deactivate(componentContext: ComponentContext): Unit =
    clientApplicationContext.messageBus removeListener messageBusRegistrationID
    super.deactivate(componentContext)

  /**
    * Returns the message bus receiving function.
    *
    * @return the message bus receiving function
    */
  private def messageBusReceive: Actor.Receive =
    case callback: FutureUICallback[_, _] =>
      callback.invokeCallback()

  /**
    * Internal wrapper class providing additional functionality for ''Future''
    * objects.
    *
    * @param future the wrapped ''Future''
    * @tparam T the type of the ''Future''
    */
  class UIFuture[T](future: Future[T]):
    /**
      * Completes the wrapped ''Future'' and passes the result to the given
      * callback in the UI thread.
      *
      * @param f the callback
      * @tparam U the result of the callback (just ignored)
      */
    def onCompleteUIThread[U](f: Try[T] => U): Unit =
      future.onComplete(t => clientApplicationContext.messageBus publish FutureUICallback(f, t))

  /**
    * Internal implementation of ''ActorRequest''.
    *
    * @param actor   the actor to be invoked
    * @param msg     the message to be sent
    * @param timeout the timeout
    */
  private case class ActorRequestImpl(actor: ActorRef, msg: Any, timeout: Timeout)
    extends ActorRequest:
    override def execute[R](implicit c: ClassTag[R]): Future[R] =
      actor.ask(msg)(timeout).mapTo(c)

    override def executeUIThread[R, U](callback: Try[R] => U)(implicit c: ClassTag[R]):
    Unit = execute onCompleteUIThread callback

