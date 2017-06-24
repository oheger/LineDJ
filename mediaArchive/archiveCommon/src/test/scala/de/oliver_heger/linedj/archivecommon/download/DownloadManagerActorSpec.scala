/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivecommon.download

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.RecordingSchedulerSupport
import de.oliver_heger.linedj.shared.archive.media.DownloadActorAlive
import de.oliver_heger.linedj.utils.SchedulerSupport
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object DownloadManagerActorSpec {
  /** The interval for reader actor timeout checks. */
  private val DownloadCheckInterval = 5.minutes

  /** Timeout for download operations. */
  private val DownloadTimeout = 1.hour
}

/**
  * Test class for ''DownloadManagerActor''.
  */
class DownloadManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("DownloadManagerActorSpec"))

  import DownloadManagerActorSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Expects that the specified actor has been stopped.
    *
    * @param actor the actor in question
    */
  private def expectTermination(actor: ActorRef): Unit = {
    val probeWatcher = TestProbe()
    probeWatcher watch actor
    probeWatcher.expectMsgType[Terminated].actor should be(actor)
  }

  /**
    * Expects that the specified actor has not been stopped.
    *
    * @param actor the actor in question
    */
  private def expectNoTermination(actor: ActorRef): Unit = {
    val probeWatcher = TestProbe()
    probeWatcher watch actor
    probeWatcher.expectNoMsg(1.second)
  }

  /**
    * Creates a mock download configuration that returns test settings.
    *
    * @return the mock download configuration
    */
  private def createConfig(): DownloadConfig = {
    val config = mock[DownloadConfig]
    when(config.downloadCheckInterval).thenReturn(DownloadCheckInterval)
    when(config.downloadTimeout).thenReturn(DownloadTimeout)
    config
  }

  "A DownloadManagerActor" should "return correct properties" in {
    val config = mock[DownloadConfig]
    val props = DownloadManagerActor(config)

    classOf[DownloadManagerActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[SchedulerSupport].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should contain only config
  }

  it should "create a default DownloadActorData object" in {
    val actor = TestActorRef[DownloadManagerActor](DownloadManagerActor(createConfig()))

    actor.underlyingActor.downloadData shouldBe a[DownloadActorData]
  }

  it should "store data about download operations in the helper object" in {
    val helper = new DownloadManagerTestHelper

    val downloadActor = helper.sendDownloadOperation()
    val timestamp = helper.expectDownloadOperationAndGetTime(downloadActor.ref)
    Duration(System.currentTimeMillis() - timestamp, MILLISECONDS) should be <= 2.seconds
  }

  it should "stop download actors when their client actor dies" in {
    val helper = new DownloadManagerTestHelper(realDownloadData = true)
    val client = TestProbe()
    val downloadActor1 = helper.sendDownloadOperation(client.ref)
    val downloadActor2 = helper.sendDownloadOperation(client.ref)

    system stop client.ref
    expectTermination(downloadActor1.ref)
    expectTermination(downloadActor2.ref)
  }

  it should "stop watching a client actor if there are no more download operations" in {
    val helper = new DownloadManagerTestHelper
    val client = TestProbe()
    when(helper.downloadData.findReadersForClient(client.ref))
      .thenReturn(List.empty, List(client.ref), List.empty)
    when(helper.downloadData.remove(any(classOf[ActorRef]))).thenReturn(Some(client.ref))
    when(helper.downloadData.hasActor(any(classOf[ActorRef]))).thenAnswer(new Answer[Boolean] {
      override def answer(invocation: InvocationOnMock): Boolean =
        invocation.getArguments.head != client.ref
    })
    val downloadActor1 = helper.sendDownloadOperation(client.ref)
    val downloadActor2 = helper.sendDownloadOperation(client.ref)
    system stop downloadActor1.ref
    expectTermination(downloadActor1.ref)
    when(helper.downloadData.findReadersForClient(client.ref))
      .thenReturn(List(downloadActor2.ref))

    system stop client.ref
    expectNoTermination(downloadActor2.ref)
  }

  it should "handle multiple download operations when watching a client" in {
    val helper = new DownloadManagerTestHelper(realDownloadData = true)
    val client = TestProbe()
    val downloadActor1 = helper.sendDownloadOperation(client.ref)
    val downloadActor2 = helper.sendDownloadOperation(client.ref)
    system stop downloadActor1.ref
    expectTermination(downloadActor1.ref)

    system stop client.ref
    expectTermination(downloadActor2.ref)
  }

  it should "stop download actors that timed out" in {
    val helper = new DownloadManagerTestHelper(realDownloadData = true)
    val downloadActor1, downloadActor2 = TestProbe()
    val now = System.currentTimeMillis()
    helper.downloadData.add(downloadActor1.ref, testActor, now - DownloadTimeout.toMillis - 1000)
    helper.downloadData.add(downloadActor2.ref, testActor, now)

    helper.sendCheckForTimeouts()
    expectTermination(downloadActor1.ref)
    expectNoTermination(downloadActor2.ref)
  }

  it should "allow updating active download actors" in {
    val helper = new DownloadManagerTestHelper(realDownloadData = true)
    val downloadActor = TestProbe()
    helper.downloadData.add(downloadActor.ref, testActor, 0)

    helper.send(DownloadActorAlive(downloadActor.ref, null))
      .sendCheckForTimeouts()
    expectNoTermination(downloadActor.ref)
  }

  it should "check for timed out download actors periodically" in {
    val helper = new DownloadManagerTestHelper

    val invocation = helper.fetchSchedulerInvocation()
    invocation.initialDelay should be(DownloadCheckInterval)
    invocation.interval should be(DownloadCheckInterval)
    invocation.message should be(DownloadManagerActor.CheckDownloadTimeout)
  }

  it should "cancel periodic download checks when it is stopped" in {
    val helper = new DownloadManagerTestHelper

    val invocation = helper.stopTestActor().fetchSchedulerInvocation()
    awaitCond(invocation.cancellable.isCancelled)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    *
    * @param realDownloadData flag whether a real download data object
    *                         ('''true''') or a mock ('''false''') should be
    *                         used
    */
  private class DownloadManagerTestHelper(realDownloadData: Boolean = false) {
    /** A mock for the download data helper object. */
    val downloadData: DownloadActorData = createDownloadData()

    /** A queue for storing scheduler invocations. */
    private val schedulerQueue =
      new LinkedBlockingQueue[RecordingSchedulerSupport.SchedulerInvocation]

    /** The test actor. */
    private val managerActor = createTestActor()

    /**
      * Sends the specified message to the test actor.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): DownloadManagerTestHelper = {
      managerActor receive msg
      this
    }

    /**
      * Sends a message about a new download operation to the test actor. For
      * this purpose, a ''TestProbe'' is created representing the download
      * actor.
      *
      * @param client the client actor
      * @return the probe for the new download actor
      */
    def sendDownloadOperation(client: ActorRef = testActor): TestProbe = {
      val downloadProbe = TestProbe()
      send(DownloadManagerActor.DownloadOperationStarted(downloadProbe.ref, client))
      downloadProbe
    }

    /**
      * Sends a message to the test actor to check for timed out download
      * actors.
      *
      * @return this test helper
      */
    def sendCheckForTimeouts(): DownloadManagerTestHelper =
      send(DownloadManagerActor.CheckDownloadTimeout)

    /**
      * Expects that a download operation was reported to the test actor and
      * returns the timestamp passed to the ''DownloadActorData'' object.
      *
      * @param downloadActor the download actor
      * @param client        the client actor
      * @return the timestamp of the download operation
      */
    def expectDownloadOperationAndGetTime(downloadActor: ActorRef, client: ActorRef = testActor):
    Long = {
      val captor = ArgumentCaptor forClass classOf[Long]
      verify(downloadData).add(argEq(downloadActor), argEq(client), captor.capture())
      captor.getValue
    }

    /**
      * Returns an invocation of the scheduler for periodic timeout checks.
      * Applies some basic checks.
      *
      * @return the invocation
      */
    def fetchSchedulerInvocation(): RecordingSchedulerSupport.SchedulerInvocation = {
      val invocation = RecordingSchedulerSupport.expectInvocation(schedulerQueue)
      invocation.receiver should be(managerActor)
      invocation
    }

    /**
      * Stops the test actor.
      *
      * @return this test helper
      */
    def stopTestActor(): DownloadManagerTestHelper = {
      system stop managerActor
      this
    }

    /**
      * Creates a mock ''DownloadActorData'' object.
      *
      * @return the mock object
      */
    private def createDownloadData(): DownloadActorData = {
      if (realDownloadData) new DownloadActorData
      else {
        val data = mock[DownloadActorData]
        when(data.findReadersForClient(testActor)).thenReturn(List.empty)
        data
      }
    }

    /**
      * Creates a test actor reference.
      *
      * @return the test actor reference
      */
    private def createTestActor(): TestActorRef[DownloadManagerActor] =
      TestActorRef(Props(new DownloadManagerActor(createConfig(), downloadData) with
        RecordingSchedulerSupport {
        override val queue: BlockingQueue[RecordingSchedulerSupport.SchedulerInvocation] =
          schedulerQueue
      }))
  }

}
