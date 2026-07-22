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

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.archive.server.cloud.model.CloudArchiveModel
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.archiveclient.LoginServiceImplSpec.MonitorTimeout
import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.marshalling.{Marshal, Marshaller}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar
import spray.json.enrichAny

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object LoginServiceImplSpec:
  /** Test backoff configuration to monitor the archive for changes. */
  private val MonitorBackoff = BackoffConfig(
    minBackoff = 30.seconds,
    maxBackoff = 30.minutes,
    factor = 1.75
  )

  /** The timeout for the monitor actor. */
  private val MonitorTimeout: Timeout = Timeout(33.seconds)
end LoginServiceImplSpec

class LoginServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar, OptionValues, CloudArchiveModel.CloudArchiveJsonSupport:
  def this() = this(ActorSystem("LoginServiceImplSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import LoginServiceImplSpec.*

  /**
    * Creates the responses containing the given data entity.
    *
    * @param data the data for the response entity
    * @param m    the marshaller
    * @tparam T the type of the data
    * @return a [[Future]] with the response
    */
  private def createResponse[T](data: T)(using m: Marshaller[T, HttpResponse]): Future[List[HttpResponse]] =
    Marshal(data).to[HttpResponse].map(List(_))

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

  it should "not create a monitor actor if no backoff config is provided" in :

    given Timeout = Timeout(5.seconds)

    val service = LoginServiceImpl.newInstance(mock, None, null)

    service.optMonitorActor shouldBe empty

  it should "use a request function for the monitor that returns the correct request" in :
    val helper = new ServiceTestHelper

    val requests = helper.requestFunc(null)

    requests should have size 1
    val request = requests.head
    request.uri should be(Uri("/api/archive/archives/status"))
    request.method should be(HttpMethods.GET)

  it should "use an evaluate function for the monitor that returns new data" in :
    val status = CloudArchiveModel.CloudArchiveStateResponse(
      waitingArchives = Set("notLoadedArchive"),
      loadedArchives = Set("liveMusic", "musicOnline", "radioActive"),
      failedArchives = Set.empty
    )
    val helper = new ServiceTestHelper

    for
      response <- createResponse(status)
      result <- helper.evaluateFunc(response, None)
    yield
      result.value should be((status, status))

  it should "use an evaluate function for the monitor that returns None if data is not changed" in :
    val status = CloudArchiveModel.CloudArchiveStateResponse(
      waitingArchives = Set("notLoadedArchive"),
      loadedArchives = Set("liveMusic", "musicOnline", "radioActive"),
      failedArchives = Set(
        CloudArchiveModel.FailedArchive("errorArchive", "Could not load", 2)
      )
    )
    val helper = new ServiceTestHelper

    for
      response <- createResponse(status)
      result <- helper.evaluateFunc(response, Some(status))
    yield
      result shouldBe empty

  it should "return an evaluate function for the monitor that returns updated data" in :
    val oldStatus = CloudArchiveModel.CloudArchiveStateResponse(
      waitingArchives = Set("notLoadedArchive"),
      loadedArchives = Set("liveMusic", "musicOnline", "radioActive"),
      failedArchives = Set(
        CloudArchiveModel.FailedArchive("errorArchive", "Could not load", 2)
      )
    )
    val newStatus = CloudArchiveModel.CloudArchiveStateResponse(
      waitingArchives = Set.empty,
      loadedArchives = Set("liveMusic", "musicOnline", "radioActive", "notLoadedArchive"),
      failedArchives = Set(
        CloudArchiveModel.FailedArchive("errorArchive", "Could not load", 3)
      )
    )
    val helper = new ServiceTestHelper

    for
      response <- createResponse(newStatus)
      result <- helper.evaluateFunc(response, Some(oldStatus))
    yield
      result.value should be((newStatus, newStatus))

  it should "stop the monitor actor when it is closed" in :
    val helper = new ServiceTestHelper

    helper.loginService.close()

    helper.expectMonitorActorStopped()
    succeed

  /**
    * A test helper class managing a test service and its dependencies.
    */
  private class ServiceTestHelper:
    /** The probe for the request sender actor. */
    private val senderProbe = typedTestKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** The probe for the monitoring actor. */
    private val monitorProbe =
      typedTestKit.createTestProbe[ArchiveStateMonitor.ArchiveListenerCommand[ArchiveModel.MediaOverview]]()

    /** Stores the request function passed to the archive monitor. */
    private var optRequestFunc: Option[ArchiveStateMonitor.RequestFunc[LoginServiceImpl.MonitorData]] = None

    /** Stores the evaluate function passed to the archive monitor. */
    private var optEvaluateFunc:
      Option[ArchiveStateMonitor.EvaluateFunc[LoginServiceImpl.MonitorData, LoginServiceImpl.MonitorData]] = None

    /** A mock for the archive service. */
    private val mockArchiveService = createArchiveServiceMock()

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
      * Returns the request function that was specified when the archive
      * monitor was created.
      *
      * @return the request function
      */
    def requestFunc: ArchiveStateMonitor.RequestFunc[LoginServiceImpl.MonitorData] = optRequestFunc.value

    /**
      * Returns the evaluate function that was specified when the archive
      * monitor was created.
      *
      * @return the evaluate function
      */
    def evaluateFunc: ArchiveStateMonitor.EvaluateFunc[LoginServiceImpl.MonitorData, LoginServiceImpl.MonitorData] =
      optEvaluateFunc.value

    /**
      * Checks whether the monitor actor was sent a Stop command.
      *
      * @return this test helper
      */
    def expectMonitorActorStopped(): ServiceTestHelper =
      monitorProbe.expectMessage(ArchiveStateMonitor.ArchiveListenerCommand.Stop())
      this

    /**
      * Creates a mock for the archive service and prepares it to return the
      * probe for the archive sender.
      *
      * @return the mock archive service
      */
    private def createArchiveServiceMock(): ArchiveService =
      val mockService = mock[ArchiveService]
      when(mockService.requestSender).thenReturn(senderProbe.ref)
      mockService

    /**
      * Returns a stub factory for the archive monitor actor that checks the
      * passed in parameters and returns the managed test probe.
      *
      * @return the factory for the monitor actor
      */
    private def createStubMonitorFactory(): ArchiveStateMonitor.Factory =
      new ArchiveStateMonitor.Factory:
        override def apply[DATA, STATE](params: ArchiveStateMonitor.Params[DATA, STATE],
                                        actorName: String)
                                       (using actorFactory: ActorFactory):
        ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[STATE]] =
          actorFactory.actorSystem should be(system)
          params.archiveSender should be(senderProbe.ref)
          params.backoffFactory should be(BackoffActor.newInstance)
          params.requestTimeout should be(MonitorTimeout)
          params.backoffConfig should be(Some(MonitorBackoff).get)
          actorName should be(LoginServiceImpl.MonitorActorName)
          optRequestFunc = Some(
            params.requestFunc.asInstanceOf[ArchiveStateMonitor.RequestFunc[LoginServiceImpl.MonitorData]]
          )
          optEvaluateFunc = Some(
            params.evaluateFunc
              .asInstanceOf[ArchiveStateMonitor.EvaluateFunc[LoginServiceImpl.MonitorData,
              LoginServiceImpl.MonitorData]]
          )
          monitorProbe.ref.asInstanceOf[ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[STATE]]]

    /**
      * Creates a service instance to be tested.
      *
      * @return the test service instance
      */
    private def createLoginService(): LoginServiceImpl =
      given Timeout = MonitorTimeout

      LoginServiceImpl.newInstance(mockArchiveService, Some(MonitorBackoff), createStubMonitorFactory())
