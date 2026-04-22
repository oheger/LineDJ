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

package de.oliver_heger.linedj.server.discovery

import de.oliver_heger.linedj.server.common.ServerLocator
import de.oliver_heger.linedj.server.common.findFreePort
import de.oliver_heger.linedj.server.discovery.ServerDiscoverySpec.{TestServerResponse, createDiscoveryParams, createLocatorParams}
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import de.oliver_heger.linedj.shared.actors.{ActorFactory, ManagingActorFactory}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, TryValues}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.util.Using

object ServerDiscoverySpec:
  /** The multicast address of the test server. */
  private val TestServerAddress = "231.10.0.7"

  /** The request code expected by the test server. */
  private val TestServerCode = "testServer?!"

  /** The response sent by the test server. */
  private val TestServerResponse = "I am here!"

  /**
    * Creates an object with parameters for the locator using the test values
    * and a random port on which the test server should listen.
    *
    * @param handlerFunc the handler function to use
    * @return the parameters for the locator
    */
  private def createLocatorParams(handlerFunc: ServerLocator.HandlerFunc = ServerLocator.defaultHandler):
  ServerLocator.LocatorParams =
    ServerLocator.LocatorParams(
      multicastAddress = TestServerAddress,
      port = findFreePort(),
      responseTemplate = TestServerResponse,
      requestCode = TestServerCode,
      handlerFunc = handlerFunc
    )

  /**
    * Creates an object with discovery parameters based on the given locator
    * parameters and the specified properties.
    *
    * @param locatorParams the locator parameters
    * @return the discovery parameters
    */
  private def createDiscoveryParams(locatorParams: ServerLocator.LocatorParams): ServerDiscovery.DiscoveryParams =
    ServerDiscovery.DiscoveryParams(
      multicastAddress = TestServerAddress,
      port = locatorParams.port,
      requestCode = TestServerCode
    )
end ServerDiscoverySpec

/**
  * Test class for [[ServerDiscovery]].
  */
class ServerDiscoverySpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll,
  Matchers, TryValues:
  def this() = this(ActorSystem("ServerDiscoverySpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Starts a [[ServerLocator]] with the given parameters and runs a test
    * block that can use it. After the test execution, the locator is stopped.
    *
    * @param name   the name of the locator
    * @param params the parameters for the locator
    * @param test   the test block
    */
  private def runLocatorTest(name: String, params: ServerLocator.LocatorParams)(test: => Unit): Unit =
    val actorFactory = ManagingActorFactory.newDefaultManagingActorFactory
    val locator = ServerLocator.newLocator(name, params)(using actorFactory)
    val closable: AutoCloseable = () => locator.stop()

    runTestWithResource(closable)(test)

  /**
    * Runs a test block that requires a specific resource. Makes sure that the
    * resource is closed after the test execution.
    *
    * @param resource the resource to manage
    * @param test     the test block
    * @tparam T the return type of the test block
    * @return the result of the test block
    */
  private def runTestWithResource[T](resource: AutoCloseable)(test: => T): T =
    Using(resource): _ =>
      test
    .success.value

  "ServerDiscovery" should "discover a server" in :
    val locatorParams = createLocatorParams()
    runLocatorTest("standardDiscovery", locatorParams):
      val handle = ServerDiscovery.discover(createDiscoveryParams(locatorParams))

      runTestWithResource(handle):
        val discoveryResult = new AtomicReference[String]
        handle.futResult.foreach(discoveryResult.set)

        awaitCond(discoveryResult.get() == TestServerResponse)
