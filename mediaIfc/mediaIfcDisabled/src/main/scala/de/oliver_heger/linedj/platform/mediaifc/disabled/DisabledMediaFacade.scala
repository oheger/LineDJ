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

package de.oliver_heger.linedj.platform.mediaifc.disabled

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.apache.commons.configuration.Configuration
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import scala.concurrent.Future

/**
  * A dummy implementation of the [[MediaFacade]] trait.
  *
  * This implementation can be used for applications that do not require a
  * media archive. It just provides dummy implementations for the single facade
  * methods.
  *
  * @param bus the ''MessageBus''
  */
class DisabledMediaFacade(override val bus: MessageBus) extends MediaFacade:
  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /**
    * @inheritdoc This is just a dummy implementation.
    */
  override def activate(enabled: Boolean): Unit =
    log.info("Media archive not available.")

  /**
    * @inheritdoc This implementation does not send any message, but just logs
    *             a warning.
    */
  override def send(target: MediaActor, msg: Any): Unit =
    log.warn("Media archive not available. Ignoring message {} to {}.",
      msg, target)

  /**
    * @inheritdoc This is just a dummy implementation.
    */
  override def initConfiguration(config: Configuration): Unit = {}

  /**
    * @inheritdoc This implementation publishes an unavailable message on the
    *             message bus.
    */
  override def requestMediaState(): Unit =
    bus publish MediaFacade.MediaArchiveUnavailable

  /**
    * @inheritdoc This implementation returns a ''Future'' that completes with
    *             ''None''.
    */
  override def requestActor(target: MediaActor)(implicit timeout: Timeout):
  Future[Option[ActorRef]] = Future.successful(None)

  /**
    * @inheritdoc This is just a dummy implementation.
    */
  override def removeMetadataListener(mediumID: MediumID): Unit = {}

  /**
    * @inheritdoc This is just a dummy implementation.
    */
  override def registerMetadataStateListener(componentID: ComponentID): Unit = {}

  /**
    * @inheritdoc This is just a dummy implementation.
    */
  override def unregisterMetadataStateListener(componentID: ComponentID): Unit = {}
