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
import de.oliver_heger.linedj.platform.archiveclient.ArchiveMonitorSpec.BackoffActorCreation
import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.headers.{ETag, EntityTag, `If-None-Match`}
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.*
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{Future, TimeoutException}

object ArchiveMonitorSpec:
  /** The backoff config used by tests. */
  private val TestBackoffConfig = BackoffConfig(
    minBackoff = 11.seconds,
    maxBackoff = 58.minutes,
    factor = 3.1415
  )

  /** The default timeout for sending requests to the archive. */
  private val DefaultRequestTimeout = Timeout(3.seconds)

  /** Constant for the `ETag` header returned by the test archive. */
  private val DefaultETag = "test-e-tag"

  /**
    * An internally used data class to track the creation of a backoff actor.
    *
    * @param backoffParams    the parameters passed to the factory
    * @param backoffActorName the name of the backoff actor
    */
  private case class BackoffActorCreation(backoffParams: BackoffActor.BackoffParameters,
                                          backoffActorName: String)

  /**
    * Creates the expected request to the test archive with an optional header
    * containing the last known `ETag` value.
    *
    * @param etag the optional tag value
    * @return the request
    */
  private def archiveRequest(etag: Option[String] = Some(DefaultETag)): HttpRequest =
    val headers = etag.map(tag => Seq(`If-None-Match`(EntityTag(tag)))).getOrElse(Seq.empty)
    HttpRequest(
      uri = "/api/archive/media",
      method = HttpMethods.HEAD,
      headers = headers
    )
end ArchiveMonitorSpec

/**
  * Test class for [[ArchiveMonitor]].
  */
class ArchiveMonitorSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar:
  def this() = this(ActorSystem("ArchiveMonitorSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ArchiveMonitorSpec.*

  "An ArchiveMonitor actor" should "stop itself on receiving a Stop command" in :
    val helper = new MonitorTestHelper("stop")

    helper.checkStopMonitorActor()

  it should "notify a change listener about a change in the archive" in :
    val listener = new TestArchiveChangeLister
    val helper = new MonitorTestHelper("basicListener")

    val futRequest = helper.registerListener(listener)
      .expectBackoffActorCreation()
      .handleArchiveRequest(archiveRequest(None), HttpResponse(), BackoffActor.TaskResult.Reset)
    futRequest flatMap : _ =>
      listener.expectInvocation()

  it should "not notify change listeners if no change in the archive was found" in :
    val listener = new TestArchiveChangeLister
    val response = HttpResponse(status = StatusCodes.NotModified)
    val helper = new MonitorTestHelper("noChange")

    val futRequest = helper.registerListener(listener)
      .expectBackoffActorCreation()
      .handleArchiveRequest(archiveRequest(None), response, BackoffActor.TaskResult.Backoff)
    futRequest flatMap : _ =>
      listener.expectNoInvocation()

  it should "not notify change listeners if an unexpected response from the archive is received" in :
    val listener = new TestArchiveChangeLister
    val response = HttpResponse(status = StatusCodes.BadRequest)
    val helper = new MonitorTestHelper("unexpectedStatus")

    val futRequest = helper.registerListener(listener)
      .expectBackoffActorCreation()
      .handleArchiveRequest(archiveRequest(None), response, BackoffActor.TaskResult.Backoff)
    futRequest flatMap : _ =>
      listener.expectNoInvocation()

  it should "record the ETag and reuse it in follow-up requests" in :
    val listener = new TestArchiveChangeLister
    val response = HttpResponse(headers = Seq(ETag(DefaultETag)))
    val response2 = response.withStatus(StatusCodes.NotModified)
    val helper = new MonitorTestHelper("recordTag")

    for
      _ <- helper.registerListener(listener)
        .expectBackoffActorCreation()
        .handleArchiveRequest(archiveRequest(None), response, BackoffActor.TaskResult.Reset)
      _ <- listener.expectInvocation()
      res <- helper.handleArchiveRequest(archiveRequest(), response2, BackoffActor.TaskResult.Backoff)
    yield res

  it should "only create a backoff actor on registering the first listener" in :
    val listener1 = new TestArchiveChangeLister
    val listener2 = new TestArchiveChangeLister
    val helper = new MonitorTestHelper("singleBackoffCreation")

    for
      _ <- helper.registerListener(listener1)
        .expectBackoffActorCreation()
        .registerListener(listener2)
        .expectNoBackoffActorCreation()
        .handleArchiveRequest(archiveRequest(None), HttpResponse(), BackoffActor.TaskResult.Reset)
      _ <- listener1.expectInvocation()
      res <- listener2.expectInvocation()
    yield res

  it should "correctly evaluate the timeout" in :
    val timeout = 50.millis
    val listener = new TestArchiveChangeLister
    val result = HttpRequestSender.FailedResult(
      request = HttpRequestSender.SendRequest(archiveRequest(None), null, null),
      cause = new IllegalStateException("Test exception: Failed request.")
    )
    val helper = new MonitorTestHelper("failedRequest", requestTimeout = Timeout(timeout))

    val startTime = System.nanoTime()
    val futRequest = helper.registerListener(listener)
      .expectBackoffActorCreation()
      .handleArchiveRequestWithResult(archiveRequest(None), result, BackoffActor.TaskResult.Backoff)
    recoverToSucceededIf[TimeoutException](futRequest) flatMap : _ =>
      val endTime = System.nanoTime()
      (endTime - startTime).nanos should be < 1.second

  it should "close the backoff handle when the last listener is removed" in :
    val listener = new TestArchiveChangeLister
    val helper = new MonitorTestHelper("closeBackoffHandle")

    helper.registerListener(listener)
      .expectBackoffActorCreation()
      .removeListener(listener)
      .verifyBackoffHandleClosed()

  it should "not close the backoff handle if there are further listeners registered" in :
    val listener1 = new TestArchiveChangeLister
    val listener2 = new TestArchiveChangeLister
    val helper = new MonitorTestHelper("closeBackoffHandleOnLastListener")

    helper.registerListener(listener1)
      .expectBackoffActorCreation()
      .registerListener(listener2)
      .removeListener(listener2)

    val futNotClosed = for
      _ <- listener1.expectNoInvocation() // Wait a bit
      notClosed <- helper.verifyBackoffHandleNotClosed()
    yield notClosed
    futNotClosed flatMap : _ =>
      helper.removeListener(listener1)
        .verifyBackoffHandleClosed()

  it should "support removing change listeners" in :
    val listener1 = new TestArchiveChangeLister
    val listener2 = new TestArchiveChangeLister
    val helper = new MonitorTestHelper("removeListener")

    for
      _ <- helper.registerListener(listener1)
        .expectBackoffActorCreation()
        .registerListener(listener2)
        .removeListener(listener1)
        .handleArchiveRequest(archiveRequest(None), HttpResponse(), BackoffActor.TaskResult.Reset)
      _ <- listener2.expectInvocation()
      res <- listener1.expectNoInvocation()
    yield res

  it should "create another backoff actor on registration of new listeners" in :
    val listener = new TestArchiveChangeLister
    val helper = new MonitorTestHelper("anotherBackoffActor")

    for
      _ <- helper.registerListener(listener)
        .expectBackoffActorCreation()
        .removeListener(listener)
        .registerListener(listener)
        .expectBackoffActorCreation()
        .handleArchiveRequest(archiveRequest(None), HttpResponse(), BackoffActor.TaskResult.Reset)
      res <- listener.expectInvocation()
    yield res

  /**
    * A test helper class managing a test actor instance and its dependencies.
    *
    * @param actorName      the name of the monitor actor
    * @param requestTimeout the timeout for requests
    */
  private class MonitorTestHelper(actorName: String,
                                  requestTimeout: Timeout = DefaultRequestTimeout):
    /** The implicit actor factory to be used. */
    private given actorFactory: ActorFactory = ActorFactory.defaultActorFactory

    /** A queue to track the creation of backoff actors. */
    private val backoffCreationQueue = new LinkedBlockingQueue[BackoffActorCreation]

    /** A mock backoff handle to be returned by the test factory. */
    private val backoffHandle = mock[BackoffActor.BackoffHandle]

    /**
      * A reference to hold the task function passed to the latest backoff 
      * actor creation.
      */
    private val refTaskFunc = new AtomicReference[BackoffActor.TaskFunc]

    /** A test probe representing the HTTP sender to the archive. */
    private val httpSenderProbe = typedTestKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** The actor under test. */
    private val monitorActor = createMonitorActor()

    /**
      * Expects that a backoff actor has been created and verifies the creation
      * parameters.
      *
      * @return this test helper
      */
    def expectBackoffActorCreation(): MonitorTestHelper =
      val creation = backoffCreationQueue.poll(3, TimeUnit.SECONDS)
      creation should not be null
      creation.backoffParams.minBackoff should be(TestBackoffConfig.minBackoff)
      creation.backoffParams.maxBackoff should be(TestBackoffConfig.maxBackoff)
      creation.backoffParams.incrementFactor should be(TestBackoffConfig.factor)
      creation.backoffParams.failureResult should be(BackoffActor.TaskResult.Backoff)
      creation.backoffActorName should be(actorName + ".backoff")
      refTaskFunc.set(creation.backoffParams.taskFunc)
      this

    /**
      * Expects the no backoff actor is created within a specific grace period.
      *
      * @return this test helper
      */
    def expectNoBackoffActorCreation(): MonitorTestHelper =
      backoffCreationQueue.poll(100, TimeUnit.MILLISECONDS) should be(null)
      this

    /**
      * Calls the task function that was passed to the backoff actor and 
      * handles the request that was sent via the HTTP sender actor. The 
      * function checks whether the expected request is sent and answers it 
      * with the provided result.
      *
      * @param expectedRequest    the expected request to the archive
      * @param result             the result to return to the task function
      * @param expectedTaskResult the expected result of the task function
      * @param triggerInvocation  flag whether to call the task function
      * @return a [[Future]] with the check of the task result
      */
    def handleArchiveRequestWithResult(expectedRequest: HttpRequest,
                                       result: HttpRequestSender.Result,
                                       expectedTaskResult: BackoffActor.TaskResult,
                                       triggerInvocation: Boolean = true): Future[Assertion] =
      val futTask = if triggerInvocation then
        taskFunc()
      else
        Future.successful(expectedTaskResult)
      val request = httpSenderProbe.expectMessageType[HttpRequestSender.SendRequest]
      request.discardEntityMode should be(HttpRequestSender.DiscardEntityMode.Always)
      request.request should be(expectedRequest)
      request.replyTo ! result
      futTask.map: taskResult =>
        taskResult should be(expectedTaskResult)

    /**
      * Calls the task function that was passed to the backoff actor and 
      * handles the request sent via the HTTP sender actor by returning the
      * given response. This is a convenience function that constructs the
      * required result object.
      *
      * @param expectedRequest    the expected request to the archive
      * @param response           the HTTP response to return
      * @param expectedTaskResult the expected result of the task function
      * @param triggerInvocation  flag whether to call the task function
      * @return a [[Future]] with the check of the task result
      */
    def handleArchiveRequest(expectedRequest: HttpRequest,
                             response: HttpResponse,
                             expectedTaskResult: BackoffActor.TaskResult,
                             triggerInvocation: Boolean = true): Future[Assertion] =
      val result = HttpRequestSender.SuccessResult(
        request = HttpRequestSender.SendRequest(
          expectedRequest,
          null,
          typedTestKit.createTestProbe[HttpRequestSender.Result]().ref
        ),
        response = response
      )
      handleArchiveRequestWithResult(expectedRequest, result, expectedTaskResult, triggerInvocation)

    /**
      * Adds the given change listener to the test actor instance.
      *
      * @param listener the listener to register
      * @return this test helper
      */
    def registerListener(listener: ArchiveMonitor.ArchiveChangeListener): MonitorTestHelper =
      monitorActor ! ArchiveMonitor.ArchiveListenerCommand.AddChangeListener(listener)
      this

    /**
      * Removes the given change listener from the test actor instance.
      *
      * @param listener the listener to remove
      * @return this test helper
      */
    def removeListener(listener: ArchiveMonitor.ArchiveChangeListener): MonitorTestHelper =
      monitorActor ! ArchiveMonitor.ArchiveListenerCommand.RemoveChangeListener(listener)
      this

    /**
      * Verifies that the backoff handle has been closed.
      *
      * @return the result of the check
      */
    def verifyBackoffHandleClosed(): Future[Assertion] = Future:
      verify(backoffHandle, timeout(3000)).close()
      succeed

    /**
      * Verifies that the backoff handle has not yet been closed.
      *
      * @return the result of the check
      */
    def verifyBackoffHandleNotClosed(): Future[Assertion] = Future:
      verify(backoffHandle, never()).close()
      succeed

    /**
      * Tests whether the monitor actor terminates on receiving a stop command.
      *
      * @return a [[Future]] with the test result
      */
    def checkStopMonitorActor(): Future[Assertion] =
      monitorActor ! ArchiveMonitor.ArchiveListenerCommand.Stop

      val watchProbe = typedTestKit.createDeadLetterProbe()
      watchProbe.expectTerminated(monitorActor)
      succeed

    /**
      * Returns the task function that was passed to the latest backoff actor
      * creation.
      *
      * @return the backoff task function
      */
    private def taskFunc: BackoffActor.TaskFunc =
      val tf = refTaskFunc.get()
      tf should not be null
      tf

    /**
      * Creates a stub factory for creating new backoff actor instances.
      *
      * @return the stub backoff actor factory
      */
    private def createBackoffFactory(): BackoffActor.Factory =
      new BackoffActor.Factory:
        override def apply(params: BackoffActor.BackoffParameters, name: String)
                          (using factory: ActorFactory): BackoffActor.BackoffHandle =
          factory should be(actorFactory)
          backoffCreationQueue.offer(BackoffActorCreation(params, name))
          backoffHandle

    /**
      * Creates a monitor actor to be tested.
      *
      * @return the reference to the new actor
      */
    private def createMonitorActor(): ActorRef[ArchiveMonitor.ArchiveListenerCommand] =
      val monitorParams = ArchiveMonitor.ArchiveMonitorParams(
        archiveSender = httpSenderProbe.ref,
        backoffConfig = TestBackoffConfig,
        requestTimeout = requestTimeout,
        backoffFactory = createBackoffFactory()
      )
      ArchiveMonitor.newInstance(monitorParams, actorName)
  end MonitorTestHelper

  /**
    * A test implementation of an archive change listener that allows waiting
    * for a certain number of invocations.
    */
  private class TestArchiveChangeLister extends ArchiveMonitor.ArchiveChangeListener:
    /** A queue to receive the recorded invocation times. */
    private val invocationTimes = new LinkedBlockingQueue[Instant]

    override def archiveContentChanged(): Unit =
      invocationTimes.offer(Instant.now())

    /**
      * Waits for an invocation of this listener. This is done by monitoring a 
      * queue that gets populated by the listener callback.
      *
      * @return the result of the check
      */
    def expectInvocation(): Future[Assertion] =
      Future:
        invocationTimes.poll(3, TimeUnit.SECONDS) should not be null

    /**
      * Checks that for a certain period this change listener is not invoked.
      *
      * @return the result of the check
      */
    def expectNoInvocation(): Future[Assertion] =
      Future:
        invocationTimes.poll(100, TimeUnit.MILLISECONDS) should be(null)
  end TestArchiveChangeLister
