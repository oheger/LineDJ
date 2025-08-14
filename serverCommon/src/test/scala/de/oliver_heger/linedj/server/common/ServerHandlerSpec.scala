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

package de.oliver_heger.linedj.server.common

import de.oliver_heger.linedj.server.common.ServerHandler.given
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Succeeded}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{Future, Promise}

object ServerHandlerSpec:
  /**
    * Runs a stream to verify that the classic actor system and the execution
    * context can be implicitly obtained from a services object.
    *
    * @param elements the number of elements to flow through the stream
    * @param services the services object
    * @return the result of a computation on the stream elements
    */
  private def runTestStream(elements: Int)(using services: ServerHandler.ServerServices): Future[Int] =
    val source = Source(1 to elements)
    val sink = Sink.fold[Int, Int](0)(_ + _)
    source.runWith(sink).map(_ * 2)

  /**
    * Returns a handler instance that can be used to test the default
    * implementations of callback methods. All other functions have dummy
    * implementations.
    *
    * @return the handler instance for testing
    */
  private def createHandler(): ServerHandler =
    new ServerHandler:
      override type Context = String

      /**
        * @inheritdoc This implementation returns a dummy context which is
        *             required to invoke other callback functions.
        */
      override def createContext(using services: ServerHandler.ServerServices): Future[String] =
        Future.successful("myContext")

      override def route(context: String, shutdownPromise: Promise[Done])
                        (using services: ServerHandler.ServerServices): Route =
        throw new UnsupportedOperationException("Unexpected invocation.")
end ServerHandlerSpec

/**
  * Test class for [[ServerHandler]].
  */
class ServerHandlerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("ServerHandlerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ServerHandlerSpec.*

  /**
    * Creates an instance of server services based on the actor system used by
    * this test class.
    *
    * @return the services object
    */
  private def createServices(): ServerHandler.ServerServices =
    ServerHandler.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)

  "ServerServices" should "provide objects in implicit scope" in :
    val services = createServices()
    runTestStream(6)(using services) map : result =>
      result should be(42)

  "ServerHandler" should "return default binding parameters" in :
    val services = createServices()
    val handler = createHandler()

    (for
      context <- handler.createContext(using services)
      parameters <- handler.bindingParameters(context)(using services)
    yield parameters) map : parameters =>
      parameters.bindInterface should be("0.0.0.0")
      parameters.bindPort should be(8080)

  it should "provide an empty afterShutdown callback" in :
    val services = createServices()
    val handler = createHandler()

    handler.createContext(using services) map : context =>
      // It can only be tested that no exception is thrown.
      handler.afterShutdown(context)
      Succeeded
