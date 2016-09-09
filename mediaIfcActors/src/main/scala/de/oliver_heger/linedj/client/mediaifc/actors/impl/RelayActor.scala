/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.client.mediaifc.actors.impl

import akka.actor.{Actor, ActorRef, Props}
import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.client.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.client.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.RemoveMediumListener
import de.oliver_heger.linedj.utils.ChildActorFactory

object RelayActor {

  /**
   * A message received by [[RelayActor]] which activates or disables
   * tracking of the server state. The actor must be activated first before it
   * sends ''ServerAvailable'' or ''ServerUnavailable'' messages.
   * @param enabled flag whether server monitoring is enabled
   */
  case class Activate(enabled: Boolean)

  /**
   * A message to be processed by [[RelayActor]] telling it to send a
   * message to a media actor. The actor is determined by the ''target''
   * parameter. If this media actor is currently available, the message is
   * sent to it.
   * @param target the media actor type which receives the message
   * @param msg the message to be sent
   */
  case class MediaMessage(target: MediaActor, msg: Any)

  /**
   * A message to be processed by [[RelayActor]] that requests a
   * reference to a media actor. This message is answered by a
   * [[MediaActorResponse]] message.
   *
   * @param actorType the media actor type
   */
  case class MediaActorRequest(actorType: MediaActor)

  /**
   * A message sent by [[RelayActor]] as response to a
   * [[MediaActorRequest]] message. The reference to the desired media actor
   * is returned as an option as it might not be available.
   *
   * @param actorType the type of the media actor
   * @param optActor the optional reference to the media actor
   */
  case class MediaActorResponse(actorType: MediaActor, optActor: Option[ActorRef])

  /**
    * A message to be processed by [[RelayActor]] that causes a listener
    * registration for the specified medium to be removed.
    * @param mediumId the ID of the medium to be removed
    */
  case class RemoveListener(mediumId: MediumID)

  /**
    * A message processed by [[RelayActor]] for querying the current
    * server state. When this message is received, the current state is
    * published on the message bus.
    */
  case object QueryServerState

  /** The delay sequence for looking up remote actors. */
  private val DelaySequence = new BoundedDelaySequence(90, 5, 2)

  private class RelayActorImpl(remoteAddress: String, remotePort: Int, messageBus:
  MessageBus) extends RelayActor(remoteAddress, remotePort, messageBus) with ChildActorFactory

  /**
   * Creates a ''Props'' object for creating a new actor instance.
   * @param remoteAddress the address of the remote actor system
   * @param remotePort the port of the remote actor system
   * @param messageBus the message bus
   * @return creation properties for a new actor instance
   */
  def apply(remoteAddress: String, remotePort: Int, messageBus: MessageBus): Props =
    Props(classOf[RelayActorImpl], remoteAddress, remotePort, messageBus)

  /**
   * A data class holding the current tracking state of remote actors. This
   * class stores the references to remote actors and their types. It also
   * allows access to a specific remote actor.
   * @param trackedActors a map with information about tracked actors
   */
  private class RemoteActorTrackingState(trackedActors: Map[MediaActor, ActorRef]) {
    /**
     * Returns a flag whether all remote actors are currently available. This
     * is used as indication that the server is now available.
     * @return '''true''' if all tracked remote actors are available
     */
    def trackingComplete: Boolean =
      trackedActors.size == MediaActors.values.size

    /**
     * Returns an updated tracking state object when a remote actor becomes
     * available.
     * @param a option for the type of the remote actor
     * @param ref the actor reference
     * @return the updated tracking state
     */
    def remoteActorFound(a: Option[MediaActor], ref: ActorRef): RemoteActorTrackingState =
      updateState(a) { (act, m) => m + (act -> ref) }

    /**
     * Returns an updated tracking state object when a remote actor becomes
     * unavailable.
     * @param a option for the type of the remote actor
     * @return the updated tracking state
     */
    def remoteActorLost(a: Option[MediaActor]): RemoteActorTrackingState =
      updateState(a) { (act, m) => m - act }

    /**
     * Returns an option for the current remote actor of the given type. This
     * is ''None'' if this actor is currently not available.
     * @param a the remote actor type
     * @return an option for the reference to this remote actor
     */
    def remoteActorOption(a: MediaActor): Option[ActorRef] = trackedActors get a

    /**
     * Calculates the new tracking state based on a function to be applied on
     * the current map with remote actors. This function is called when change
     * events for remote actors are processed.
     * @param a option for the remote actor type affected by the change; this
     *          may be ''None'' if an invalid actor path was passed
     * @param f the function to update the state map
     * @return the new tracking state
     */
    private def updateState(a: Option[MediaActor])
                           (f: (MediaActor, Map[MediaActor, ActorRef]) => Map[MediaActor,
                             ActorRef]): RemoteActorTrackingState = {
      a map (ra => new RemoteActorTrackingState(f(ra, trackedActors))) getOrElse this
    }
  }

