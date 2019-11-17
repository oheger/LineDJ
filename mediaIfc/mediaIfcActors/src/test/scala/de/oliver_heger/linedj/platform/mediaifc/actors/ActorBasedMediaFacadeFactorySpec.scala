/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.actors

import akka.actor.{ActorRef, ActorSystem}
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.actors.impl.ManagementActor
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ActorBasedMediaFacadeFactory''.
  */
class ActorBasedMediaFacadeFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  "An ActorBasedMediaFacadeFactory" should "create a MediaFacade" in {
    val actorFactory = mock[ActorFactory]
    val actorSystem = mock[ActorSystem]
    val messageBus = mock[MessageBus]
    val relayActor = mock[ActorRef]
    when(actorFactory.createActor(ManagementActor(messageBus), "FacadeManagementActor"))
      .thenReturn(relayActor)
    when(actorFactory.actorSystem).thenReturn(actorSystem)
    val factory = new MediaFacadeFactoryImpl

    val facade = factory.createMediaFacade(actorFactory, messageBus)
    val creation = factory.createdFacades.head
    creation.relayActor should be(relayActor)
    creation.system should be(actorSystem)
    creation.bus should be(messageBus)
    facade should be(creation.facade)
  }

  /**
    * A test implementation of the factory which records the data passed to
    * ''createFacadeImpl()''.
    */
  private class MediaFacadeFactoryImpl extends ActorBasedMediaFacadeFactory {
    /** Stores information about facades created by this object. */
    var createdFacades = List.empty[MediaFacadeCreation]

    override protected def createFacadeImpl(relayActor: ActorRef, system: ActorSystem,
                                            bus: MessageBus): MediaFacade = {
      val facade = mock[MediaFacade]
      createdFacades = MediaFacadeCreation(relayActor, system, bus, facade) :: createdFacades
      facade
    }
  }

}

/**
  * Data class that stores the information about a newly created media facade.
  *
  * @param relayActor the relay actor
  * @param system     the actor system
  * @param bus        the message bus
  * @param facade     the mock facade object
  */
private case class MediaFacadeCreation(relayActor: ActorRef, system: ActorSystem,
                                       bus: MessageBus, facade: MediaFacade)
