/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.remote

import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''RemoteMediaFacade''.
  */
class RemoteMediaFacadeSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A RemoteMediaFacade" should "pass constructor arguments to its base class" in:
    val helper = new RemoteMediaFacadeTestHelper
    val facade = helper.createFacade()

    facade.relayActor should be(helper.relayActor)
    facade.actorSystem should be(helper.actorSystem)
    facade.bus should be(helper.messageBus)

  it should "create a path prefix from configuration settings" in:
    val config = new PropertiesConfiguration
    config.addProperty("media.host", "myTestHost")
    config.addProperty("media.port", 1234)
    config.addProperty("media.systemName", "testSystem")
    val facade = new RemoteMediaFacadeTestHelper().createFacade()

    val path = facade.createActorPathPrefix(config)
    path should be("pekko://testSystem@myTestHost:1234/user/")

  it should "use defaults for missing configuration settings" in:
    val facade = new RemoteMediaFacadeTestHelper().createFacade()

    val path = facade.createActorPathPrefix(new PropertiesConfiguration)
    path should be("pekko://LineDJ-Server@127.0.0.1:2552/user/")

  /**
    * A test helper class which manages dependencies.
    */
  private class RemoteMediaFacadeTestHelper:
    /** Mock for the relay actor. */
    val relayActor: ActorRef = mock[ActorRef]

    /** Mock for the actor system. */
    val actorSystem: ActorSystem = mock[ActorSystem]

    /** Mock for the message bus. */
    val messageBus: MessageBus = mock[MessageBus]

    /**
      * Creates a new facade test instance.
      *
      * @return the test instance
      */
    def createFacade(): RemoteMediaFacade =
    new RemoteMediaFacade(relayActor, actorSystem, messageBus)

