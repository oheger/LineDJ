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

import de.oliver_heger.linedj.server.common.ServerLocator.NetworkManager
import de.oliver_heger.linedj.shared.actors.{ActorManagement, ManagingActorFactory}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.Mockito.*
import org.scalatest.Inspectors.forAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, TryValues}
import org.scalatestplus.mockito.MockitoSugar

import java.net.{DatagramPacket, DatagramSocket, InetAddress, NetworkInterface}
import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Using

object ServerLocatorSpec:
  /** The request code used by the test locator. */
  private val RequestCode = "the_request"

  /** The address of the multicast group. */
  private val GroupAddress = "231.2.3.4"

  /** The name for the test locator. */
  private val LocatorName = "TheTestLocator"

  /**
    * A class simulating a client of the server locator. In a thread, the
    * instance sends a UDP multicast request and waits for the response.
    *
    * @param queue the queue to put the response
    * @param code  the code to send
    * @param port  the port the handler actor is listening
    */
  private class UdpClientThread(queue: BlockingQueue[String],
                                code: String,
                                port: Int) extends Thread:
    /** The socket for UDP communication. */
    private val socket = new DatagramSocket()

    /**
      * Stops the test. Makes sure that the thread is unblocked if it is still
      * waiting on the socket.
      */
    def stopTest(): Unit =
      closeSocket(socket)

    override def run(): Unit =
      Using(socket): sock =>
        val msgCode = requestCode
        val packet = new DatagramPacket(msgCode.getBytes,
          msgCode.length,
          InetAddress.getByName(GroupAddress),
          port)
        sock.send(packet)

        receiveResponse(sock)

    /**
      * Returns the code to be sent in the request message.
      *
      * @return the code for the request message
      */
    protected def requestCode: String = code

    /**
      * Uses the given socket to receive a response from the server. If this
      * succeeds, the response message is added to the queue.
      *
      * @param socket the socket for receiving
      */
    protected def receiveResponse(socket: DatagramSocket): Unit =
      val buf = new Array[Byte](256)
      val receivePacket = new DatagramPacket(buf, buf.length)
      socket.receive(receivePacket)
      val response = new String(receivePacket.getData, 0, receivePacket.getLength)
      queue offer response

    /**
      * Closes the given socket if it is not yet closed.
      *
      * @param sock the socket to close
      */
    protected def closeSocket(sock: DatagramSocket): Unit =
      if !sock.isClosed then
        sock.close()
  end UdpClientThread

  /**
    * An alternative client implementation for the test locator. This
    * implementation uses a different socket for receiving responses than for
    * sending requests. This is used to test whether the receiver port can be
    * specified in the request message.
    *
    * @param queue the queue to put the response
    * @param code  the code to send
    * @param port  the port the handler actor is listening
    */
  private class UdpClientThreadWithReceiverSocket(queue: BlockingQueue[String],
                                                  code: String,
                                                  port: Int) extends UdpClientThread(queue, code, port):
    /** The socket to be used for receiving a response. */
    private val receiveSocket = new DatagramSocket

    override def stopTest(): Unit =
      closeSocket(receiveSocket)
      super.stopTest()

    override protected def requestCode: String = s"$code:${receiveSocket.getLocalPort}"

    override protected def receiveResponse(socket: DatagramSocket): Unit = super.receiveResponse(receiveSocket)
  end UdpClientThreadWithReceiverSocket

  /**
    * A data class to record a schedule operation of the test locator to
    * trigger a bind operation.
    *
    * @param initBinding the message to be scheduled
    * @param delay       the delay for the schedule
    */
  private case class ScheduleBindData(initBinding: ServerLocator.InitBinding,
                                      delay: FiniteDuration)

  /**
    * Creates an object with default parameters for testing a locator.
    *
    * @param port     the port for the locator
    * @param lookFunc the network lookup function
    * @return the parameters object
    */
  private def testLocatorParams(port: Int,
                                lookFunc: ServerLocator.NetworkInterfaceLookupFunc): ServerLocator.LocatorParams =
    ServerLocator.LocatorParams(
      multicastAddress = GroupAddress,
      port = port,
      requestCode = RequestCode,
      responseTemplate = s"http://${ServerLocator.PlaceHolderAddress}:8080/ui/index.html",
      lookupFunc = lookFunc
    )

  /**
    * Invokes the locator factory with default parameters and a specific actor
    * factory.
    *
    * @param actorFactory the actor factory
    * @param port         the port for the locator
    * @return the handle to the new locator
    */
  private def createTestLocator(actorFactory: ManagingActorFactory,
                                port: Int,
                                lookupFunc: ServerLocator.NetworkInterfaceLookupFunc =
                                ServerLocator.NetworkManager.DefaultNetworkInterfaceLookupFunc):
  ServerLocator.LocatorHandle =
    ServerLocator.newLocator(LocatorName, testLocatorParams(port, lookupFunc))(using actorFactory)
