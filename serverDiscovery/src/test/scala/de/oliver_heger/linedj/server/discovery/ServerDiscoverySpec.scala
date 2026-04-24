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

import de.oliver_heger.linedj.server.common.{ServerLocator, findFreePort}
import de.oliver_heger.linedj.server.discovery.ServerDiscoverySpec.{TestServerCode, TestServerResponse, createDiscoveryParams, createLocatorParams}
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import de.oliver_heger.linedj.shared.actors.{ActorFactory, ManagingActorFactory}
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.io.Udp
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, TryValues}

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Promise
import scala.concurrent.duration.{DurationInt, FiniteDuration}
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
    * @param timeout       the timeout parameter
    * @param minBackoff    the minBackoff parameter
    * @param maxBackoff    the maxBackoff parameter
    * @return the discovery parameters
    */
  private def createDiscoveryParams(locatorParams: ServerLocator.LocatorParams,
                                    timeout: FiniteDuration = ServerDiscovery.DefaultTimeout,
                                    minBackoff: FiniteDuration = ServerDiscovery.DefaultMinBackoff,
                                    maxBackoff: FiniteDuration = ServerDiscovery.DefaultMaxBackoff):
  ServerDiscovery.DiscoveryParams =
    ServerDiscovery.DiscoveryParams(
      multicastAddress = TestServerAddress,
      port = locatorParams.port,
      requestCode = TestServerCode,
      timeout = timeout,
      minBackoff = minBackoff,
      maxBackoff = maxBackoff
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

  /**
    * Returns a special handler function that tracks the times when requests
    * are received. This can be used to verify the retry and timeout logic. The
    * function returns a successful response after receiving a configurable
    * number of requests.
    *
    * @param requestTimeQueue the queue to track request times
    * @param ignoredRequests  the number of requests to ignore
    * @return the locator handler function
    */
  private def trackingHandler(requestTimeQueue: LinkedBlockingQueue[Long], ignoredRequests: Int = Int.MaxValue):
  ServerLocator.HandlerFunc =
    val requestCounter = new AtomicInteger(ignoredRequests)
    request =>
      request.requestCode should be(TestServerCode)
      requestTimeQueue.offer(System.nanoTime())
      if requestCounter.decrementAndGet() == 0 then
        ServerLocator.defaultHandler(request)
      else
        None

  /**
    * Reads the next request time from the given tracking queue. If no data is
    * received within a timeout, result is 0 (due to the auto-boxing mechanism)
    * which will lead to test failures in later assertions.
    *
    * @param requestTimeQueue the tracking queue
    * @return the timestamp of the next request
    */
  private def nextRequestTime(requestTimeQueue: LinkedBlockingQueue[Long]): Long =
    requestTimeQueue.poll(3, TimeUnit.SECONDS)

  "ServerDiscovery" should "discover a server" in :
    val locatorParams = createLocatorParams()
    runLocatorTest("standardDiscovery", locatorParams):
      val handle = ServerDiscovery.discover(createDiscoveryParams(locatorParams))

      runTestWithResource(handle):
        val discoveryResult = new AtomicReference[String]
        handle.futResult.foreach(discoveryResult.set)

        awaitCond(discoveryResult.get() == TestServerResponse)

  it should "retry requests that are not answered" in :
    val locatorParams = createLocatorParams(trackingHandler(new LinkedBlockingQueue, 3))
    runLocatorTest("retriedLocator", locatorParams):
      val discoveryParams = createDiscoveryParams(
        locatorParams = locatorParams,
        timeout = 10.millis,
        minBackoff = 1.milli,
        maxBackoff = 5.millis
      )
      val handle = ServerDiscovery.discover(discoveryParams, "retriedDiscovery")

      runTestWithResource(handle):
        val discoveryResult = new AtomicReference[String]
        handle.futResult.foreach(discoveryResult.set)

        awaitCond(discoveryResult.get() == TestServerResponse)

  it should "implement configurable retry logic" in :
    val requestQueue = new LinkedBlockingQueue[Long]
    val locatorParams = createLocatorParams(trackingHandler(requestQueue))
    runLocatorTest("discoveryWithDelayedRetries", locatorParams):
      val discoveryParams = createDiscoveryParams(
        locatorParams = locatorParams,
        timeout = 10.millis,
        minBackoff = 25.millis,
        maxBackoff = 1.second
      )
      val handle = ServerDiscovery.discover(discoveryParams, "retryDelaysDiscovery")

      runTestWithResource(handle):
        val startTime = nextRequestTime(requestQueue)
        (1 to 4).foldLeft(startTime): (lastTime, index) =>
          val requestTime = nextRequestTime(requestQueue)
          val delay = requestTime - lastTime
          val expectedDelay = TimeUnit.MILLISECONDS.toNanos(index * 10 + 25 * math.pow(2, index - 1).toLong)
          withClue(s"Checking expected delay $expectedDelay in iteration $index."):
            delay should be >= expectedDelay
            (delay - expectedDelay) should be < TimeUnit.MILLISECONDS.toNanos(50)
          requestTime

  it should "correctly apply the maxBackoff parameter" in :
    val requestQueue = new LinkedBlockingQueue[Long]
    val locatorParams = createLocatorParams(trackingHandler(requestQueue))
    runLocatorTest("discoveryWithMaxBackoff", locatorParams):
      val discoveryParams = createDiscoveryParams(
        locatorParams = locatorParams,
        timeout = 10.millis,
        minBackoff = 25.millis,
        maxBackoff = 50.millis
      )
      // Explicitly do not provide a custom name. This tests that actors are stopped correctly.
      val handle = ServerDiscovery.discover(discoveryParams)

      runTestWithResource(handle):
        val startTime = nextRequestTime(requestQueue)
        (1 to 5).foldLeft(startTime): (lastTime, index) =>
          val requestTime = nextRequestTime(requestQueue)
          val delay = requestTime - lastTime
          withClue(s"Checking delay $delay in iteration $index."):
            delay should be < TimeUnit.MILLISECONDS.toNanos(150)
          requestTime

  it should "stop sending requests after receiving a response" in:
    val requestQueue = new LinkedBlockingQueue[Long]
    val locatorParams = createLocatorParams(trackingHandler(requestQueue, ignoredRequests = 1))
    runLocatorTest("discoveryFinished", locatorParams):
      val discoveryParams = createDiscoveryParams(
        locatorParams = locatorParams,
        timeout = 10.millis,
        minBackoff = 25.millis
      )

      val handle = ServerDiscovery.discover(discoveryParams, "finishedDiscovery")

      runTestWithResource(handle):
        nextRequestTime(requestQueue)
        requestQueue.poll(250, TimeUnit.MILLISECONDS) should be(null: Any)

  "UdpRequestActor" should "close the socket when it terminates" in :
    val probeUdpActor = TestProbe()
    val probeSocketActor = TestProbe()
    val discoveryParams = createDiscoveryParams(createLocatorParams())
    val props = Props(new ServerDiscovery.UdpRequestActor(probeUdpActor.ref, discoveryParams, Promise()))
    val requestActor = system.actorOf(props)

    probeUdpActor.expectMsgType[Udp.Bind]
    probeSocketActor.send(requestActor, Udp.Bound(new InetSocketAddress(8765)))
    probeSocketActor.expectMsgType[Udp.Send]
    system.stop(requestActor)

    probeSocketActor.expectMsg(Udp.Unbind)

  it should "close the socket when it restarts" in :
    val probeUdpActor = TestProbe()
    val probeSocketActor = TestProbe()
    val discoveryParams = createDiscoveryParams(createLocatorParams(), timeout = 5.millis)
    val props = Props(new ServerDiscovery.UdpRequestActor(probeUdpActor.ref, discoveryParams, Promise()))
    val requestActor = system.actorOf(props)

    probeUdpActor.expectMsgType[Udp.Bind]
    probeSocketActor.send(requestActor, Udp.Bound(new InetSocketAddress(8765)))
    probeSocketActor.expectMsgType[Udp.Send]

    probeSocketActor.expectMsg(Udp.Unbind)
    probeUdpActor.expectMsgType[Udp.Bind]
    probeSocketActor.send(requestActor, Udp.Bound(new InetSocketAddress(8764)))
    probeSocketActor.expectMsgType[Udp.Send]

  it should "handle a timeout before the socket has been opened" in :
    val probeUdpActor = TestProbe()
    val discoveryParams = createDiscoveryParams(createLocatorParams(), timeout = 5.millis)
    val props = Props(new ServerDiscovery.UdpRequestActor(probeUdpActor.ref, discoveryParams, Promise()))
    val requestActor = system.actorOf(props)
    probeUdpActor.expectMsgType[Udp.Bind]

    probeUdpActor.expectMsgType[Udp.Bind]
