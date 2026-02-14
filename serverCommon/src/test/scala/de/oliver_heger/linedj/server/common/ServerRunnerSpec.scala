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

package de.oliver_heger.linedj.server.common

import de.oliver_heger.linedj.server.common
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import org.apache.pekko.actor.Terminated
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.{Done, actor as classic}
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, Succeeded}
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

object ServerRunnerSpec:
  /** Timeout in milliseconds when waiting for something to happen. */
  private val WaitTimeoutMs = 500

  /** An object used as simulated context of the test server. */
  private val ServerContext = "The context of the test server"

  /** A name for the test server. */
  private val ServerName = "MyTestServer"

  /**
    * Type alias for a function that can test a running server instance. The
    * function is passed the base [[Uri]] to the server and a handle to it.
    * During its execution, it should trigger the shutdown of the server.
    */
  type ServerTestFunc = (Uri, ServerRunner.ServerHandle) => Future[Assertion]
end ServerRunnerSpec

/**
  * Test class for [[ServerRunner]].
  */
class ServerRunnerSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with Eventually:
  def this() = this(classic.ActorSystem("ServerRunnerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ServerRunnerSpec.*

  /**
    * Expects that the given [[Future]] does not complete within the configured
    * interval.
    *
    * @param f the future
    * @tparam T the type of the future
    */
  private def expectNoCompletion[T](f: Future[T]): Assertion =
    val latch = new CountDownLatch(1)
    f.onComplete(_ => latch.countDown())(using system.dispatcher)

    latch.await(WaitTimeoutMs, TimeUnit.MILLISECONDS) shouldBe false

  "enableGracefulShutdown" should "call the shutdown when all conditions are met" in :
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[classic.ActorSystem]
    val mockActorFactory = mock[ManagingActorFactory]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    when(mockSystem.terminate()).thenReturn(Future.successful(mock[Terminated]))
    val startupData = ServerRunner.ServerStartupData(mockBinding, ServerContext)
    given ServerController.ServerServices(mockSystem, mockActorFactory)

    val futTerminate = ServerRunner.enableGracefulShutdown(
      Future.successful(startupData),
      Future.successful(Done)
    )
    futTerminate map : t =>
      verify(mockBinding).addToCoordinatedShutdown(5.seconds)(mockSystem)
      verify(mockActorFactory).stopActors()
      verify(mockSystem).terminate()
      t shouldBe ServerContext

  it should "not shut down before the server has been fully started" in :
    val mockSystem = mock[classic.ActorSystem]
    val mockActorFactory = mock[ManagingActorFactory]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseStartup = Promise[ServerRunner.ServerStartupData[Any]]()
    given ServerController.ServerServices(mockSystem, mockActorFactory)

    expectNoCompletion(
      ServerRunner.enableGracefulShutdown(
        promiseStartup.future,
        Future.successful(Done)
      )
    )

    verify(mockActorFactory, never()).stopActors()
    verify(mockSystem, never()).terminate()
    Succeeded

  it should "not shut down before the shutdown future has completed" in :
    val mockBinding = mock[ServerBinding]
    val startupData = ServerRunner.ServerStartupData(mockBinding, "some context")
    val mockSystem = mock[classic.ActorSystem]
    val mockActorFactory = mock[ManagingActorFactory]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseShutdown = Promise[Done]()
    given ServerController.ServerServices(mockSystem, mockActorFactory)

    expectNoCompletion(
      ServerRunner.enableGracefulShutdown(
        Future.successful(startupData),
        promiseShutdown.future
      )
    )

    verify(mockActorFactory, never()).stopActors()
    verify(mockSystem, never()).terminate()
    Succeeded

  it should "shut down the actor system even if the startup future failed" in :
    val mockSystem = mock[classic.ActorSystem]
    when(mockSystem.terminate()).thenReturn(Future.successful(mock[Terminated]))
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val mockActorFactory = mock[ManagingActorFactory]
    val promiseStartup = Promise[ServerRunner.ServerStartupData[Any]]()
    given ServerController.ServerServices(mockSystem, mockActorFactory)

    val futureTerminated = ServerRunner.enableGracefulShutdown(
      promiseStartup.future,
      Future.successful(Done)
    )

    val startUpException = new IllegalStateException("Test exception")
    val futureException = recoverToExceptionIf[IllegalStateException]:
      promiseStartup.failure(startUpException)
      futureTerminated

    futureException map : e =>
      verify(mockActorFactory).stopActors()
      verify(mockSystem).terminate()
      e should be(startUpException)

  /**
    * Returns a spy for the actor system that prevents that it gets terminated
    * when the test server shuts down.
    *
    * @return the spy for the actor system
    */
  private def actorSystemSpy: classic.ActorSystem =
    val systemSpy = spy(system)
    doReturn(Future.successful(Terminated)).when(systemSpy).terminate()
    systemSpy

  /**
    * Creates a [[ServerRunner]] to be used for tests which does not operate
    * on the implicit actor system of this test class, but a spy that cannot be
    * terminated.
    *
    * @param locatorFactory an optional factory for locators
    * @return the runner for test execution
    */
  private def createRunner(locatorFactory: ServerLocator.LocatorFactory = mock): ServerRunner =
    new ServerRunner(locatorFactory)(using actorSystemSpy)

  /**
    * Checks whether a server is up and can be accessed via the given URI.
    *
    * @param serverUri the URI of the server
    * @return a [[Future]] with the test result
    */
  private def checkRunningServer(serverUri: Uri): Future[Assertion] =
    val request = HttpRequest(uri = serverUri.withPath(Uri.Path("/test/result")))
    Http().singleRequest(request) flatMap : response =>
      response.status should be(StatusCodes.OK)
      Unmarshal(response).to[String] map : responseStr =>
        responseStr should be("42")

  /**
    * Creates a [[ServerController]] object to manage a test server instance.
    * The controller records whether its shutdown callback is called.
    * Optionally, it can simulate a failure to create its context; this should
    * prevent the server from starting up.
    *
    * @param serverPort          the port to bind the server to
    * @param afterShutdownCalled flag to record the invocation of the shutdown
    *                            callback
    * @param contextException    an exception to throw when creating the 
    *                            context
    * @param optLocatorParams    optional parameters for a locator
    * @return the controller object
    */
  private def createTestController(serverPort: Int,
                                   afterShutdownCalled: AtomicBoolean = new AtomicBoolean,
                                   contextException: Option[Throwable] = None,
                                   optLocatorParams: Option[ServerLocator.LocatorParams] = None): ServerController =
    new ServerController:
      override type Context = String

      override def createContext(using services: ServerController.ServerServices): Future[String] =
        contextException match
          case None => Future.successful(ServerContext)
          case Some(exception) => Future.failed(exception)

      override def serverParameters(context: String)
                                   (using services: ServerController.ServerServices):
      Future[ServerController.ServerParameters] =
        context should be(ServerContext)
        Future.successful(
          ServerController.ServerParameters(
            bindingParameters = ServerController.BindingParameters("localhost", serverPort),
            optLocatorParams = optLocatorParams
          )
        )

      override def route(context: String, shutdownPromise: Promise[Done])
                        (using services: ServerController.ServerServices): Route =
        pathPrefix("test"):
          concat(
            path("shutdown"):
              post:
                shutdownPromise.success(Done)
                Directives.complete(StatusCodes.Accepted),
            path("result"):
              Directives.complete("42")
          )

      override def afterShutdown(context: String): Unit =
        context should be(ServerContext)
        afterShutdownCalled.set(true)

  /**
    * Starts up a test server and executes a [[ServerTestFunc]] to interact
    * with it. The test server exposes a GET endpoint for querying a simple
    * value and a POST endpoint for triggering its shutdown.
    *
    * @param testFunc the function to execute the test
    * @return the result from the test function
    */
  private def testServer(testFunc: ServerTestFunc): Future[Assertion] =
    val serverPort = findFreePort()
    val afterShutdownCalled = new AtomicBoolean
    val locatorFactory = mock[ServerLocator.LocatorFactory]

    val controller = createTestController(serverPort, afterShutdownCalled)
    val runner = createRunner(locatorFactory)
    val handle = runner.launch(ServerName, controller)
    val serverUri = Uri(s"http://localhost:$serverPort")

    eventually:
      checkRunningServer(serverUri)

    val testResult = testFunc(serverUri, handle)

    awaitCond(afterShutdownCalled.get())
    verifyNoInteractions(locatorFactory)
    testResult

  "launch" should "connect the server's route with the shutdown future" in :
    testServer: (uri, handle) =>
      val shutdownRequest = HttpRequest(method = HttpMethods.POST, uri = uri.withPath(Uri.Path("/test/shutdown")))
      Http().singleRequest(shutdownRequest) map : response =>
        response.status should be(StatusCodes.Accepted)

  it should "return a handle that can be used to shut down the server" in :
    testServer: (_, handle) =>
      handle.shutdown()

      handle.shutdownFuture map (_ => Succeeded)

  /**
    * Waits for the given handle to complete in a separate thread. When this
    * happens, the returned latch is fired.
    *
    * @param handle the handle to wait for
    * @return a latch to signal that waiting is complete
    */
  private def waitForHandle(handle: ServerRunner.ServerHandle): CountDownLatch =
    val latchShutdown = new CountDownLatch(1)
    val waitThread = new Thread(
      () =>
        val futShutdown = handle.awaitShutdown()
        futShutdown.isCompleted shouldBe true
        latchShutdown.countDown()
    )
    waitThread.start()
    latchShutdown

  it should "return a handle that allows waiting for the server to shut down" in :
    testServer: (_, handle) =>
      val latchShutdown = waitForHandle(handle)

      handle.shutdown()

      latchShutdown.await(WaitTimeoutMs, TimeUnit.MILLISECONDS) shouldBe true

  it should "return a handle that does not stop waiting until the server has shut down" in :
    testServer: (_, handle) =>
      val latchShutdown = waitForHandle(handle)

      latchShutdown.await(WaitTimeoutMs, TimeUnit.MILLISECONDS) shouldBe false

      handle.shutdown()
      succeed

  it should "handle a failure to create the server context" in :
    val shutdownCalled = new AtomicBoolean
    val exception = new IllegalStateException("Test exception: Could not create server context.")
    val controller = createTestController(findFreePort(), shutdownCalled, contextException = Some(exception))

    val runner = createRunner()
    val handle = runner.launch(ServerName, controller)

    recoverToExceptionIf[IllegalStateException](handle.shutdownFuture) map : handleException =>
      shutdownCalled.get() shouldBe false
      handleException.getMessage should be(exception.getMessage)

  it should "correctly start a locator for the server" in :
    val serverPort = findFreePort()
    val locatorFactory = mock[ServerLocator.LocatorFactory]
    val locatorParams = ServerLocator.LocatorParams(
      multicastAddress = "231.2.3.4",
      port = 7777,
      requestCode = "testCode",
      responseTemplate = "I am here!"
    )

    val controller = createTestController(serverPort, optLocatorParams = Some(locatorParams))
    val runner = createRunner(locatorFactory)
    val handle = runner.launch(ServerName, controller)

    handle.shutdown()
    handle.shutdownFuture map : _ =>
      verify(locatorFactory).apply(argEq(s"${ServerName}_locator"), argEq(locatorParams))(using any())
      Succeeded