end ServerLocatorSpec

/**
  * Test class for functionality provided by the [[ServerLocator]] module.
  */
class ServerLocatorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with TryValues:
  def this() = this(ActorSystem("ServerLocatorSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ServerLocatorSpec.*

  /**
    * Creates a mock for a [[NetworkInterface]] based on the given parameters.
    *
    * @param isUp       flag whether the interface is up
    * @param isLoopBack flag whether this is the loopback interface
    * @param addresses  a list of internet addresses for the interface
    * @return the mock interface with these parameters
    */
  private def createMockInterface(isUp: Boolean = true,
                                  isLoopBack: Boolean = false,
                                  addresses: List[String] = List("192.168.0.1")): NetworkInterface =
    val ifc = mock[NetworkInterface]
    when(ifc.isUp).thenReturn(isUp)
    when(ifc.isLoopback).thenReturn(isLoopBack)
    val addressList = new util.ArrayList[InetAddress]
    addresses.foreach: adr =>
      addressList.add(InetAddress.getByName(adr))
    when(ifc.getInetAddresses).thenReturn(util.Collections.enumeration(addressList))
    ifc

  "NetworkManager.canBindToInterface" should "return true for a usable interface" in :
    val ifc = createMockInterface()

    NetworkManager.canBindToInterface(ifc) shouldBe true

  it should "return false if the interface is not up" in :
    val ifc = createMockInterface(isUp = false)

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return false if the interface is the loopback interface" in :
    val ifc = createMockInterface(isLoopBack = true)

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return false if the interface has no addresses" in :
    val ifc = createMockInterface(addresses = Nil)

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return false if the interface has only IPv6 addresses" in :
    val ifc = createMockInterface(addresses = List("1080:0:0:0:8:800:200C:417A"))

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return true if there is at least one IPv4 address" in :
    val ifc = createMockInterface(addresses = List("1080:0:0:0:8:800:200C:417A", "192.168.0.1"))

    NetworkManager.canBindToInterface(ifc) shouldBe true

  "NetworkManager.filterSupportedInterfaces" should "filter out the correct network interfaces" in :
    val supportedIfc = createMockInterface()
    val interfaces = List(
      createMockInterface(isUp = false),
      createMockInterface(isLoopBack = true),
      supportedIfc,
      createMockInterface(addresses = List("1080:0:0:0:8:800:200C:417A"))
    )

    val supportedInterfaces = NetworkManager.filterSupportedNetworkInterfaces(interfaces.iterator)

    supportedInterfaces should contain only supportedIfc


  /**
    * Executes a test against the test locator using the given client. Checks
    * whether the expected response is received.
    *
    * @param testClient the test client to be used
    */
  private def checkLocatorResponse(testClient: TestClient): Unit =
    Using(testClient) { client =>
      if client.canSendRequest then
        val receivedResponse = client.sendAndExpectResponse()
        receivedResponse should fullyMatch regex "http://(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:8080/ui/index.html"
    }.success

  "ServerLocator" should "answer a request for the endpoint" in :
    checkLocatorResponse(new TestClient)

  it should "support overriding the receiver port in the request" in :
    checkLocatorResponse(new TestClientWithReceiverSocket)

  it should "ignore a request with a wrong code" in :
    Using(new TestClient) { client =>
      if client.canSendRequest then
        client.sendAndExpectNoResponse(RequestCode + "-foo")
    }.success

  it should "ignore a request with an invalid target port" in :
    Using(new TestClient) { client =>
      if client.canSendRequest then
        client.sendAndExpectNoResponse(RequestCode + ":invalidPort")
    }.success

  it should "wait until network interfaces are available" in :
    val interfaces = NetworkManager.DefaultNetworkInterfaceLookupFunc()
    if interfaces.nonEmpty then
      val counter = new AtomicInteger
      val lookupFunc: ServerLocator.NetworkInterfaceLookupFunc = () =>
        if counter.getAndIncrement() > 0 then interfaces
        else List.empty

      val client = createClientForBindTest(
        lookupFunc,
        new LinkedBlockingQueue[ScheduleBindData],
        regularSchedule = true
      )

      checkLocatorResponse(client)

  it should "retry bind operations after correct increasing delays" in :
    val lookupFunc: ServerLocator.NetworkInterfaceLookupFunc = () => Nil
    val scheduleQueue = new LinkedBlockingQueue[ScheduleBindData]

    def nextScheduleBindData(): ScheduleBindData =
      val data = scheduleQueue.poll(3, TimeUnit.SECONDS)
      data should not be null
      data

    @tailrec def checkScheduleIncrement(last: ScheduleBindData): Unit =
      val data = nextScheduleBindData()
      if data.initBinding.nextAttempt < ServerLocator.MaxBindRetryDelay then
        data.initBinding.nextAttempt should be(data.delay * 2)
        data.delay should be(last.initBinding.nextAttempt)
        checkScheduleIncrement(data)

    Using(createClientForBindTest(lookupFunc, scheduleQueue, regularSchedule = false)) { client =>
      val firstSchedule = nextScheduleBindData()
      firstSchedule should be(ScheduleBindData(ServerLocator.InitBinding(2.seconds), 1.second))
      checkScheduleIncrement(firstSchedule)

      val maxScheduleData = ScheduleBindData(
        ServerLocator.InitBinding(ServerLocator.MaxBindRetryDelay),
        ServerLocator.MaxBindRetryDelay
      )
      forAll((1 to 10).map(_ => nextScheduleBindData())) { data =>
        data should be(maxScheduleData)
      }
    }.success

  /**
    * Creates a specialized test client that can be used for testing bind
    * operations. This client creates a test actor with a special network
    * lookup function that uses a queue to record its schedule operations to
    * trigger network bindings.
    *
    * @param lookupFunc      the lookup function for network interfaces
    * @param scheduleQueue   the queue for schedule operations
    * @param regularSchedule flag whether the normal scheduling logic should be
    *                        used; if '''true''', delegation to the base class
    *                        happens; otherwise, the message is sent directly
    *                        to the actor with a minimum delay
    * @return the test client
    */
  private def createClientForBindTest(lookupFunc: ServerLocator.NetworkInterfaceLookupFunc,
                                      scheduleQueue: LinkedBlockingQueue[ScheduleBindData],
                                      regularSchedule: Boolean): TestClient =
    new TestClient:
      override protected def createLocator(): ServerLocator.LocatorHandle =
        val modifiedProps = Props(
          new ServerLocator.ServerLocatorActor(
            testLocatorParams(port, lookupFunc)
          ):
            override def scheduleBind(message: ServerLocator.InitBinding, delay: FiniteDuration): Unit =
              scheduleQueue.offer(ScheduleBindData(message, delay))
              val scheduleDelay = if regularSchedule then delay else 2.millis
              super.scheduleBind(message, scheduleDelay)
        )

        val specialFactory = new ManagingActorFactory:
          export actorFactory.{createClassicActor => _, *}

          override def createClassicActor(props: Props, name: String, optStopCommand: Option[Any]): ActorRef =
            actorFactory.createClassicActor(modifiedProps, name, optStopCommand)

        createTestLocator(specialFactory, port)

  /**
    * A helper class that mimics a client of the UDP actor. It sends a
    * multicast request and listens for a response.
    */
  private class TestClient extends AutoCloseable:
    /** A queue for receiving the response from the actor. */
    private val queue = new LinkedBlockingQueue[String]

    /** The factory for creating actors. */
    protected val actorFactory: ManagingActorFactory = ManagingActorFactory.newDefaultManagingActorFactory

    /** The port number to be used by the actor under test. */
    protected val port: Int = findFreePort()

    /**
      * A list storing the threads that have been created to communicate with
      * a test actor instance. They have to be cleaned up after the test.
      */
    private var clientThreads = List.empty[UdpClientThread]

    /** The handle to the locator to be tested. */
    private val locatorHandle = createLocator()

    /**
      * Checks whether requests can be sent to the test actor. This is only
      * possible if network interfaces are available to which the actor can
      * bind.
      *
      * @return a flag whether a test is possible; '''false''' means that no
      *         network is available
      */
    def canSendRequest: Boolean =
      NetworkManager.DefaultNetworkInterfaceLookupFunc().nonEmpty

    /**
      * Sends a request to the test actor with the given code and expects that
      * a response is received. Since the actor starts asynchronously, it may
      * not be ready yet. Therefore, the function tries multiple times.
      *
      * @param code the code to send in the test request
      * @return the response from the actor under test
      */
    def sendAndExpectResponse(code: String = RequestCode): String =
      @tailrec def trySendAndReceive(attempts: Int): String =
        if attempts <= 0 then null
        else
          val thread = createClientThread(queue, code, port)
          thread.start()
          clientThreads = thread :: clientThreads
          val response = queue.poll(200, TimeUnit.MILLISECONDS)
          if response != null then response
          else trySendAndReceive(attempts - 1)

      val response = trySendAndReceive(16)
      response should not be null
      // Drain the queue.
      while !queue.isEmpty do
        queue.take()
      response

    /**
      * Sends a request to the test actor with the given code and expects that
      * no response comes back. The function sends a normal request first to
      * ensure that the actor is initialized.
      *
      * @param code the code to send in the test request
      */
    def sendAndExpectNoResponse(code: String): Unit =
      sendAndExpectResponse()

      val thread = createClientThread(queue, code, port)
      thread.start()
      clientThreads = thread :: clientThreads
      val response = queue.poll(250, TimeUnit.MILLISECONDS)
      response should be(null)

    /**
      * This implementation stops the client thread if it is running. Also, the
      * test actor instance is shut down.
      */
    override def close(): Unit =
      clientThreads foreach : clientThread =>
        clientThread.stopTest()
        clientThread.join()
      stopHandlerActor()

    /**
      * Creates the thread that handles the UDP communication.
      *
      * @param queue the queue to put the response
      * @param code  the request code to send
      * @param port  the port the handler actor is listening
      * @return the UDP client thread
      */
    protected def createClientThread(queue: LinkedBlockingQueue[String], code: String, port: Int): UdpClientThread =
      new UdpClientThread(queue, code, port)

    /**
      * Creates a locator to be tested and returns the handle to it. The 
      * creation is done via the default factory.
      *
      * @return the handle to the test locator
      */
    protected def createLocator(): ServerLocator.LocatorHandle =
      createTestLocator(actorFactory, port)

    /**
      * Stops the actor under test and waits for it to terminate. Since always
      * the same actor name is used, this is necessary when running multiple
      * tests.
      */
    private def stopHandlerActor(): Unit =
      val handlerActor = actorFactory.getActor(LocatorName)
      handlerActor should not be null

      val probe = TestProbe()
      probe.watch(handlerActor)
      locatorHandle.stop()
      probe.expectTerminated(handlerActor)

      actorFactory.managedActorNames should not contain LocatorName
  end TestClient

  /**
    * An alternative test client implementation that uses a second socket for
    * receiving responses. This is used to test whether responses can be sent
    * to a different port.
    */
  private class TestClientWithReceiverSocket extends TestClient:
    override protected def createClientThread(queue: LinkedBlockingQueue[String],
                                              code: String,
                                              port: Int): UdpClientThread =
      new UdpClientThreadWithReceiverSocket(queue, code, port)
  end TestClientWithReceiverSocket
