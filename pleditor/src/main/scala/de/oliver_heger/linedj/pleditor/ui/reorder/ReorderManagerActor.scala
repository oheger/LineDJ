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

package de.oliver_heger.linedj.pleditor.ui.reorder

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import de.oliver_heger.linedj.pleditor.ui.reorder.ReorderManagerActor._
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.apache.pekko.actor._

import scala.collection.immutable.Seq

object ReorderManagerActor:

  /**
    * A message processed by [[ReorderManagerActor]] which requests information
    * about the currently available reorder services. As a response to this
    * message an [[AvailableReorderServices]] object is created.
    */
  case object GetAvailableReorderServices

  /**
    * A message produced by [[ReorderManagerActor]] as answer for a request for
    * the currently available reorder services. The message contains a list of
    * services with their (user-readable) names.
    *
    * @param services the list of currently available services
    */
  case class AvailableReorderServices(services: Seq[(PlaylistReorderer, String)])

  /**
    * A message processed by [[ReorderManagerActor]] telling it that another
    * ''PlaylistReorderer'' object is now available. Information about this
    * service is stored internally, so that it can be invoked.
    *
    * @param service the ''PlaylistReorderer'' service
    * @param name    the name under which this service is registered
    */
  case class AddReorderService(service: PlaylistReorderer, name: String)

  /**
    * A message processed by [[ReorderManagerActor]] telling it that a
    * ''PlaylistReorderer'' service is no longer available. The service is
    * removed from the internal data structure and can no longer be invoked.
    *
    * @param service the removed service
    */
  case class RemoveReorderService(service: PlaylistReorderer)

  /**
    * A message processed by [[ReorderManagerActor]] that triggers an
    * invocation or a reorder service. The specified service is invoked on
    * a sequence of song data.
    *
    * @param service the service to be invoked
    * @param request the request describing the reorder operation
    */
  case class ReorderServiceInvocation(service: PlaylistReorderer, request: ReorderActor
  .ReorderRequest)

  /**
    * A message indicating a failed invocation of a reorder service. An object
    * of this class is published on the message bus if an invocation did not
    * succeed. This typically means that the reorder service is no longer
    * available.
    *
    * @param service the service which could not be invoked
    */
  case class FailedReorderServiceInvocation(service: PlaylistReorderer)

  /**
    * An internally used data class for storing information about reorder
    * services.
    *
    * @param name       the name of the service
    * @param childActor the associated child actor
    */
  private case class ServiceData(name: String, childActor: ActorRef)

  private class ReorderManagerActorImpl(bus: MessageBus) extends ReorderManagerActor(bus) with ChildActorFactory

  /**
    * Creates a ''Props'' object for creating actor instances.
    *
    * @param bus the ''MessageBus''
    * @return the creation ''Props''
    */
  def apply(bus: MessageBus): Props =
    Props(classOf[ReorderManagerActorImpl], bus)

/**
  * An actor implementation which manages all available reorder services.
  *
  * Service objects implementing the
  * [[de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer]] interface can be
  * added and removed dynamically. This actor is responsible for managing the
  * services available and processing requests to reorder parts of the
  * playlist. This is achieved by creating [[ReorderActor]] child actors for
  * each reorder service that is currently available.
  *
  * This actor class supports messages for adding and removing reorder
  * services. Such messages are processed by updating the child actors
  * accordingly. A message for executing a reorder operation is also supported.
  * It is delegated to the corresponding child actor, and the result is
  * published on the system message bus.
  *
  * @param bus the ''MessageBus''
  */
class ReorderManagerActor(bus: MessageBus) extends Actor with ActorLogging:
  this: ChildActorFactory =>

  /**
    * A map storing information about the currently available reorder
    * services.
    */
  private val reorderServices = collection.mutable.Map.empty[PlaylistReorderer, ServiceData]

  /**
    * This actor uses a supervision strategy that stops failing child actors.
    * This means that every time a reorder operation fails - because the
    * wrapped reorder service is no longer available -, the child actor is
    * stopped, and a ''Terminated'' message is received. This is handled by
    * removing the corresponding reorder service.
    */
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy():
    case _: Exception =>
      Stop

  override def receive: Receive =
    case GetAvailableReorderServices =>
      sender() ! AvailableReorderServices(availableReorderServices())

    case AddReorderService(service, name) =>
      val child = createChildActor(Props(classOf[ReorderActor], service))
      context watch child
      reorderServices += service -> ServiceData(name, child)

    case RemoveReorderService(service) =>
      val optData = reorderServices remove service
      optData foreach { d =>
        context unwatch d.childActor
        context stop d.childActor
      }

    case ReorderServiceInvocation(service, request) =>
      handleServiceInvocation(service, request, reorderServices get service)

    case response: ReorderActor.ReorderResponse =>
      bus publish response

    case t: Terminated =>
      handleChildActorTermination(t.actor)

  /**
    * Returns a list with meta data about all currently available reorder
    * services.
    *
    * @return the list with service information
    */
  private def availableReorderServices(): List[(PlaylistReorderer, String)] =
    reorderServices.toList map (e => (e._1, e._2.name))

  /**
    * Handles a request for invoking a reorder service. The invocation may fail
    * if no corresponding service data can be obtained.
    *
    * @param service the service to be invoked
    * @param request the request to be executed
    * @param optData an option with the associated service data
    */
  private def handleServiceInvocation(service: PlaylistReorderer, request: ReorderActor
  .ReorderRequest, optData: Option[ServiceData]): Unit =
    optData match
      case Some(data) =>
        data.childActor ! request

      case None =>
        bus publish FailedReorderServiceInvocation(service)

  /**
    * Handles a message that a child actor has been terminated. This typically
    * happens when a reorder operation is executed, but the reorder service is
    * suddenly no longer available. In this case, an error message is sent on
    * the message bus, and the service is removed from the internal list.
    *
    * @param childActor the affected child actor
    */
  private def handleChildActorTermination(childActor: ActorRef): Unit =
    log.warning("Invocation of a child actor failed!")
    val item = reorderServices.find(p => p._2.childActor == childActor)
    item foreach { p =>
      val service = p._1
      reorderServices remove service
      bus publish FailedReorderServiceInvocation(service)
    }
