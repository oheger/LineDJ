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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.server.{ArchiveController, ArchiveServerConfig}
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
  * Test class for the additional routes provided by the [[Controller]] class.
  */
class CloudRoutesSpec extends AnyFlatSpec, BeforeAndAfterEach, Matchers, ScalatestRouteTest, MockitoSugar,
  FileTestHelper, CloudArchiveModel.CloudArchiveJsonSupport:
  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  /**
    * Returns the route to be tested. It is created from a [[Controller]]
    * instance with the provided parameters.
    *
    * @param credentialsManager the manager for credentials
    * @return the route to be tested
    */
  private def testRoute(credentialsManager: CloudArchiveCredentialsManager = mock): Route =
    val serverConfig = CloudArchiveServerConfig(
      archives = List.empty,
      credentialsDirectory = testDirectory,
      cacheDirectory = Paths.get("some", "path")
    )
    val config = ArchiveServerConfig(8080, 30.seconds, serverConfig)
    val cloudContext = Controller.CloudArchiveServerContext(
      archiveManager = mock,
      credentialsManager = credentialsManager
    )
    val context = ArchiveController.ArchiveServerContext(
      serverConfig = config,
      contentActor = mock,
      customContext = cloudContext
    )
    given ServerController.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)
    val controller = new Controller with SystemPropertyAccess {}
    controller.customRoute(context).get

  "Cloud routes" should "return information about pending credentials" in :
    val credentialsManager = mock[CloudArchiveCredentialsManager]
    val credentialKeys = Set(
      CloudArchiveCredentialsManager.CredentialKey("file1", CloudArchiveCredentialsManager.CredentialKeyType.File),
      CloudArchiveCredentialsManager.CredentialKey("file2", CloudArchiveCredentialsManager.CredentialKeyType.File),
      CloudArchiveCredentialsManager.CredentialKey("user", CloudArchiveCredentialsManager.CredentialKeyType.Archive),
      CloudArchiveCredentialsManager.CredentialKey("pwd", CloudArchiveCredentialsManager.CredentialKeyType.Archive),
    )
    when(credentialsManager.pendingCredentials).thenReturn(Future.successful(credentialKeys))

    Get("/credentials") ~> testRoute(credentialsManager) ~> check:
      status should be(StatusCodes.OK)
      val expectedInfo = CloudArchiveModel.CredentialsInfo(
        fileCredentials = Set("file1", "file2"),
        archiveCredentials = Set("user", "pwd")
      )
      responseAs[CloudArchiveModel.CredentialsInfo] should be(expectedInfo)
      