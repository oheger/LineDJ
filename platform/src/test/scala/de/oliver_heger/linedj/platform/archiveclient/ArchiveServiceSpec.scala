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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*

import scala.concurrent.Future

object ArchiveServiceSpec extends DefaultJsonProtocol:
  /**
    * A test data class that is used for serialization tests.
    *
    * @param name   a name
    * @param status a status
    */
  private case class TestData(name: String, status: Boolean)

  private given testDataFormat: RootJsonFormat[TestData] = jsonFormat2(TestData.apply)
end ArchiveServiceSpec

/**
  * Test class for [[ArchiveService]].
  */
class ArchiveServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers:
  def this() = this(ActorSystem("ArchiveServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ArchiveServiceSpec.*

  /**
    * Creates a stub service that expects a specific request to be sent. It
    * returns the given response.
    *
    * @param expectedRequest the expected request
    * @param response        the response to return
    * @return the stub service
    */
  private def createStubArchiveService(expectedRequest: HttpRequest, response: HttpResponse): ArchiveService =
    new ArchiveService:
      override def sendRequest(request: HttpRequest): Future[HttpResponse] =
        request should be(expectedRequest)
        Future.successful(response)

      override protected def actorSystem: ActorSystem = system

  "queryData" should "handle a request correctly" in :
    val uri = "https://test.example.com/data/request"
    val data = TestData("testName", status = true)
    val expectedRequest = HttpRequest(uri = uri)
    val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, data.toJson.prettyPrint))

    val service = createStubArchiveService(expectedRequest, response)
    service.queryData[TestData](uri) map : responseData =>
      responseData should be(data)

  it should "return a failed future if de-serialization fails" in :
    val uri = "https://test.example.com/invalid/data"
    val expectedRequest = HttpRequest(uri = uri)
    val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, "This is not JSON."))

    val service = createStubArchiveService(expectedRequest, response)
    recoverToSucceededIf[JsonParser.ParsingException]:
      service.queryData[TestData](uri)
      