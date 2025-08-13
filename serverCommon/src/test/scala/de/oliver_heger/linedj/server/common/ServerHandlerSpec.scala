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
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

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

  "ServerServices" should "provide objects in implicit scope" in :
    val services = ServerHandler.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)
    runTestStream(6)(using services) map : result =>
      result should be(42)  
    