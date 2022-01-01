/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorRef
import akka.util.Timeout
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.GetMetaData
import org.apache.commons.configuration.Configuration

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

object MediaFacade {

  /**
    * A common base trait for events determining the availability of the media
    * archive. This allows handling ''MediaArchiveAvailable'' and
    * ''MediaArchiveUnavailable'' in a generic way.
    */
  sealed trait MediaArchiveAvailabilityEvent

  /**
    * A message published via the message bus when the media archive becomes
    * available. Monitoring this message allows an application to keep track
    * on the current state of the media archive.
    */
  case object MediaArchiveAvailable extends MediaArchiveAvailabilityEvent

  /**
    * A message published via the message bus when the media archive is no
    * longer available. This message can be monitored by applications that
    * rely on the media archive; they can update themselves accordingly when
    * they receive this notification.
    */
  case object MediaArchiveUnavailable extends MediaArchiveAvailabilityEvent

  /**
    * Constant for a reserved/invalid listener registration ID. The
    * ''queryMetaDataAndRegisterListener()'' method will never return this ID.
    * This can be used to mark an invalid listener ID.
    */
  val InvalidListenerRegistrationID = 0

  /**
    * A class allowing access to all actors comprising the interface to the
    * media archive.
    *
    * ''MediaFacade'' has a method to request all actors at once. This is
    * needed by most clients that have to interact with the archive. This
    * method returns a ''Future'' of this class. If successful, actor
    * references are directly available.
    *
    * @param mediaManager    the reference to the media manager
    * @param metaDataManager the reference to the meta data manager
    */
  case class MediaFacadeActors(mediaManager: ActorRef, metaDataManager: ActorRef)

}

/**
  * A trait defining a facade for accessing the media archive.
  *
  * The media archive is controlled by a couple of actors which could be
  * invoked directly. However, obtaining references to these actors is not
  * trivial, as the archive may operate in different modes (e.g. remote,
  * embedded, disabled). Therefore, this trait can be used to simplify access.
  * There will be different implementations for the various operation modes of
  * the media archive.
  *
  * This trait defines some methods addressing some frequent use cases, for
  * instance related to the initialization of the archive. There is also a
  * generic ''send()'' method to send a message to one of the actors
  * controlling the archive. This method is convenient for UI components that
  * expect a result to be published on the UI message bus.
  *
  * For some use cases it is necessary to directly interact with a controlling
  * actor, e.g. if the client is itself an actor that needs a direct message
  * exchange scenario. In such cases, an actor reference can be queried, but
  * clients need to be aware that the target actor may not be available. (If
  * the archive runs in remote mode, a connection can be lost at any time.)
  *
  * The methods defined by this trait construct a pretty low-level API to the
  * media archive. The LineDJ platform provides some components that operate on
  * a higher level and simplify interactions with the media archive. Such
  * components should be used whenever possible.
  */
trait MediaFacade {
  import MediaFacade._

  /** A counter for generating registration IDs. */
  private val registrationIDCounter = new AtomicInteger

  /**
    * A reference to the message bus. This is used to publish responses from
    * the media archive to UI components.
    */
  val bus: MessageBus

  /**
    * Changes the active state of the archive client. If active, the client
    * actively tries to reach the controlling actors, which may involve remote
    * calls or monitoring. Notifications are published on the UI bus whether
    * the media archive is available or not.
    *
    * @param enabled the enabled flag
    */
  def activate(enabled: Boolean): Unit

  /**
    * Sends a message to a media actor. No answer is expected; so the message
    * is just passed to the specified target actor.
    *
    * @param target the target actor
    * @param msg    the message
    */
  def send(target: MediaActor, msg: Any): Unit

  /**
    * Initializes configuration data. This method is called at application
    * startup before activation. If an implementation requires special
    * configuration settings, it can fetch from the passed in object.
    *
    * @param config the configuration object
    */
  def initConfiguration(config: Configuration): Unit