  /**
   * Returns the state message to be sent for the specified tracking state.
   * @param trackingState the tracking state
   * @return the state message
   */
  private def stateMessage(trackingState: RemoteActorTrackingState): Any =
    if (trackingState.trackingComplete) MediaFacade.MediaArchiveAvailable
    else MediaFacade.MediaArchiveUnavailable
}

/**
 * An actor class which handles the communication with the remote actor system.
 *
 * This actor class is responsible for establishing a connection to the
 * actor system running the media archive. This is achieved by looking up a
 * number of (remote) actors the client needs to access; only if references to
 * all required remote actors have been resolved, the media archive is
 * considered available. Address and port of the remote actor system have to be
 * specified as constructor arguments.
 *
 * All message exchange with media actors is routed via this actor. In order
 * to send a message to a remote actor, the message is wrapped into a
 * ''RemoteMessage'' object which identifies the target actor. Messages
 * received from the remote system (as answers to requests) are published via
 * the specified [[MessageBus]].
 *
 * Before this actor is active and sends messages regarding the server state,
 * it has to be enabled by sending it an ''Activate(true)'' message. As answer
 * to this message the current server state is sent. Further on, all changes on
 * the server state cause ''ServerAvailable'' or ''ServerUnavailable''
 * messages to be sent.
 *
 * @param remoteAddress the address of the remote actor system
 * @param remotePort the port of the remote actor system
 * @param messageBus the message bus
 */
class RelayActor(remoteAddress: String, remotePort: Int, messageBus: MessageBus) extends
Actor {
  this: ChildActorFactory =>

  import RelayActor._

  /** A map for assigning lookup paths to corresponding remote actors. */
  private val pathMapping = createLookupPathMapping()

  /** The current remote actor tracking state. */
  private var trackingState = new RemoteActorTrackingState(Map.empty)

  /** The current activated flag. */
  private var activated = false

  /**
   * @inheritdoc This implementation creates lookup actors for the remote
   *             actors to be tracked.
   */
  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()

    pathMapping.keys foreach { p =>
      createChildActor(Props(classOf[LookupActor], p, self, DelaySequence))
    }
  }

  override def receive: Receive = {
    case Activate(enabled) =>
      activated = enabled
      publish(stateMessage(trackingState))

    case QueryServerState =>
      publish(stateMessage(trackingState))

    case LookupActor.RemoteActorAvailable(path, ref) =>
      updateTrackingState(trackingState.remoteActorFound(pathMapping get path, ref))

    case LookupActor.RemoteActorUnavailable(path) =>
      updateTrackingState(trackingState.remoteActorLost(pathMapping get path))

    case MediaMessage(target, msg) =>
      sendToTarget(target, msg)

    case MediaActorRequest(actorType) =>
      sender ! MediaActorResponse(actorType, trackingState remoteActorOption actorType)

    case RemoveListener(mediumId) =>
      sendToTarget(MediaActors.MetaDataManager, RemoveMediumListener(mediumId, self))

    case msg =>
      messageBus publish msg
  }

  /**
   * Updates the remote actor tracking state. This method is called when a
   * message from a lookup actor was received indicating that a remote actor
   * was detected or became unavailable. If necessary, the changed server state
   * has to be published.
   * @param newState the updated tracking state
   */
  private def updateTrackingState(newState: RemoteActorTrackingState): Unit = {
    val oldState = trackingState
    trackingState = newState
    if (oldState.trackingComplete != newState.trackingComplete) {
      publish(stateMessage(newState))
    }
  }

  /**
    * Sends the specified message to the given target actor, provided that it
    * is available.
    *
    * @param target the target actor
    * @param msg    the message to be sent
    */
  private def sendToTarget(target: MediaActors.MediaActor, msg: Any): Unit = {
    trackingState remoteActorOption target foreach (_ ! msg)
  }

  /**
   * Sends a message on the message bus if this actor is enabled.
   * @param msg the message
   */
  private def publish(msg: => Any): Unit = {
    if (activated) {
      messageBus publish msg
    }
  }

  /**
   * Creates a map that allows mapping a remote lookup path to the
   * corresponding remote actor type.
   * @return the map
   */
  private def createLookupPathMapping(): Map[String, MediaActor] = {
    val mapping = MediaActors.values map (a => lookupPath(a) -> a)
    Map(mapping.toSeq: _*)
  }

  /**
   * Generates the lookup path for a remote actor based on the parameters
   * passed to this instance.
   * @param actor the remote actor
   * @return the lookup path to this actor
   */
  private def lookupPath(actor: MediaActor): String =
    s"akka.tcp://LineDJ-Server@$remoteAddress:$remotePort/user/${actor.name}"
}
