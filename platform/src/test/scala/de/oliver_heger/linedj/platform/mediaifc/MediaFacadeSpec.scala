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
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future

/**
  * Test class for ''MediaFacade''.
  */
class MediaFacadeSpec extends FlatSpec with Matchers {
  "A MediaFacade" should "register a meta data listener" in {
    val facade = new MediaFacadeImpl
    val MediumId = MediumID("A medium", None)

    facade queryMetaDataAndRegisterListener MediumId
    facade.sentMessages should be(List((MediaActors.MetaDataManager,
      GetMetaData(MediumId, registerAsListener = true))))
  }
}

/**
  * Test implementation of the trait.
  */
class MediaFacadeImpl extends MediaFacade {
  override val bus: MessageBus = null

  /** Records messages passed to the send() method (in reverse order). */
  var sentMessages = List.empty[(MediaActor, Any)]

  override def activate(enabled: Boolean): Unit = ???

  override def send(target: MediaActor, msg: Any): Unit = {
    sentMessages = (target, msg) :: sentMessages
  }

  override def initConfiguration(config: Configuration): Unit = ???

  override def requestMediaState(): Unit = ???

  override def requestActor(target: MediaActor)(implicit timeout: Timeout):
  Future[Option[ActorRef]] = ???

  override def removeMetaDataListener(mediumID: MediumID): Unit = ???

  override def registerMetaDataStateListener(): Unit = ???

  override def unregisterMetaDataStateListener(): Unit = ???
}
