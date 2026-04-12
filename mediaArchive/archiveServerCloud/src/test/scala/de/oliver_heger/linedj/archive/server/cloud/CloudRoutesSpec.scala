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
import scala.util.Success

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
    * @param archiveManager     the archive manager
    * @return the route to be tested
    */
  private def testRoute(credentialsManager: CloudArchiveCredentialsManager = mock,
                        archiveManager: CloudArchiveManager = mock): Route =
    val serverConfig = CloudArchiveServerConfig(
      archives = List.empty,
      credentialsDirectory = testDirectory,
      cacheDirectory = Paths.get("some", "path")
    )
    val config = ArchiveServerConfig(8080, 30.seconds, serverConfig)
    val cloudContext = Controller.CloudArchiveServerContext(
      archiveManager = archiveManager,
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

  /**
    * Creates a credentials manager object. This is used to test setting of
    * credential values to test the real interaction with this instance.
    *
    * @return the credentials manager instance
    */
  private def createCredentialsManager(): CloudArchiveCredentialsManager =
    import de.oliver_heger.linedj.archive.cloud.auth.Credentials.queryCredentialTimeout
    CloudArchiveCredentialsManager.newInstance(
      testDirectory,
      ManagingActorFactory.newDefaultManagingActorFactory,
      credentialsActorName = "credentialsManager" + System.nanoTime() // Use a unique actor name.
    )

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

  it should "use correct path prefixes" in :
    Get("/unknown") ~> testRoute() ~> check:
      handled shouldBe false

  it should "support setting credentials" in :
    val setCredentialsBody =
      """[
        |  {
        |    "key": "validKey1",
        |    "value": "secret1"
        |  },
        |  {
        |    "key": "validKey3",
        |    "value": "secret3"
        |  },
        |  {
        |    "key": "otherKey",
        |    "value": "ultra-secret"
        |  }
        |]""".stripMargin
    val credentialsManager = createCredentialsManager()
    credentialsManager.resolverFunc("validKey1")
    credentialsManager.resolverFunc("validKey2")
    val futKey3 = credentialsManager.resolverFunc("validKey3")

    Put("/credentials", setCredentialsBody) ~> testRoute(credentialsManager) ~> check:
      status should be(StatusCodes.OK)
      val expectedInfo = CloudArchiveModel.CredentialsInfo(
        fileCredentials = Set.empty,
        archiveCredentials = Set("validKey2")
      )
      val expectedResponse = CloudArchiveModel.SetCredentialsResponse(
        invalidKeys = Set("otherKey"),
        info = expectedInfo
      )
      responseAs[CloudArchiveModel.SetCredentialsResponse] should be(expectedResponse)
      futKey3.value.map(_.map(_.secret)) should be(Some(Success("secret3")))

  it should "handle an invalid structure when setting credentials" in :
    val setCredentialsBody =
      """[
        |  {
        |    "foo": "fooValue",
        |    "bar": "barValue
        |  }
        |]""".stripMargin
    val credentialsManager = createCredentialsManager()

    Put("/credentials", setCredentialsBody) ~> testRoute(credentialsManager) ~> check:
      status should be(StatusCodes.BadRequest)

  it should "handle a non-JSON body when setting credentials" in :
    val setCredentialsBody = FileTestHelper.TestData
    val credentialsManager = createCredentialsManager()

    Put("/credentials", setCredentialsBody) ~> testRoute(credentialsManager) ~> check:
      status should be(StatusCodes.BadRequest)

  it should "return information about the current archive state" in :
    val archiveManager = mock[CloudArchiveManager]
    val archivesState = CloudArchiveManager.ArchivesState(
      state = Map(
        "waitingArchive" -> CloudArchiveManager.CloudArchiveState.Waiting,
        "loadedArchive1" -> CloudArchiveManager.CloudArchiveState.Loaded(mock),
        "loadedArchive2" -> CloudArchiveManager.CloudArchiveState.Loaded(mock),
        "failedArchive" -> CloudArchiveManager.CloudArchiveState.Failure(
          exception = new IllegalStateException("Test exception: Could not load archive."),
          attempts = 11
        )
      )
    )
    when(archiveManager.archivesState).thenReturn(Future.successful(archivesState))

    Get("/archives/status") ~> testRoute(archiveManager = archiveManager) ~> check:
      val expectedStatus = CloudArchiveModel.CloudArchiveStateResponse(
        waitingArchives = Set("waitingArchive"),
        loadedArchives = Set("loadedArchive1", "loadedArchive2"),
        failedArchives = Set(
          CloudArchiveModel.FailedArchive(
            name = "failedArchive",
            failure = "IllegalStateException: Test exception: Could not load archive.",
            attempts = 11
          )
        )
      )
      status should be(StatusCodes.OK)
      responseAs[CloudArchiveModel.CloudArchiveStateResponse] should be(expectedStatus)
