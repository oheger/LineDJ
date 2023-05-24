/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.{ActorRef, ActorSystem}
import akka.io.Udp
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, TryValues}

import java.net.{DatagramPacket, DatagramSocket, InetAddress, ServerSocket}
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.util.Using

object EndpointRequestHandlerActorSpec {
  /** The request code used by the test actor. */
  private val RequestCode = "the_request"

  /** The response to be sent by the test actor. */
  private val Response = "the_response"

  /** The address of the multicast group. */
  private val GroupAddress = "231.2.3.4"

  /**
    * Determines a free port number that can be used by the test server.
    *
    * @return the free port number
    */
  private def findFreePort(): Int =
    Using(new ServerSocket(0)) {
      _.getLocalPort
    }.get

  /**
    * A class simulating a client of the request handler actor. In a thread,
    * the instance sends a UDP multicast request and waits for the response.
    * @param queue the queue to put the response
    * @param code the code to send
    * @param port the port the handler actor is listening
    */
  private class UdpClientThread(queue: BlockingQueue[String],
                                code: String,
                                port: Int) extends Thread {
    /** The socket for UDP communication. */
    private val socket = new DatagramSocket()

    /**
      * Stops the test. Makes sure that the thread is unblocked if it is still
      * waiting on the socket.
      */
    def stopTest(): Unit = {
      if(!socket.isClosed) {
        socket.close()
      }
    }

    override def run(): Unit = {
      Using(socket) { socket =>
        val packet = new DatagramPacket(code.getBytes,
          code.length,
          InetAddress.getByName(GroupAddress),
          port)
        socket.send(packet)

        val buf = new Array[Byte](256)
        val receivePacket = new DatagramPacket(buf, buf.length)
        socket.receive(receivePacket)
        val response = new String(receivePacket.getData, 0, receivePacket.getLength)
        queue offer response
      }
    }
  }
}

/**
  * Test class for [[EndpointRequestHandlerActor]]. Note: This test class
  * partly requires network functionality. If no network is available, the test
  * cases are skipped.
  */
class EndpointRequestHandlerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
with BeforeAndAfterAll with Matchers with TryValues {
  def this() = this(ActorSystem("EndpointRequestHandlerActorSpec"))

  /** The test kit for typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import EndpointRequestHandlerActorSpec._

  "EndpointRequestHandlerActor" should "answer a request for the endpoint" in {
    Using(new TestClient) { client =>
      if(client.sendRequest()) {
        client.expectResponse() should be(Response)
      }
    }.success
  }

  it should "ignore a request with a wrong code" in {
    Using(new TestClient) { client =>
      if(client.sendRequest("unexpected request")) {
        client.expectNoResponse()
      }
    }.success
  }

  it should "stop itself on receiving an Unbind message" in {
    Using(new TestClient) { client =>
      if (client.sendRequest()) {
        client.handlerActor ! Udp.Unbind

        val probe = TestProbe()
        probe.watch(client.handlerActor)
        probe.expectTerminated(client.handlerActor)
      }
    }.success
  }

  /**
    * A helper class that mimics a client of the UDP actor. It sends a
    * multicast request and listens for a response.
    */
  class TestClient extends AutoCloseable {
    /** A queue for receiving the response from the actor. */
    private val queue = new LinkedBlockingQueue[String]

    /** Test probe for receiving a ready notification from the handler. */
    private val readyListener = testKit.createTestProbe[EndpointRequestHandlerActor.HandlerReady]()

    /** The port number to be used by the actor under test. */
    private val port = findFreePort()

    /** The thread that does the UDP communication. */
    private var optClientThread: Option[UdpClientThread] = None

    /** The actor to be tested. */
    val handlerActor: ActorRef = createHandlerActor()

    /**
      * Sends the request to the test actor after it
      * @param code the request code to sent to the test actor
      * @return a flag whether a test is possible; '''false''' means that no
      *         network is available
      */
    def sendRequest(code: String = RequestCode): Boolean = {
      val readyMessage = readyListener.expectMessageType[EndpointRequestHandlerActor.HandlerReady]
      if(readyMessage.interfaces.nonEmpty) {
        val thread = createClientThread(code)
        thread.start()
        optClientThread = Some(thread)
        true
      } else false
    }

    /**
      * Expects that a response from the actor has been received. Returns this
      * response.
      * @return the response from the actor under test
      */
    def expectResponse(): String = {
      val response = queue.poll(3, TimeUnit.SECONDS)
      response should not be null
      response
    }

    /**
      * Expects that no response from the actor has been received.
      */
    def expectNoResponse(): Unit = {
      val response = queue.poll(250, TimeUnit.MILLISECONDS)
      response should be(null)
    }

    override def close(): Unit = {
      optClientThread foreach { clientThread =>
        clientThread.stopTest()
        clientThread.join()
      }
    }

    /**
      * Creates the thread that handles the UDP communication.
      * @param code the request code to send
      * @return the UDP client thread
      */
    private def createClientThread(code: String): UdpClientThread =
      new UdpClientThread(queue, code, port)

    /**
      * Creates the actor to be tested.
      * @return the test actor instance
      */
    private def createHandlerActor(): ActorRef =
      system.actorOf(EndpointRequestHandlerActor.props(GroupAddress, port, RequestCode, Response,
        Some(readyListener.ref)))
  }
}
