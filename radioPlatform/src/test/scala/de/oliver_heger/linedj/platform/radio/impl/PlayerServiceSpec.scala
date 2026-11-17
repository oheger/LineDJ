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

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.MultiHostExtension.RequestActorFactory
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import spray.json.*

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object PlayerServiceSpec:
  /** The URI of the test radio player server. */
  private val ServerUri = Uri("https://radio.example.com/test")

  /** The timeout for sending requests to the server. */
  given Timeout = Timeout(30.seconds)
end PlayerServiceSpec

/**
  * Test class for [[PlayerService]] and [[PlayerServiceImpl]].
  */
class PlayerServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar, RadioModel.RadioJsonSupport:
  def this() = this(ActorSystem("PlayerServiceSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import PlayerServiceSpec.{*, given}

  /**
    * Creates a stub service that delegates each request to the given function.
    *
    * @param respond the function to handle a request
    * @return the stub service
    */
  private def createStubService(respond: HttpRequest => Future[HttpResponse]): PlayerService =
    new PlayerService:
      override def sendRequest(request: HttpRequest): Future[HttpResponse] = respond(request)

      override protected def actorSystem: ActorSystem = system

      override private[radio] def requestSender: ActorRef[HttpRequestSender.HttpCommand] =
        throw new UnsupportedOperationException("Unexpected call.")

  /**
    * Creates an HTTP response with the given data serialized as JSON.
    *
    * @param data the data to be serialized
    * @return the response
    */
  private def jsonResponse[A](data: A)(using writer: RootJsonFormat[A]): HttpResponse =
    HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, data.toJson.prettyPrint))

  /**
    * Creates a stub service that expects a specific request and returns the
    * given response.
    *
    * @param expectedRequest the expected request
    * @param response        the response to return
    * @return the stub service
    */
  private def createStubService(expectedRequest: HttpRequest, response: HttpResponse): PlayerService =
    createStubService: request =>
      request should be(expectedRequest)
      Future.successful(response)

  "radioSources" should "query the radio sources from the server" in :
    val sources = RadioModel.RadioSources(List(RadioModel.RadioSource("id", "Test", 1)))
    val expectedRequest = HttpRequest(uri = PlayerService.SourcesPath)
    val service = createStubService(expectedRequest, jsonResponse(sources))

    service.radioSources map : result =>
      result should be(sources)

  "switchToSource" should "send a POST request to select the given source" in :
    val sourceID = "some-source-id"
    val expectedRequest = HttpRequest(method = HttpMethods.POST, uri = s"${PlayerService.CurrentSourcePath}/$sourceID")
    val service = createStubService(expectedRequest, HttpResponse())

    service.switchToSource(sourceID) map : _ =>
      succeed

  "startPlayback" should "send a POST request to start playback" in :
    val expectedRequest = HttpRequest(method = HttpMethods.POST, uri = PlayerService.PlaybackStartPath)
    val service = createStubService(expectedRequest, HttpResponse())

    service.startPlayback() map : _ =>
      succeed

  "stopPlayback" should "send a POST request to stop playback" in :
    val expectedRequest = HttpRequest(method = HttpMethods.POST, uri = PlayerService.PlaybackStopPath)
    val service = createStubService(expectedRequest, HttpResponse())

    service.stopPlayback() map : _ =>
      succeed

  "PlayerServiceImpl" should "send a request to the radio player server" in :
    val helper = new ServiceTestHelper
    val request = HttpRequest(method = HttpMethods.HEAD, uri = "/test/end-point")
    val response = HttpResponse(status = StatusCodes.Accepted, entity = "Thanks")

    val futResponse = helper.service.sendRequest(request)
    helper.handleSendRequest(request, response)

    futResponse map : actualResponse =>
      actualResponse should be(response)

  it should "handle a failed response from the radio player server" in :
    val helper = new ServiceTestHelper
    val request = HttpRequest(uri = "/test/failure")
    val response = HttpResponse(status = StatusCodes.BadRequest, entity = "Error")
    val result = HttpRequestSender.FailedResult(null, HttpRequestSender.FailedResponseException(response))

    val futResponse = helper.service.sendRequest(request)
    helper.handleSendRequest(request, result)

    recoverToExceptionIf[HttpRequestSender.FailedResponseException](futResponse) map : exception =>
      exception.response should be(response)

  it should "stop the sender actor when it is closed" in :
    val helper = new ServiceTestHelper

    helper.service.close()

    helper.expectSenderMessage(HttpRequestSender.Stop)
    succeed

  /**
    * A test helper class that manages an instance under test and some required
    * dependencies.
    *
    * @param timeout the timeout when sending requests
    */
  private class ServiceTestHelper(using timeout: Timeout):
    /** The probe for the request sender actor. */
    private val senderProbe: TestProbe[HttpRequestSender.HttpCommand] =
      typedTestKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** The service to be tested. */
    val service: PlayerServiceImpl = PlayerServiceImpl.newInstance(ServerUri.toString, createStubSenderFactory())

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
          baseUri should be(ServerUri)
          val expectedConfig = HttpRequestSenderConfig(actorName = Some(PlayerServiceImpl.SenderName))
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
