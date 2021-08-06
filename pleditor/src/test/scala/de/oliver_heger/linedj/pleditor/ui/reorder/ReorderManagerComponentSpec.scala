/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.reorder

import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Await
import scala.concurrent.duration._

object ReorderManagerComponentSpec {
  /** A name for a test reorder service. */
  private val ServiceName = "ReorderServiceTestName"
}

/**
  * Test class for ''ReorderManagerComponent''.
  */
class ReorderManagerComponentSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ReorderManagerComponentSpec._

  def this() = this(ActorSystem("ReorderManagerComponentSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a mock reorder service.
    *
    * @return the mock service
    */
  private def createReorderService(): PlaylistReorderer = {
    val service = mock[PlaylistReorderer]
    when(service.name).thenReturn(ServiceName)
    service
  }

  "A ReorderManagerComponent" should "report new services to the management actor" in {
    val reorder = createReorderService()
    val helper = new ReorderManagerComponentTestHelper

    helper.component reorderServiceAdded reorder
    helper.reorderManagerActor.expectMsg(ReorderManagerActor.AddReorderService(reorder,
      ServiceName))
  }

  it should "ignore new services that fail when asked for their name" in {
    val reorder = mock[PlaylistReorderer]
    when(reorder.name).thenThrow(new RuntimeException("Test exception"))
    val helper = new ReorderManagerComponentTestHelper

    helper.component reorderServiceAdded reorder
    val ping = "Ping" // test that no other message was sent
    helper.reorderManagerActor.ref ! ping
    helper.reorderManagerActor.expectMsg(ping)
  }

  it should "report removed services to the management actor" in {
    val reorder = createReorderService()
    val helper = new ReorderManagerComponentTestHelper

    helper.component reorderServiceRemoved reorder
    helper.reorderManagerActor.expectMsg(ReorderManagerActor.RemoveReorderService(reorder))
  }

  it should "stop the management actor when the component gets deactivated" in {
    val context = mock[ComponentContext]
    val helper = new ReorderManagerComponentTestHelper

    helper.component deactivate context
    verifyZeroInteractions(context)
    val watcher = TestProbe()
    watcher watch helper.reorderManagerActor.ref
    watcher.expectMsgType[Terminated]
  }

  it should "implement the loadAvailableReorderServices() method" in {
    val serviceList = List((mock[PlaylistReorderer], ServiceName), (mock[PlaylistReorderer],
      "other"))
    val props = Props(new Actor {
      override def receive: Receive = {
        case ReorderManagerActor.GetAvailableReorderServices =>
          sender() ! ReorderManagerActor.AvailableReorderServices(serviceList)
      }
    })
    val managerActor = system.actorOf(props, "TestReorderManagementActor")
    val helper = new ReorderManagerComponentTestHelper(Some(managerActor))

    val future = helper.component.loadAvailableReorderServices()
    Await.result(future, 5.seconds) should be(serviceList)
  }

  it should "implement the reorder() method" in {
    val reorder = createReorderService()
    val songs = List(mock[SongData], mock[SongData])
    val startIndex = 8
    val helper = new ReorderManagerComponentTestHelper

    helper.component.reorder(reorder, songs, startIndex)
    helper.reorderManagerActor.expectMsg(ReorderManagerActor.ReorderServiceInvocation(reorder,
      ReorderActor.ReorderRequest(songs, startIndex)))
  }

  /**
    * A test helper class collecting all dependent objects.
    *
    * @param optManagerActor an optional actor to be returned by the mock
    *                        actor factory
    */
  class ReorderManagerComponentTestHelper(optManagerActor: Option[ActorRef] = None) extends
    ActorFactory(system) {
    /** The application context. */
    val context: ClientApplicationContext = createClientApplicationContext()

    /** The probe representing the management actor. */
    val reorderManagerActor: TestProbe = TestProbe()

    /** Counts the invocations of the actor factory. */
    private val factoryCount = new AtomicInteger

    /** The test component. */
    val component = new ReorderManagerComponent

    component initClientApplicationContext context

    /**
      * @inheritdoc This implementation allows creating exactly one management
      *             actor. The test probe is returned.
      */
    override def createActor(props: Props, name: String): ActorRef = {
      props should be(ReorderManagerActor(context.messageBus))
      name should be("ReorderManagerActor")
      if (factoryCount.incrementAndGet() > 1) {
        fail("Too many invocations of actor factory!")
      }
      optManagerActor getOrElse reorderManagerActor.ref
    }

    /**
      * Creates a mock for the client application context.
      *
      * @return the mock context
      */
    private def createClientApplicationContext(): ClientApplicationContext = {
      val context = mock[ClientApplicationContext]
      val bus = mock[MessageBus]
      when(context.messageBus).thenReturn(bus)
      when(context.actorFactory).thenReturn(this)
      when(context.actorSystem).thenReturn(system)
      context
    }
  }

}
