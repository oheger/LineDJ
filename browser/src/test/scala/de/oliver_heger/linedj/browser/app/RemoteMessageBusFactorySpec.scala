/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.app

import akka.actor.{ActorRef, ActorSystem, Props}
import de.oliver_heger.linedj.remoting.{ActorFactory, MessageBus, RemoteMessageBus, RemoteRelayActor}
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Matchers.{eq => argEq, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object RemoteMessageBusFactorySpec {
  /** A test server address. */
  private val ServerAddress = "www.linedj.org"

  /** A test server port. */
  private val ServerPort = 4242
}

/**
 * Test class for ''RemoteMessageBusFactory''.
 */
class RemoteMessageBusFactorySpec extends FlatSpec with Matchers with MockitoSugar {

  import RemoteMessageBusFactorySpec._

  "A RemoteMessageBusFactory" should "create a new bus with default settings" in {
    val helper = new BusFactoryTestHelper
    val actor = helper.expectActorCreation("127.0.0.1", 2552)

    helper createAndVerifyRemoteMessageBus actor
  }

  it should "read server settings from the configuration" in {
    val helper = new BusFactoryTestHelper
    helper.config.addProperty(RemoteMessageBusFactory.PropServerAddress, ServerAddress)
    helper.config.addProperty(RemoteMessageBusFactory.PropServerPort, ServerPort)
    val actor = helper.expectActorCreation(ServerAddress, ServerPort)

    helper createAndVerifyRemoteMessageBus actor
  }

  it should "discard an already existing remote message bus" in {
    val helper = new BusFactoryTestHelper
    val oldActor = mock[ActorRef]
    val oldBus = mock[RemoteMessageBus]
    when(oldBus.relayActor).thenReturn(oldActor)
    helper.installBean(BrowserApp.BeanRemoteMessageBus, oldBus)
    val actor = helper.expectActorCreation("127.0.0.1", 2552)

    helper createAndVerifyRemoteMessageBus actor
    verify(helper.actorSystem).stop(same(oldActor))
  }

  /**
   * A test helper class managing a bunch of helper objects.
   */
  private class BusFactoryTestHelper {
    /** A mock for the message bus. */
    val messageBus = mock[MessageBus]

    /** The application configuration. */
    val config = new PropertiesConfiguration

    /** A mock for the bean context. */
    val beanContext = mock[BeanContext]

    /** A mock for the actor system. */
    val actorSystem = mock[ActorSystem]

    /** A mock for the actor factory. */
    val actorFactory = createActorFactory()

    /** A mock for the application context. */
    val applicationContext = createContext()

    /**
     * Installs a bean in the mock bean context.
     * @param name the bean name
     * @param bean the bean instance
     */
    def installBean(name: String, bean: AnyRef): Unit = {
      when(beanContext.containsBean(name)).thenReturn(true)
      doReturn(bean).when(beanContext).getBean(name)
    }

    /**
     * Prepares the mock for the actor system to expect the creation of a
     * remote relay actor with the specified parameters.
     * @param address the server address
     * @param port the server port
     * @return a mock actor reference
     */
    def expectActorCreation(address: String, port: Int): ActorRef = {
      val ref = mock[ActorRef]
      when(actorFactory.createActor(any(classOf[Props]), argEq(RemoteMessageBusFactory.RelayActorName)
      )).thenAnswer(new Answer[ActorRef] {
        override def answer(invocationOnMock: InvocationOnMock): ActorRef = {
          val props = invocationOnMock.getArguments.head.asInstanceOf[Props]
          props should be(RemoteRelayActor(address, port, messageBus))
          ref
        }
      })
      ref
    }

    /**
     * Invokes the factory to create a new bus with the current settings.
     * The resulting instance is verified before it is returned.
     * @param relayActor the expected relay actor
     * @return the created bus
     */
    def createAndVerifyRemoteMessageBus(relayActor: ActorRef): RemoteMessageBus = {
      val factory = new RemoteMessageBusFactory
      val bus = factory recreateRemoteMessageBus applicationContext
      bus.bus should be(messageBus)
      bus.relayActor should be(relayActor)
      bus
    }

    /**
     * Creates the mock application context.
     * @return the mock application context
     */
    private def createContext(): ApplicationContext = {
      installBean(BrowserApp.BeanMessageBus, messageBus)
      installBean(BrowserApp.BeanActorFactory, actorFactory)

      val context = mock[ApplicationContext]
      when(context.getBeanContext).thenReturn(beanContext)
      when(context.getConfiguration).thenReturn(config)
      context
    }

    /**
     * Creates a mock for the actor factory.
     * @return the mock actor factory
     */
    private def createActorFactory(): ActorFactory = {
      val factory = mock[ActorFactory]
      when(factory.actorSystem).thenReturn(actorSystem)
      factory
    }
  }

}
