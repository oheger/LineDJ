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

package de.oliver_heger.linedj.platform.mediaifc.embedded

import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.commons.configuration.Configuration
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''EmbeddedMediaFacade''.
  */
class EmbeddedMediaFacadeSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "An EmbeddedMediaFacade" should "pass constructor arguments to the super class" in {
    val helper = new EmbeddedMediaFacadeTestHelper
    val facade = helper.createFacade()

    facade.relayActor should be(helper.relayActor)
    facade.actorSystem should be(helper.actorSystem)
    facade.bus should be(helper.messageBus)
  }

  it should "return the correct path prefix" in {
    val config = mock[Configuration]
    val facade = new EmbeddedMediaFacadeTestHelper().createFacade()

    facade.createActorPathPrefix(config) should be("/user/")
    verifyNoInteractions(config)
  }

  /**
    * A test helper class that manages the required dependencies.
    */
  private class EmbeddedMediaFacadeTestHelper {
    /** Reference to the relay actor. */
    val relayActor: ActorRef = mock[ActorRef]

    /** The actor system. */
    val actorSystem: ActorSystem = mock[ActorSystem]

    /** The message bus. */
    val messageBus: MessageBus = mock[MessageBus]

    /**
      * Creates a new facade test instance.
      *
      * @return the facade instance
      */
    def createFacade(): EmbeddedMediaFacade =
    new EmbeddedMediaFacade(relayActor, actorSystem, messageBus)
  }

}
