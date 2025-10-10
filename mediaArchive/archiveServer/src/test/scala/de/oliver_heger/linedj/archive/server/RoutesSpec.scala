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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

object RoutesSpec:
  /** The configuration used by tests per default. */
  private val TestServerConfig = ArchiveServerConfig(0, ArchiveServerConfig.DefaultServerTimeout, Nil)
end RoutesSpec

/**
  * Test class for the routes of the archive server.
  */
class RoutesSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers with ScalatestRouteTest
  with ArchiveModel.ArchiveJsonSupport:
  /** The test kit for typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  import RoutesSpec.*

  /**
    * Returns a [[Route]] to be tested based on the given content actor. This
    * function obtains the route from the [[Controller]], so that the 
    * controller method creating the route is tested as well.
    *
    * @param contentActor the content actor to use
    * @return the [[Route]] for being tested
    */
  private def testRoute(contentActor: ActorRef[ArchiveContentActor.ArchiveContentCommand],
                        config: ArchiveServerConfig = TestServerConfig): Route =
    val controller = new Controller() with SystemPropertyAccess {}
    val context = Controller.ArchiveServerContext(
      serverConfig = config,
      contentActor = contentActor
    )
    val services = ServerController.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)
    val shutdownPromise = Promise[Done]()
    controller.route(context, shutdownPromise)(using services)

  "Routes" should "define a route for obtaining an overview over all media" in :
    val mediaOverview = List(
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("c1"), "testMedium1"),
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("c2"), "testMedium2"),
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("c3"), "testMedium3")
    )
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case ArchiveContentActor.ArchiveContentCommand.GetMedia(replyTo) =>
        replyTo ! ArchiveContentActor.GetMediaResponse(mediaOverview)
        Behaviors.same

    val contentActor = testKit.spawn(contentBehavior)
    Get("/api/archive/media") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val actualMedia = responseAs[ArchiveModel.MediaOverview]
      actualMedia.media should contain theSameElementsAs mediaOverview

  it should "respect the configured timeout" in :
    val config = ArchiveServerConfig(
      serverPort = 8080,
      timeout = 10.millis,
      archiveConfigs = Nil
    )
    val contentBehavior = Behaviors.receivePartial[ArchiveContentActor.ArchiveContentCommand]:
      case (context, ArchiveContentActor.ArchiveContentCommand.GetMedia(replyTo)) =>
        context.scheduleOnce(500.millis, replyTo, ArchiveContentActor.GetMediaResponse(Nil))
        Behaviors.same

    val contentActor = testKit.spawn(contentBehavior)
    Get("/api/archive/media") ~> testRoute(contentActor, config) ~> check:
      status should be(StatusCodes.InternalServerError)
