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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.server.common.{ServerController, ServerLocator}
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.commons.configuration.StrictConfigurationComparator
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.io.File
import java.nio.file.Paths
import scala.concurrent.Future

/**
  * Test class for [[Controller]].
  */
class ControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with OptionValues:
  def this() = this(ActorSystem("ControllerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /** A default test configuration for the server. */
  private val playerConfig = PlayerServerConfig(
    radioPlayerConfig = mock[RadioPlayerConfig],
    sourceConfig = mock[RadioSourceConfig],
    metadataConfig = mock[MetadataConfig],
    serverPort = 7000,
    lookupPort = 7777,
    lookupMulticastAddress = "231.1.2.3",
    lookupCommand = "hello",
    uiContentFolder = Paths.get("someUiFolder"),
    optUiContentResource = None,
    uiPath = "/ui/index.html",
    optCurrentConfig = None,
    optShutdownCommand = None
  )

  /**
    * Checks whether the given configuration matches the expected one. Since
    * there are some expected differences, this cannot be done via a direct
    * comparison.
    *
    * @param serverConfig   the configuration to check
    * @param expectedConfig the expected configuration
    * @return the result of the check
    */
  private def checkConfig(serverConfig: PlayerServerConfig, expectedConfig: PlayerServerConfig): Assertion =
    val modifiedPlayerConfig = serverConfig.radioPlayerConfig.playerConfig.copy(actorFactory = null)
    val modifiedRadioConfig = serverConfig.radioPlayerConfig.copy(playerConfig = modifiedPlayerConfig)
    val modifiedServerConfig = serverConfig.copy(radioPlayerConfig = modifiedRadioConfig,
      sourceConfig = expectedConfig.sourceConfig,
      metadataConfig = expectedConfig.metadataConfig,
      optCurrentConfig = expectedConfig.optCurrentConfig,
      optShutdownCommand = expectedConfig.optShutdownCommand)
    modifiedServerConfig should be(expectedConfig)
    serverConfig.sourceConfig.sources should be(expectedConfig.sourceConfig.sources)

    serverConfig.optCurrentConfig.isDefined shouldBe expectedConfig.optCurrentConfig.isDefined
    val currentConfigEquals = for
      serverCurrent <- serverConfig.optCurrentConfig
      expectedCurrent <- expectedConfig.optCurrentConfig
    yield
      new StrictConfigurationComparator().compare(serverCurrent, expectedCurrent)
    currentConfigEquals.getOrElse(true) shouldBe true

  /**
    * Creates a new object with server services.
    *
    * @return the object with services
    */
  private def createServices(): ServerController.ServerServices =
    ServerController.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)

  /**
    * Creates a [[Controller]] for tests based on the given parameters.
    *
    * @param serviceFactory the [[ServiceFactory]] for the controller
    * @param configFileName the name of the configuration file
    * @return the test controller
    */
  private def createController(serviceFactory: ServiceFactory,
                               configFileName: Option[String] = None): Controller =
    new Controller(serviceFactory) with SystemPropertyAccess:
      override def getSystemProperty(key: String): Option[String] =
        key should be(Controller.PropConfigFileName)
        configFileName

  "Controller" should "create a configuration from the default location" in :
    val expectedConfig = PlayerServerConfig(PlayerServerConfig.DefaultConfigFileName, null, null)
    val serviceFactory = mock[ServiceFactory]
    doReturn(Future.successful(mock[RadioPlayer])).when(serviceFactory).createRadioPlayer(any())(any())

    val controller = createController(serviceFactory)
    controller.createContext(using createServices()) map : context =>
      checkConfig(context.config, expectedConfig)

  it should "create a configuration from an alternative location" in :
    val alternativeConfigName = "test-server-config.xml"
    val expectedConfig = PlayerServerConfig(alternativeConfigName, null, null)
    val serviceFactory = mock[ServiceFactory]
    doReturn(Future.successful(mock[RadioPlayer])).when(serviceFactory).createRadioPlayer(any())(any())

    val controller = createController(serviceFactory, Some(alternativeConfigName))
    controller.createContext(using createServices()) map : context =>
      checkConfig(context.config, expectedConfig)

  it should "store the correct actor factory in the configuration" in :
    val serviceFactory = mock[ServiceFactory]
    doReturn(Future.successful(mock[RadioPlayer])).when(serviceFactory).createRadioPlayer(any())(any())
    val services = createServices()

    val controller = createController(serviceFactory)
    controller.createContext(using services) map : context =>
      context.config.radioPlayerConfig.playerConfig.actorFactory should be(services.managingActorFactory)

  it should "create a radio player" in :
    val radioPlayer = mock[RadioPlayer]
    val serviceFactory = mock[ServiceFactory]
    doReturn(Future.successful(radioPlayer)).when(serviceFactory).createRadioPlayer(any())(any())

    val controller = createController(serviceFactory = serviceFactory)
    controller.createContext(using createServices()) map : context =>
      verify(serviceFactory).createRadioPlayer(context.config)(system)
      context.radioPlayer should be(radioPlayer)

  it should "create correct server parameters" in :
    val serviceFactory = mock[ServiceFactory]
    doReturn(Future.successful(mock[RadioPlayer])).when(serviceFactory).createRadioPlayer(any())(any())
    val serverContext = Controller.PlayerServerContext(playerConfig, mock[RadioPlayer])

    val controller = createController(serviceFactory)
    controller.serverParameters(serverContext)(using createServices()) map : parameters =>
      parameters.bindingParameters.bindInterface should be("0.0.0.0")
      parameters.bindingParameters.bindPort should be(playerConfig.serverPort)
      val locatorParams = parameters.optLocatorParams.value
      locatorParams.multicastAddress should be(playerConfig.lookupMulticastAddress)
      locatorParams.port should be(playerConfig.lookupPort)
      locatorParams.requestCode should be(playerConfig.lookupCommand)
      locatorParams.responseTemplate should be(s"http://${ServerLocator.PlaceHolderAddress}:7000/ui/index.html")

  it should "execute the shutdown command if it is defined" in :
    val serviceFactory = mock[ServiceFactory]
    doReturn(Future.successful(mock[RadioPlayer])).when(serviceFactory).createRadioPlayer(any())(any())
    val tempDir = System.getProperty("java.io.tmpdir")
    val subDir = "serverSpecNewTestDirectory" + System.currentTimeMillis()

    // The test shutdown command is OS-specific; so the test is only executed under Linux.
    val optShutdownCommand = if System.getProperty("os.name").contains("Linux") then
      Some(s"mkdir $tempDir/$subDir")
    else None
    val config = playerConfig.copy(optShutdownCommand = optShutdownCommand)
    val serverContext = Controller.PlayerServerContext(config, mock[RadioPlayer])

    val controller = createController(serviceFactory)
    controller.afterShutdown(serverContext)

    val createdDir = new File(tempDir, subDir)
    createdDir.isDirectory shouldBe true
    createdDir.delete() shouldBe true
