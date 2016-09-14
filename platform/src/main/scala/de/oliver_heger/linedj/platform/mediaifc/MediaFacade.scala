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

package de.oliver_heger.linedj.platform.mediaifc

import akka.actor.ActorRef
import akka.util.Timeout
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.GetMetaData
import org.apache.commons.configuration.Configuration

import scala.concurrent.Future

object MediaFacade {

  /**
    * A message published via the message bus when the media archive becomes
    * available. Monitoring this message allows an application to keep track
    * on the current state of the media archive.
    */
  case object MediaArchiveAvailable

  /**
    * A message published via the message bus when the media archive is no
    * longer available. This message can be monitored by applications that
    * rely on the media archive; they can update themselves accordingly when
    * they receive this notification.
    */
  case object MediaArchiveUnavailable
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
  */
trait MediaFacade {
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
    * A convenience method which calls the meta data manager actor to request
    * meta data for the specified medium and to register a listener to receive
    * notifications when new meta data arrives.
    *
    * @param mediumID the medium ID
    */
  def queryMetaDataAndRegisterListener(mediumID: MediumID): Unit = {
    send(MediaActors.MetaDataManager, GetMetaData(mediumID, registerAsListener = true))
  }

  /**
    * Removes a listener from the meta data manager actor for the specified
    * medium ID. This client will then no longer receive update notifications
    * for this medium.
    *
    * @param mediumID the medium ID
    */
  def removeMetaDataListener(mediumID: MediumID): Unit
}
