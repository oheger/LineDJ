/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.platform.app

import de.oliver_heger.linedj.platform.bus.ComponentID
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{BlockingQueue, CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._

object ShutdownManagementActorSpec:
  /** A set with test component IDs. */
  private val TestComponents = createPendingComponents()

  /** The time to wait for the shutdown of the platform. */
  private val WaitForShutdownMillis = 3000

  /** An interval to check that no shutdown was triggered. */
  private val NoShutdownTimeFrameMillis = 1000

  /**
    * Creates a number of component IDs representing the pending components.
    *
    * @return the test component IDs
    */
  private def createPendingComponents(): Set[ComponentID] =
    (1 to 4).map(_ => ComponentID()).toSet

  /**
    * A trait supporting the creation of test actor instance. By having
    * different implementations, actor instances with various mocking
    * capabilities can be created.
    */
  trait TestActorCreator:
    /**
      * Returns a ''Props'' object to create a new test actor instance. This
      * base implementation returns the standard ''Props'' produced by the
      * actor class itself.
      *
      * @param managementApp the client management application
      * @param components    the pending components to monitor
      * @return the ''Props'' to create the test actor instance
      */
    def generateProps(managementApp: ClientManagementApplication, components: Set[ComponentID]): Props =
      ShutdownManagementActor.props(managementApp, components)


/**
  * Test class for ''ShutdownManagementActor''.
  */
class ShutdownManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ShutdownManagementActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import ShutdownManagementActorSpec._

  "ShutdownManagementActor" should "trigger a shutdown when confirmations of all components arrived" in:
    val helper = new ShutdownActorTestHelper with TestActorCreator

    helper.sendShutdownConfirmations(TestComponents.toSeq: _*)
      .expectPlatformShutdown()

  it should "not trigger a shutdown before all confirmations arrive" in:
    val componentIDs = TestComponents.toSeq drop 1
    val helper = new ShutdownActorTestHelper with TestActorCreator

    helper.sendShutdownConfirmations(componentIDs: _*)
      .expectNoPlatformShutdown()

  it should "not trigger multiple shutdowns" in:
    val helper = new ShutdownActorTestHelper(shutdownLatchCount = 2) with TestActorCreator

    helper.sendShutdownConfirmations(TestComponents.toSeq: _*)
      .sendShutdownConfirmations(ComponentID())
      .expectNoPlatformShutdown()

  it should "take a shutdown timeout into account" in:
    val helper = new ShutdownActorTestHelper with TestActorCreator

    helper.initShutdownTimeout(100.millis)
      .expectPlatformShutdown()

  it should "use the correct default shutdown timeout if none is configured" in:
    val helper = new ShutdownActorTestHelper with MockSchedulerTestActorCreator

    val invocation = helper.expectSchedule()
    invocation.initialDelay should be(ClientManagementApplication.DefaultShutdownTimeoutMillis.millis)

  it should "cancel the scheduled job when shutdown is complete" in:
    val helper = new ShutdownActorTestHelper with MockSchedulerTestActorCreator

    helper.sendShutdownConfirmations(TestComponents.toSeq: _*)
      .expectPlatformShutdown()
    val invocation = helper.expectSchedule()
    invocation.cancellable.isCancelled shouldBe true

  /**
    * A test helper managing a test actor instance and its dependencies.
    *
    * @param shutdownLatchCount the count value to initialize the latch that
    *                           is triggered when a shutdown happens
    */
  private class ShutdownActorTestHelper(shutdownLatchCount: Int = 1):
    this: TestActorCreator =>

    /** A latch to determine whether the application was shutdown. */
    private val latchShutdown = new CountDownLatch(shutdownLatchCount)

    /** The configuration of the client application. */
    private val managementConfig = new PropertiesConfiguration

    /** The actor instance to be tested. */
    private val shutdownActor = createShutdownActor()

    /**
      * Initializes the platform timeout property in the configuration of the
      * management application.
      *
      * @param timeout the timeout to be set
      * @return this test helper
      */
    def initShutdownTimeout(timeout: FiniteDuration): ShutdownActorTestHelper =
      managementConfig.setProperty(ClientManagementApplication.PropShutdownTimeout, timeout.toMillis)
      this

    /**
      * Sends shutdown confirmation messages for the given components to the
      * test actor instance.
      *
      * @param componentIDs the component IDs
      * @return this test helper
      */
    def sendShutdownConfirmations(componentIDs: ComponentID*): ShutdownActorTestHelper =
      componentIDs.foreach(id => send(ShutdownManagementActor.ShutdownConfirmation(id)))
      this

    /**
      * Checks whether the client management application was triggered to
      * shutdown the platform.
      *
      * @return this test helper
      */
    def expectPlatformShutdown(): ShutdownActorTestHelper =
      latchShutdown.await(WaitForShutdownMillis, TimeUnit.MILLISECONDS) shouldBe true
      this

    /**
      * Checks that no shutdown of the client management application was
      * triggered during a specific time frame.
      *
      * @return this test helper
      */
    def expectNoPlatformShutdown(): ShutdownActorTestHelper =
      latchShutdown.await(NoShutdownTimeFrameMillis, TimeUnit.MILLISECONDS) shouldBe false
      this

    /**
      * Sends the given message to the actor to be tested.
      *
      * @param msg the message
      * @return this test helper
      */
    private def send(msg: Any): ShutdownActorTestHelper =
      shutdownActor ! msg
      this

    /**
      * Creates an initialized mock for the client management application to be
      * shutdown by the actor under test.
      *
      * @return the mock application
      */
    private def createApplicationMock(): ClientManagementApplication =
      val app = mock[ClientManagementApplication]
      doAnswer((_: InvocationOnMock) => {
        latchShutdown.countDown()
        null
      }).when(app).shutdown()
      when(app.managementConfiguration).thenReturn(managementConfig)
      app

    /**
      * Creates a test actor instance.
      *
      * @return the test actor instance
      */
    private def createShutdownActor(): ActorRef =
      val props = generateProps(createApplicationMock(), TestComponents)
      system.actorOf(props)

  /**
    * A specialized implementation of ''TestActorCreator'', which adds support
    * for mocking interactions with the scheduler.
    */
  private trait MockSchedulerTestActorCreator extends TestActorCreator:
    /** The queue for recording scheduled invocations. */
    private val schedulerQueue =
      new LinkedBlockingQueue[RecordingSchedulerSupport.SchedulerInvocation]

    /**
      * Checks whether an interaction with the scheduler took place and returns
      * the corresponding data.
      *
      * @return the data object describing the scheduler invocation
      */
    def expectSchedule(): RecordingSchedulerSupport.SchedulerInvocation =
      RecordingSchedulerSupport.expectInvocation(schedulerQueue)

    override def generateProps(managementApp: ClientManagementApplication, components: Set[ComponentID]): Props =
      Props(new ShutdownManagementActor(managementApp, components)
        with RecordingSchedulerSupport {
        override val queue: BlockingQueue[RecordingSchedulerSupport.SchedulerInvocation] = schedulerQueue
      })

