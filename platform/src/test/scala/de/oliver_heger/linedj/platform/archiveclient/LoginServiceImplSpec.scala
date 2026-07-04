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

package de.oliver_heger.linedj.platform.archiveclient

import de.oliver_heger.linedj.archive.server.cloud.model.CloudArchiveModel
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.marshalling.{Marshal, Marshaller}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import spray.json.enrichAny

import scala.concurrent.Future

class LoginServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar, CloudArchiveModel.CloudArchiveJsonSupport:
  def this() = this(ActorSystem("LoginServiceImplSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Creates a response containing the given data entity.
    *
    * @param data the data for the response entity
    * @param m    the marshaller
    * @tparam T the type of the data
    * @return a [[Future]] with the response
    */
  private def createResponse[T](data: T)(using m: Marshaller[T, HttpResponse]): Future[HttpResponse] =
    Marshal(data).to[HttpResponse]

  "A LoginServiceImpl" should "return information about credentials" in :
    val credentialsInfo = CloudArchiveModel.CredentialsInfo(
      fileCredentials = Set("file1", "anotherFile"),
      archiveCredentials = Set("arc1.username", "arc1.password", "arc2.crypt", "test")
    )
    val helper = new ServiceTestHelper

    helper.expectQuery("/api/archive/credentials", credentialsInfo)
      .loginService.credentialsInfo map : result =>
      result should be(credentialsInfo)

  it should "return information about the archive status" in :
    val status = CloudArchiveModel.CloudArchiveStateResponse(
      waitingArchives = Set("notLoadedArchive"),
      loadedArchives = Set("liveMusic", "musicOnline", "radioActive"),
      failedArchives = Set(
        CloudArchiveModel.FailedArchive("errorArchive", "Could not load", 2)
      )
    )
    val helper = new ServiceTestHelper

    helper.expectQuery("/api/archive/archives/status", status)
      .loginService.cloudArchiveState map : result =>
      result should be(status)

  it should "support setting credentials" in :
    val credentials = Map("cred1" -> "secret1", "anotherCred" -> "verySecret")
    val credentialsJson = credentials.toJson.compactPrint
    val request = HttpRequest(
      uri = "/api/archive/credentials",
      method = HttpMethods.PUT,
      entity = HttpEntity(ContentTypes.`application/json`, credentialsJson)
    )
    val nextCredentialsInfo = CloudArchiveModel.CredentialsInfo(
      fileCredentials = Set("file1", "anotherFile"),
      archiveCredentials = Set("arc1.username", "arc1.password", "arc2.crypt", "test")
    )
    val helper = new ServiceTestHelper

    helper.expectRequest(request, nextCredentialsInfo)
      .loginService.setCredentials(credentials) map : result =>
      result should be(nextCredentialsInfo)

  /**
    * A test helper class managing a test service and its dependencies.
    */
  private class ServiceTestHelper:
    /** A mock for the archive service. */
    private val mockArchiveService: ArchiveService = mock[ArchiveService]

    /** The service to be tested. */
    val loginService: LoginServiceImpl = createLoginService()

    /**
      * Prepares the mock archive service to expect a query for a specific URI
      * and return the given result for this query.
      *
      * @param uri          the URI of the expected query
      * @param result       the result to return
      * @param unmarshaller the unmarshaller for this data
      * @tparam A the type of the result data
      * @return this test helper
      */
    def expectQuery[A](uri: String, result: A)(using unmarshaller: Unmarshaller[HttpResponse, A]): ServiceTestHelper =
      when(mockArchiveService.queryData[A](argEq(uri))(using any())).thenReturn(Future.successful(result))
      this

    /**
      * Prepares the mock archive service to expect the given request which is
      * then to be answered by a success response containing the given data.
      *
      * @param request the expected request
      * @param data    the data for the response
      * @param m       the marshaller to create the response
      * @tparam A the type of the data in the response
      * @return this test helper
      */
    def expectRequest[A](request: HttpRequest, data: A)(using m: Marshaller[A, HttpResponse]): ServiceTestHelper =
      val futResponse = Marshal(data).to[HttpResponse]
      when(mockArchiveService.sendRequest(request)).thenReturn(futResponse)
      this

    /**
      * Creates a service instance to be tested.
      *
      * @return the test service instance
      */
    private def createLoginService(): LoginServiceImpl =
      LoginServiceImpl.newInstance(mockArchiveService)
      