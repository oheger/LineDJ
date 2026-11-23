/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.radio.impl

import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.commons.configuration2.{BaseHierarchicalConfiguration, ImmutableHierarchicalConfiguration}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.*
import org.osgi.service.component.ComponentContext
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*

object RadioPlatformComponentSpec:
  /** The section containing the relevant configuration properties. */
  private val Section = "platform.radio"

  /** The section with the properties for discovery. */
  private val DiscoverySection = s"$Section.discovery"

  /** The discovery multicast address used by the tests. */
  private val Address = "231.1.1.0"

  /** The discovery port used by the tests. */
  private val Port = 8765

  /** The discovery code used by the tests. */
  private val Code = "whereAreYou?"

  /**
    * Creates a configuration that contains all mandatory discovery properties.
    *
    * @return the configuration for the test
    */
  private def createMandatoryConfig(): BaseHierarchicalConfiguration =
    val config = new BaseHierarchicalConfiguration
    config.addProperty(s"$DiscoverySection.multicastAddress", Address)
    config.addProperty(s"$DiscoverySection.port", Port)
    config.addProperty(s"$DiscoverySection.requestCode", Code)
    config
end RadioPlatformComponentSpec

/**
  * Test class for [[RadioPlatformComponent]].
  */
class RadioPlatformComponentSpec extends ScalaTestWithActorTestKit, AnyFlatSpecLike, Matchers, MockitoSugar:

  import RadioPlatformComponentSpec.*

  "A RadioPlatformComponent" should "handle a deactivation without an activation" in :
    val componentContext = mock[ComponentContext]
    val helper = new ComponentTestHelper

    helper.deactivate()

    helper.expectNoStop()

  it should "not create a controller for an empty configuration" in :
    val helper = new ComponentTestHelper

    helper.activate(new BaseHierarchicalConfiguration)

    helper.verifyNoController()

  it should "not create a controller without a discovery section" in :
    val helper = new ComponentTestHelper
    val config = new BaseHierarchicalConfiguration
    config.addProperty(s"$Section.requestTimeout", "5s")

    helper.activate(config)

    helper.verifyNoController()

  it should "not create a controller if the multicast address is missing" in :
    val helper = new ComponentTestHelper
    val config = new BaseHierarchicalConfiguration
    config.addProperty(s"$DiscoverySection.port", Port)
    config.addProperty(s"$DiscoverySection.requestCode", Code)

    helper.activate(config)

    helper.verifyNoController()

  it should "not create a controller if the port is missing" in :
    val helper = new ComponentTestHelper
    val config = new BaseHierarchicalConfiguration
    config.addProperty(s"$DiscoverySection.multicastAddress", Address)
    config.addProperty(s"$DiscoverySection.requestCode", Code)

    helper.activate(config)

    helper.verifyNoController()

  it should "not create a controller if the request code is missing" in :
    val helper = new ComponentTestHelper
    val config = new BaseHierarchicalConfiguration
    config.addProperty(s"$DiscoverySection.multicastAddress", Address)
    config.addProperty(s"$DiscoverySection.port", Port)

    helper.activate(config)

    helper.verifyNoController()

  it should "create a controller with default values for optional properties" in :
    val helper = new ComponentTestHelper

    helper.activate(createMandatoryConfig())

    helper.verifyController(
      ServerDiscovery.DiscoveryParams(
        multicastAddress = Address,
        port = Port,
        requestCode = Code
      ),
      Timeout(RadioPlatformComponent.DefaultRequestTimeout)
    )

  it should "create a controller with configured timeouts and backoffs" in :
    val helper = new ComponentTestHelper
    val config = createMandatoryConfig()
    config.addProperty(s"$Section.requestTimeout", "12s")
    config.addProperty(s"$DiscoverySection.timeout", "3s")
    config.addProperty(s"$DiscoverySection.minBackoff", "2s")
    config.addProperty(s"$DiscoverySection.maxBackoff", "1min")

    helper.activate(config)

    helper.verifyController(
      ServerDiscovery.DiscoveryParams(
        multicastAddress = Address,
        port = Port,
        requestCode = Code,
        timeout = 3.seconds,
        minBackoff = 2.seconds,
        maxBackoff = 1.minute
      ),
      Timeout(12.seconds)
    )

  it should "use default values for invalid duration properties" in :
    val helper = new ComponentTestHelper
    val config = createMandatoryConfig()
    config.addProperty(s"$Section.requestTimeout", "invalid")
    config.addProperty(s"$DiscoverySection.timeout", "invalid")
    config.addProperty(s"$DiscoverySection.minBackoff", "invalid")
    config.addProperty(s"$DiscoverySection.maxBackoff", "invalid")

    helper.activate(config)

    helper.verifyController(
      ServerDiscovery.DiscoveryParams(
        multicastAddress = Address,
        port = Port,
        requestCode = Code
      ),
      Timeout(RadioPlatformComponent.DefaultRequestTimeout)
    )

  it should "stop the controller on deactivation" in :
    val helper = new ComponentTestHelper
    helper.activate(createMandatoryConfig())

    helper.deactivate()

    helper.expectStop()

  it should "not stop the controller twice on repeated deactivation" in :
    val helper = new ComponentTestHelper
    helper.activate(createMandatoryConfig())

    helper.deactivate()
    helper.expectStop()
    helper.deactivate()

    helper.expectNoStop()

  /**
    * A test helper class managing a component under test and its dependencies.
    */
  private class ComponentTestHelper:
    /** The mock for the message bus. */
    private val messageBus: MessageBus = mock

    /** The mock for the actor factory. */
    private val actorFactory: ActorFactory = mock

    /** A test probe for the radio controller actor. */
    private val controllerProbe: TestProbe[RadioControllerActor.Command] =
      testKit.createTestProbe[RadioControllerActor.Command]()

    /** The component under test. */
    private val component: RadioPlatformComponent = new RadioPlatformComponent(createControllerFactory())

    /** The number of controller actors created by the component. */
    private var controllerCreationCount = 0

    /**
      * The discovery parameters passed to the controller factory, if a
      * controller was created.
      */
    private var optDiscoveryParams: Option[ServerDiscovery.DiscoveryParams] = None

    /**
      * The timeout passed to the controller factory, if a controller was
      * created.
      */
    private var optTimeout: Option[Timeout] = None

    /**
      * Initializes the component with the given configuration and activates it.
      *
      * @param config the configuration returned by the config service
      * @return this test helper
      */
    def activate(config: ImmutableHierarchicalConfiguration): ComponentTestHelper =
      val configService: ConfigService = mock
      val clientContext: ClientApplicationContext = mock
      when(configService.config).thenReturn(config)
      when(clientContext.actorFactory).thenReturn(actorFactory)
      when(clientContext.messageBus).thenReturn(messageBus)
      component.initConfigService(configService)
      component.initClientContext(clientContext)
      component.activate(mock[ComponentContext])
      this

    /**
      * Deactivates the component under test.
      *
      * @return this test helper
      */
    def deactivate(): ComponentTestHelper =
      component.deactivate(mock[ComponentContext])
      this

    /**
      * Verifies that the component did not create a controller actor.
      *
      * @return this test helper
      */
    def verifyNoController(): ComponentTestHelper =
      controllerCreationCount should be(0)
      this

    /**
      * Verifies that the component created a controller actor with the expected
      * parameters.
      *
      * @param discoveryParams the expected discovery parameters
      * @param timeout         the expected timeout for service requests
      * @return this test helper
      */
    def verifyController(discoveryParams: ServerDiscovery.DiscoveryParams,
                         timeout: Timeout): ComponentTestHelper =
      controllerCreationCount should be(1)
      optDiscoveryParams.get should be(discoveryParams)
      optTimeout.get.duration should be(timeout.duration)
      this

    /**
      * Verifies that the controller actor received a Stop command.
      *
      * @return this test helper
      */
    def expectStop(): ComponentTestHelper =
      controllerProbe.expectMessage(RadioControllerActor.Command.Stop)
      this

    /**
      * Verifies that the controller actor did not receive another Stop command.
      *
      * @return this test helper
      */
    def expectNoStop(): ComponentTestHelper =
      controllerProbe.expectNoMessage(100.millis)
      this

    /**
      * Creates a stub factory for controller actors. The stub checks the known
      * dependencies, records the remaining parameters, and returns a test probe.
      *
      * @return the controller factory for the test
      */
    private def createControllerFactory(): RadioControllerActor.Factory =
      val expectedMessageBus = messageBus
      val expectedActorFactory = actorFactory
      new RadioControllerActor.Factory:
        override def apply(discoveryParams: ServerDiscovery.DiscoveryParams,
                           messageBus: MessageBus,
                           discoveryFactory: ServerDiscovery.Factory,
                           serviceFactory: PlayerServiceImpl.Factory,
                           playerFactory: RadioPlayerActor.Factory)
                          (using actorFactory: ActorFactory,
                           timeout: Timeout): ActorRef[RadioControllerActor.Command] =
          messageBus should be(expectedMessageBus)
          actorFactory should be(expectedActorFactory)
          controllerCreationCount += 1
          optDiscoveryParams = Some(discoveryParams)
          optTimeout = Some(timeout)
          controllerProbe.ref
