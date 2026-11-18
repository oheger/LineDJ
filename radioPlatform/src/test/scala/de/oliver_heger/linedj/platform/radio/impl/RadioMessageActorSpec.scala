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

import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}

object RadioMessageActorSpec:
  /** The URI of the test radio player server. */
  private val ServerUri = "http://radio.example.com"

  /** The expected URI of the WebSocket endpoint. */
  private val ExpectedWebSocketUri = Uri("ws://radio.example.com/api/radio/events")
end RadioMessageActorSpec

/**
  * Test class for [[RadioMessageActor]].
  */
class RadioMessageActorSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar:
  def this() = this(ActorSystem("RadioMessageActorSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import RadioMessageActorSpec.*

  "webSocketUri" should "derive a WebSocket URI from an HTTP server URI" in :
    RadioMessageActor.webSocketUri(ServerUri) should be(ExpectedWebSocketUri)

  it should "derive a secure WebSocket URI from an HTTPS server URI" in :
    RadioMessageActor.webSocketUri("https://radio.example.com") should be(
      Uri("wss://radio.example.com/api/radio/events"))

  it should "keep the URI if it does not use HTTP or HTTPS" in :
    RadioMessageActor.webSocketUri("ws://radio.example.com") should be(
      Uri("ws://radio.example.com/api/radio/events"))

  "RadioMessageActor" should "connect to the WebSocket endpoint of the server" in :
    val helper = new ActorTestHelper(ServerUri)

    helper.connectionFactory.uri should be(ExpectedWebSocketUri)

  it should "forward received messages to the listener" in :
    val helper = new ActorTestHelper(ServerUri)
    val message = RadioModel.RadioMessage("TestType", "TestPayload")

    helper.connectionFactory.sendMessage(message)

    helper.listenerProbe.expectMessage(message)

  it should "close the WebSocket connection when stopped" in :
    val helper = new ActorTestHelper(ServerUri)

    helper.actor ! RadioMessageActor.Stop

    verify(helper.connection, timeout(5000)).close()

  /**
    * A test helper class managing the actor under test and its dependencies.
    *
    * @param serverUri the URI of the radio player server
    */
  private class ActorTestHelper(serverUri: String):
    /** A probe for the listener of radio messages. */
    val listenerProbe: TestProbe[RadioModel.RadioMessage] =
      typedTestKit.createTestProbe[RadioModel.RadioMessage]()

    /** A mock for the WebSocket connection. */
    val connection: RadioMessageActor.Connection = mock[RadioMessageActor.Connection]

    /** The factory for the WebSocket connection. */
    val connectionFactory: TestConnectionFactory = new TestConnectionFactory(connection)

    /** The actor under test. */
    val actor: ActorRef[RadioMessageActor.Command] =
      typedTestKit.spawn(RadioMessageActor.newInstance(serverUri, listenerProbe.ref, connectionFactory))

    connectionFactory.awaitConnected()

  /**
    * A stub [[RadioMessageActor.ConnectionFactory]] that records the URI of
    * the connection and simulates the delivery of messages.
    *
    * @param connection the connection to return
    */
  private class TestConnectionFactory(connection: RadioMessageActor.Connection)
    extends RadioMessageActor.ConnectionFactory:
    /** A latch to wait for the connection to be established. */
    private val connectLatch = new CountDownLatch(1)

    /** The handler for messages received from the server. */
    private val handlerRef = new AtomicReference[RadioModel.RadioMessage => Unit]

    /** The URI passed to the connection factory. */
    private val uriRef = new AtomicReference[Uri]

    /** The URI that was passed to the connection factory. */
    def uri: Uri = uriRef.get

    override def connect(uri: Uri, onMessage: RadioModel.RadioMessage => Unit)
                        (using system: ActorSystem): RadioMessageActor.Connection =
      uriRef.set(uri)
      handlerRef.set(onMessage)
      connectLatch.countDown()
      connection

    /**
      * Waits until the connection has been established.
      */
    def awaitConnected(): Unit =
      connectLatch.await(5, TimeUnit.SECONDS) should be(true)

    /**
      * Simulates a message received from the radio player server.
      *
      * @param message the message to send
      */
    def sendMessage(message: RadioModel.RadioMessage): Unit =
      handlerRef.get()(message)
