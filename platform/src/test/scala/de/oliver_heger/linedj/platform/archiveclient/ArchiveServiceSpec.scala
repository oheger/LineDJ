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
import com.github.cloudfiles.core.http.MultiHostExtension.RequestActorFactory
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import spray.json.*

import java.util.concurrent.TimeoutException
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object ArchiveServiceSpec extends DefaultJsonProtocol:
  /** The URI of the test archive server. */
  private val ArchiveUri = Uri("https://archive.example.com/test")

  /** Test backoff configuration to monitor the archive for changes. */
  private val MonitorBackoff = BackoffConfig(
    minBackoff = 30.seconds,
    maxBackoff = 30.minutes,
    factor = 1.75
  )

  /** The timeout for sending requests to the archive. */
  given Timeout = Timeout(30.seconds)

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
  Matchers, MockitoSugar:
  def this() = this(ActorSystem("ArchiveServiceSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ArchiveServiceSpec.*
  import ArchiveServiceSpec.given

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

      override def addChangeListener(listener: ArchiveMonitor.ArchiveChangeListener): Unit =
        throw new UnsupportedOperationException("Unexpected call.")

      override def removeChangeListener(listener: ArchiveMonitor.ArchiveChangeListener): Unit =
        throw new UnsupportedOperationException("Unexpected call.")

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

  "ArchiveServiceImpl" should "send a request to the archive server" in :
    val helper = new ServiceTestHelper
    val request = HttpRequest(method = HttpMethods.HEAD, uri = "/test/end-point")
    val response = HttpResponse(status = StatusCodes.Accepted, entity = "Thanks")

    val futResponse = helper.archiveService.sendRequest(request)
    helper.handleSendRequest(request, response)

    futResponse map : actualResponse =>
      actualResponse should be(response)

  it should "handle a failed response from the archive server" in :
    val helper = new ServiceTestHelper
    val request = HttpRequest(uri = "/test/failure")
    val response = HttpResponse(status = StatusCodes.BadRequest, entity = "Error")
    val result = HttpRequestSender.FailedResult(null, HttpRequestSender.FailedResponseException(response))

    val futResponse = helper.archiveService.sendRequest(request)
    helper.handleSendRequest(request, result)

    recoverToExceptionIf[HttpRequestSender.FailedResponseException](futResponse) map : exception =>
      exception.response should be(response)

  it should "apply the configured timeout" in :
    val shortTimeout = Timeout(10.millis)
    val helper = new ServiceTestHelper()(using shortTimeout)

    val futResponse = helper.archiveService.sendRequest(HttpRequest(uri = "/test/timeout"))

    recoverToSucceededIf[TimeoutException](futResponse)

  it should "stop the sender actor when it is closed" in :
    val helper = new ServiceTestHelper

    helper.archiveService.close()

    helper.expectSenderMessage(HttpRequestSender.Stop)
    succeed

  it should "forward a new change listener to the monitor actor" in :
    val listener = mock[ArchiveMonitor.ArchiveChangeListener]
    val helper = new ServiceTestHelper

    helper.archiveService.addChangeListener(listener)

    helper.expectMonitorCommand(ArchiveMonitor.ArchiveListenerCommand.AddChangeListener(listener))
    succeed

  it should "forward a listener to remove to the monitor actor" in :
    val listener = mock[ArchiveMonitor.ArchiveChangeListener]
    val helper = new ServiceTestHelper

    helper.archiveService.removeChangeListener(listener)

    helper.expectMonitorCommand(ArchiveMonitor.ArchiveListenerCommand.RemoveChangeListener(listener))
    succeed

  it should "not create a monitor actor if no backoff config is provided" in :
    val listener = mock[ArchiveMonitor.ArchiveChangeListener]
    val helper = new ServiceTestHelper(optMonitorBackoff = None)

    helper.archiveService.addChangeListener(listener)
    helper.archiveService.removeChangeListener(listener)
    helper.archiveService.close()

    verifyNoInteractions(listener)
    succeed

  it should "stop the monitor actor when it is closed" in :
    val helper = new ServiceTestHelper

    helper.archiveService.close()

    helper.expectMonitorCommand(ArchiveMonitor.ArchiveListenerCommand.Stop)
    succeed

  /**
    * A test helper class that manages an instance under test and some required
    * dependencies.
    *
    * @param optMonitorBackoff the backoff to monitor the archive
    * @param timeout           the timeout when sending requests
    */
  private class ServiceTestHelper(optMonitorBackoff: Option[BackoffConfig] = Some(MonitorBackoff))
                                 (using timeout: Timeout):
    /** The probe for the request sender actor. */
    private val senderProbe: TestProbe[HttpRequestSender.HttpCommand] =
      typedTestKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** The probe for the monitoring actor. */
    private val monitorProbe = typedTestKit.createTestProbe[ArchiveMonitor.ArchiveListenerCommand]()

    /** The service to be tested. */
    val archiveService: ArchiveServiceImpl = ArchiveServiceImpl.newInstance(
      ArchiveUri.toString,
      optMonitorBackoff,
      createStubSenderFactory(),
      createStubMonitorFactory()
    )

    /**
      * Expects that the given message was sent to the sender actor.
      *
      * @param message the expected message
      * @return this test helper
      */
    def expectSenderMessage(message: HttpRequestSender.HttpCommand): ServiceTestHelper =
      senderProbe.expectMessage(message)
      this

    /**
      * Expects that the sender actor is passed a command to send a specific
      * request and answers it with the given response.
      *
      * @param request  the expected request
      * @param response the response
      * @return this test helper
      */
    def handleSendRequest(request: HttpRequest, response: HttpResponse): ServiceTestHelper =
      val result = HttpRequestSender.SuccessResult(null, response)
      handleSendRequest(request, result)

    /**
      * Expects that the sender actor is passed a command to send a specific
      * request and answers it with the given result.
      *
      * @param request the expected request
      * @param result  the result to send
      * @return this test helper
      */
    def handleSendRequest(request: HttpRequest, result: HttpRequestSender.Result): ServiceTestHelper =
      val requestMsg = senderProbe.expectMessageType[HttpRequestSender.SendRequest]
      requestMsg.request should be(request)
      requestMsg.discardEntityMode should be(HttpRequestSender.DiscardEntityMode.OnFailure)
      requestMsg.replyTo ! result
      this

    /**
      * Expects that a command was sent to the monitor actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectMonitorCommand(command: ArchiveMonitor.ArchiveListenerCommand): ServiceTestHelper =
      monitorProbe.expectMessage(command)
      this

    /**
      * Returns a factory for the request sender that checks the passed in
      * parameters and returns a reference to the managed test probe.
      *
      * @return the stub sender factory
      */
    private def createStubSenderFactory(): HttpRequestSenderFactory =
      new HttpRequestSenderFactory:
        override def createRequestSender(spawner: Spawner,
                                         baseUri: Uri,
                                         config: HttpRequestSenderConfig): ActorRef[HttpRequestSender.HttpCommand] =
          spawner should not be null
          baseUri should be(ArchiveUri)
          val expectedConfig = HttpRequestSenderConfig(actorName = Some(ArchiveServiceImpl.SenderName))
          config should be(expectedConfig)
          senderProbe.ref

        override def createMultiHostRequestSender(spawner: Spawner,
                                                  config: HttpRequestSenderConfig,
                                                  requestActorFactory: RequestActorFactory):
        ActorRef[HttpRequestSender.HttpCommand] =
          throw new UnsupportedOperationException("Unexpected call.")

        override def decorateRequestSender(spawner: Spawner,
                                           requestSender: ActorRef[HttpRequestSender.HttpCommand],
                                           config: HttpRequestSenderConfig): ActorRef[HttpRequestSender.HttpCommand] =
          throw new UnsupportedOperationException("Unexpected call.")

    /**
      * Returns a stub factory for the archive monitor actor that checks the
      * passed in parameters and returns the managed test probe.
      *
      * @return the factory for the monitor actor
      */
    private def createStubMonitorFactory(): ArchiveMonitor.Factory =
      new ArchiveMonitor.Factory {
        override def apply(params: ArchiveMonitor.ArchiveMonitorParams, actorName: String)
                          (using actorFactory: ActorFactory): ActorRef[ArchiveMonitor.ArchiveListenerCommand] = {
          actorFactory.actorSystem should be(system)
          params.archiveSender should be(senderProbe.ref)
          params.backoffFactory should be(BackoffActor.newInstance)
          params.requestTimeout should be(timeout)
          params.backoffConfig should be(optMonitorBackoff.get)
          actorName should be(ArchiveServiceImpl.MonitorName)
          monitorProbe.ref
        }
      }