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

package de.oliver_heger.linedj.server.common

import de.oliver_heger.linedj.server.common.ConfigSupport.ConfigLoader
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * Test class for [[ConfigSupport]].
  */
class ConfigSupportSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar:
  def this() = this(ActorSystem("ConfigSupportSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "ConfigSupport" should "create a correct context" in :
    val helper = new ControllerTestHelper

    helper.controller.createContext(using helper.services) map : context =>
      context.serverConfig.httpPort should be(8077)
      context.config should be(42)
      context.context should be("testCustomContext")

  it should "load the configuration from an alternative location" in :
    val ConfigFileName = "test-server-config-with-discovery.xml"
    val propertyAccess = new SystemPropertyAccess:
      override def getSystemProperty(key: String): Option[String] =
        key should be(ConfigSupport.PropConfigFileName)
        Some(ConfigFileName)
    val helper = new ControllerTestHelper(propertyAccess)

    helper.controller.createContext(using helper.services) map : context =>
      context.serverConfig.httpPort should be(8078)
      context.serverConfig.optDiscoveryConfig.isDefined shouldBe true
      context.config should be(42)

  it should "return correct server parameters without discovery" in :
    val helper = new ControllerTestHelper
    val config = ServerConfig(
      httpPort = 8765,
      optDiscoveryConfig = None
    )
    val context = ConfigSupport.ConfigSupportContext(
      serverConfig = config,
      config = 42,
      context = ()
    ).asInstanceOf[helper.controller.Context]

    helper.controller.serverParameters(context)(using helper.services) map : params =>
      params.optLocatorParams shouldBe empty
      params.bindingParameters should be(ServerController.BindingParameters("0.0.0.0", config.httpPort))

  it should "return correct server parameters with discovery" in :
    val helper = new ControllerTestHelper
    val discoveryConfig = DiscoveryConfig(
      multicastAddress = "231.11.12.13",
      port = 7777,
      command = "Discover!"
    )
    val config = ServerConfig(
      httpPort = 8765,
      optDiscoveryConfig = Some(discoveryConfig)
    )
    val context = ConfigSupport.ConfigSupportContext(
      serverConfig = config,
      config = 42,
      context = ()
    ).asInstanceOf[helper.controller.Context]

    helper.controller.serverParameters(context)(using helper.services) map : params =>
      val expectedLocatorParams = ServerLocator.LocatorParams(
        multicastAddress = discoveryConfig.multicastAddress,
        port = discoveryConfig.port,
        requestCode = discoveryConfig.command,
        responseTemplate = s"http://${ServerLocator.PlaceHolderAddress}:${config.httpPort}"
      )
      params.optLocatorParams shouldBe Some(expectedLocatorParams)
      params.bindingParameters should be(ServerController.BindingParameters("0.0.0.0", config.httpPort))

  /**
    * A test helper class that manages the dependencies of the controller to
    * be tested.
    *
    * @param propertyAccess the object to obtain system property values
    */
  private class ControllerTestHelper(propertyAccess: SystemPropertyAccess = new SystemPropertyAccess {}):
    /** The object with services to be consumed by the server. */
    val services: ServerController.ServerServices =
      ServerController.ServerServices(system, mock[ManagingActorFactory])

    /** The controller to be tested. */
    val controller: ConfigSupport = createController()

    /**
      * Creates the object to be tested.
      *
      * @return the test controller object supporting configuration
      */
    private def createController(): ConfigSupport =
      new ServerController with ConfigSupport with SystemPropertyAccess:
        override type CustomConfig = Int

        override type CustomContext = String

        override def route(context: Context, shutdownPromise: Promise[Done])
                          (using services: ServerController.ServerServices): Route =
          throw new UnsupportedOperationException("Unexpected call.")

        override def defaultConfigFileName: String = "test-server-config.xml"

        override def configLoader: ConfigLoader[CustomConfig] = config =>
          Try(config.getInt("test.value"))

        override def createCustomContext(context: ConfigSupport.ConfigSupportContext[CustomConfig, Unit])
                                        (using services: ServerController.ServerServices): Future[CustomContext] =
          context.config should be(42)
          Future.successful("testCustomContext")

        override def getSystemProperty(key: String): Option[String] = propertyAccess.getSystemProperty(key)
                                        