  /**
    * Triggers a request for the current state of the media archive. As
    * reaction the current enabled state is published on the message bus.
    */
  def requestMediaState(): Unit

  /**
    * Requests a reference to a controlling actor. The reference is obtained
    * in an asynchronous call. It may not be available, depending on the
    * current state of the media archive (which can change at any time).
    * Therefore, the future result is an option of an actor reference.
    *
    * @param target  the desired target actor
    * @param timeout a timeout for this operation
    * @return a future with an optional actor reference
    */
  def requestActor(target: MediaActor)(implicit timeout: Timeout): Future[Option[ActorRef]]

  /**
    * Returns a ''Future'' with an object that provides access to all actors
    * of the media facade. While ''requestActor()'' can be used if only a
    * single actors is needed, this method returns all actors of the media
    * facade. This is more convenient for clients needing full interaction
    * with the archive.
    *
    * @param timeout a timeout for requesting the single actors
    * @param ec      an execution context
    * @return a ''Future'' with an object exposing all actors of the interface
    */
  def requestFacadeActors()(implicit timeout: Timeout, ec: ExecutionContext):
  Future[MediaFacadeActors] = {
    def requestArchiveActor(t: MediaActor): Future[ActorRef] =
      requestActor(t) map (_.get)

    val futMedia = requestArchiveActor(MediaActors.MediaManager)
    val futMeta = requestArchiveActor(MediaActors.MetaDataManager)
    Future.sequence(List(futMedia, futMeta)) map { lst =>
      MediaFacadeActors(lst.head, lst(1))
    }
  }

  /**
    * A convenience method which calls the meta data manager actor to request
    * meta data for the specified medium and to register a listener to receive
    * notifications when new meta data arrives. This method generates a
    * listener registration ID; it is returned to the caller. Based on this ID
    * the caller can detect whether meta data response messages are up-to-date.
    *
    * @param mediumID the medium ID
    * @return a listener registration ID
    */
  def queryMetaDataAndRegisterListener(mediumID: MediumID): Int = {
    val id = nextListenerRegistrationID()
    send(MediaActors.MetaDataManager, GetMetaData(mediumID, registerAsListener = true, id))
    id
  }

  /**
    * Removes a listener from the meta data manager actor for the specified
    * medium ID. This client will then no longer receive update notifications
    * for this medium.
    *
    * @param mediumID the medium ID
    */
  def removeMetaDataListener(mediumID: MediumID): Unit

  /**
    * Requests the registration of a meta data state listener. Objects calling
    * this method are interested in receiving meta data state events via the
    * message bus. Note that there can be multiple components interested in
    * such events, and all should call this method. It is up to a concrete
    * implementation to deal with multiple requests of different components in
    * a graceful way: A single physical listener registration with the media
    * archive should be established if and only if the number of registered
    * state listener components is greater zero. The method expects the
    * component ID of the registering listener as argument to keep track on
    * registered component. For each component, only a single registration is
    * counted. A component is free to call this method multiple times with its
    * ID; an implementation must ignore all registrations except for the first
    * one.
    *
    * @param componentID the ID of the registering component
    */
  def registerMetaDataStateListener(componentID: ComponentID): Unit

  /**
    * Removes the state listener registration for the specified component. This
    * method can be called by components that have called
    * ''registerMetaDataStateListener()'' if they are no longer interested in
    * these events. It is up to an implementation to decide whether the
    * physical registration can be canceled or whether there are still
    * registration requests remaining, so that the registration has to be kept
    * alive. If there is no component with the specified ID registered as state
    * listener, this invocation has no effect.
    *
    * @param componentID the ID of the component to be removed as listener
    */
  def unregisterMetaDataStateListener(componentID: ComponentID): Unit

  /**
    * Returns the next ID for a listener registration and updates the internal
    * counter.
    *
    * @return the listener registration ID
    */
  @tailrec private def nextListenerRegistrationID(): Int = {
    val id = registrationIDCounter.getAndIncrement()
    if (id == InvalidListenerRegistrationID) nextListenerRegistrationID()
    else id
  }
}
