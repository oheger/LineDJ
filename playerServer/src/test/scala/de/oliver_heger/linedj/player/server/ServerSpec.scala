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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.server.ServerConfigTestHelper.getActorManagement
import de.oliver_heger.linedj.utils.{ActorManagement, SystemPropertyAccess}
import org.apache.commons.configuration.StrictConfigurationComparator
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq as eqArgs}
import org.mockito.Mockito.{timeout, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.compatible.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.File
import java.net.InetSocketAddress
import scala.concurrent.{Future, Promise}

/**
  * Test class for [[Server]].
  */
class ServerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike with BeforeAndAfterAll
  with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ServerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  /**
    * Executes a test of the [[Server]] class using a specific configuration
    * file. The function tests whether the correct configuration is constructed
    * and passed to the several factory functions together with the correct
    * parameters.
    *
    * @param expectedConfig the expected configuration
    * @param configFile     an optional alternative config file name if not the
    *                       default name should be used
    * @param shutdownCommand the optional shutdown command to execute
    * @return the ''Future'' with the assertion
    */
  private def runServerTest(expectedConfig: PlayerServerConfig,
                            configFile: Option[String] = None,
                            shutdownCommand: Option[String] = None): Future[Assertion] =
    val serviceFactory = mock[ServiceFactory]
    val server = new Server(serviceFactory) with SystemPropertyAccess:
      override def getSystemProperty(key: String): Option[String] =
        key should be(Server.PropConfigFileName)
        configFile

    val mockPlayer = mock[RadioPlayer]
    val binding = mock[ServerBinding]
    when(binding.localAddress).thenReturn(InetSocketAddress.createUnresolved("127.0.0.1", 8080))
    val bindingFuture = Future.successful(ServiceFactory.ServerStartupData(binding, expectedConfig))
    val promiseTerminated = Promise[Option[String]]()
    when(serviceFactory.createEndpointRequestHandler(any(), any())).thenReturn(TestProbe().ref)
    when(serviceFactory.createRadioPlayer(any())(any())).thenReturn(Future.successful(mockPlayer))
    when(serviceFactory.createHttpServer(any(), any(), any())(any())).thenReturn(bindingFuture)
    when(serviceFactory.enableGracefulShutdown(any(), any(), any())(any())).thenReturn(promiseTerminated.future)

    val runThread = new Thread(() => server.run())
    runThread.start()

    val captEndpointRequestHandlerConfig = ArgumentCaptor.forClass(classOf[PlayerServerConfig])
    verify(serviceFactory, timeout(3000)).createEndpointRequestHandler(captEndpointRequestHandlerConfig.capture(),
      any())
    val captPlayerConfig = ArgumentCaptor.forClass(classOf[PlayerServerConfig])
    verify(serviceFactory, timeout(3000)).createRadioPlayer(captPlayerConfig.capture())(eqArgs(system))
    val captHttpConfig = ArgumentCaptor.forClass(classOf[PlayerServerConfig])
    val captShutdownPromise = ArgumentCaptor.forClass(classOf[Promise[Done]])
    verify(serviceFactory, timeout(3000)).createHttpServer(captHttpConfig.capture(), eqArgs(mockPlayer),
      captShutdownPromise.capture())(eqArgs(system))
    val captStartup = ArgumentCaptor.forClass(classOf[Future[ServiceFactory.ServerStartupData]])
    val captShutdownFuture = ArgumentCaptor.forClass(classOf[Future[Done]])
    val captManagement = ArgumentCaptor.forClass(classOf[ActorManagement])
    verify(serviceFactory, timeout(3000)).enableGracefulShutdown(captStartup.capture(),
      captShutdownFuture.capture(), captManagement.capture())(eqArgs(system))

    promiseTerminated.success(shutdownCommand)
    runThread.join(3000)
    runThread.isAlive shouldBe false

    captStartup.getValue.map { actStartup =>
      actStartup.binding should be(binding)
      val serverConfig = captPlayerConfig.getValue
      captEndpointRequestHandlerConfig.getValue should be(serverConfig)
      captHttpConfig.getValue should be(serverConfig)
      serverConfig.getActorManagement should be(captManagement.getValue)
      checkConfig(serverConfig, expectedConfig)

      captShutdownPromise.getValue.success(Done)
    }.flatMap { _ =>
      captShutdownFuture.getValue map (_ should not be null)
    }

  /**
    * Checks whether the given configuration matches the expected one. Since
    * there are some expected differences, this cannot be done via a direct
    * comparison.
    *
    * @param serverConfig   the configuration to check
    * @param expectedConfig the expected configuration
    */
  private def checkConfig(serverConfig: PlayerServerConfig, expectedConfig: PlayerServerConfig): Unit =
    val modifiedPlayerConfig = serverConfig.radioPlayerConfig.playerConfig.copy(actorCreator = null)
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

  "Server" should "run the server" in {
    val expectedConfig = PlayerServerConfig(PlayerServerConfig.DefaultConfigFileName, null, null)

    runServerTest(expectedConfig)
  }

  it should "run the server with a non-standard configuration file" in {
    val alternativeConfigName = "test-server-config.xml"
    val expectedConfig = PlayerServerConfig(alternativeConfigName, null, null)

    runServerTest(expectedConfig, Some(alternativeConfigName))
  }

  it should "execute the shutdown command" in {
    val alternativeConfigName = "test-server-config.xml"
    val tempDir = System.getProperty("java.io.tmpdir")
    val subDir = "serverSpecNewTestDirectory" + System.currentTimeMillis()

    // The test shutdown command is OS-specific; so the test is only executed under Linux.
    val optShutdownCommand = if System.getProperty("os.name").contains("Linux") then
      Some(s"mkdir $tempDir/$subDir")
    else None
    val expectedConfig = PlayerServerConfig(alternativeConfigName, null, null)

    runServerTest(expectedConfig, Some(alternativeConfigName), optShutdownCommand) map { ass =>
      val createdDir = new File(tempDir, subDir)
      optShutdownCommand match
        case Some(_) =>
          awaitCond(createdDir.isDirectory)
          createdDir.delete() shouldBe true
        case None => ass
    }
  }
