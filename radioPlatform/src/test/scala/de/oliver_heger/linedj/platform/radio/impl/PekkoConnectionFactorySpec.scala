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
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}

object PekkoConnectionFactorySpec:
  /** The timeout for operations that access the test server. */
  private val ServerTimeout = 5.seconds
end PekkoConnectionFactorySpec

/**
  * Test class for the default [[RadioMessageActor.ConnectionFactory]]
  * implementation, which establishes real WebSocket connections via Pekko
  * HTTP. The tests start an embedded HTTP server that exposes a WebSocket
  * endpoint.
  */
class PekkoConnectionFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike,
  BeforeAndAfterAll, Matchers, RadioModel.RadioJsonSupport:
  def this() = this(ActorSystem("PekkoConnectionFactorySpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import PekkoConnectionFactorySpec.*

  "The default connection factory" should "receive and parse messages from the server" in :
    val helper = new ServerTestHelper
    val probe = typedTestKit.createTestProbe[RadioModel.RadioMessage]()
    val connection = RadioMessageActor.defaultConnectionFactory.connect(helper.uri, msg => probe.ref ! msg)
    val message = RadioModel.RadioMessage("TestType", "TestPayload")

    helper.sendMessage(message)

    probe.expectMessage(message)
    connection.close()
    helper.stop()

  it should "ignore messages that cannot be parsed" in :
    val helper = new ServerTestHelper
    val probe = typedTestKit.createTestProbe[RadioModel.RadioMessage]()
    val connection = RadioMessageActor.defaultConnectionFactory.connect(helper.uri, msg => probe.ref ! msg)

    helper.sendRawMessage("This is not valid JSON.")

    probe.expectNoMessage(500.millis)
    connection.close()
    helper.stop()

  it should "close the WebSocket connection to the server" in :
    val helper = new ServerTestHelper
    val connection = RadioMessageActor.defaultConnectionFactory.connect(helper.uri, _ => ())
    helper.awaitConnected()

    connection.close()

    helper.awaitConnectionClosed()
    helper.stop()

  /**
    * A helper class that starts a test server with a WebSocket endpoint and
    * allows sending messages to a connected client.
    */
  private class ServerTestHelper:
    /** A promise for the queue used to send messages to the client. */
    private val queuePromise = Promise[SourceQueueWithComplete[Message]]()

    /** A promise that is completed when the connection is closed. */
    private val connectionClosed = Promise[Unit]()

    /** The route served by the test server. */
    private val route: Route =
      path("api" / "radio" / "events"):
        handleWebSocketMessages(createWebSocketFlow())

    /** The binding of the test server. */
    private val binding: Http.ServerBinding =
      Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), ServerTimeout)

    /** The URI of the WebSocket endpoint of the test server. */
    val uri: Uri = Uri(s"ws://127.0.0.1:${binding.localAddress.getPort}/api/radio/events")

    /** The queue used to send messages to the connected client. */
    private lazy val messageQueue: SourceQueueWithComplete[Message] =
      Await.result(queuePromise.future, ServerTimeout)

    /**
      * Waits until a client has connected to the test server.
      */
    def awaitConnected(): Unit =
      Await.result(queuePromise.future, ServerTimeout)

    /**
      * Sends the given radio message to the connected client.
      *
      * @param message the message to send
      */
    def sendMessage(message: RadioModel.RadioMessage): Unit =
      sendRawMessage(message.toJson.compactPrint)

    /**
      * Sends a raw text message to the connected client.
      *
      * @param json the text to send
      */
    def sendRawMessage(json: String): Unit =
      Await.result(messageQueue.offer(TextMessage(json)), ServerTimeout)

    /**
      * Waits until the connection to the client has been closed.
      */
    def awaitConnectionClosed(): Unit =
      Await.result(connectionClosed.future, ServerTimeout)

    /**
      * Stops the test server.
      */
    def stop(): Unit =
      Await.result(binding.unbind(), ServerTimeout)

    /**
      * Creates the flow that handles a WebSocket connection. Messages to be
      * sent to the client are offered via the materialized queue.
      *
      * @return the flow for the WebSocket endpoint
      */
    private def createWebSocketFlow(): Flow[Message, Message, Any] =
      val sink = Sink.onComplete[Message](_ => connectionClosed.trySuccess(()))
      val source = Source.queue[Message](16, OverflowStrategy.fail)
      Flow.fromSinkAndSourceMat(sink, source)(Keep.right)
        .mapMaterializedValue: queue =>
          queuePromise.trySuccess(queue)
          queue
