/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.SupervisionTestActor
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ReorderManagerActorSpec {
  /** A name for a reorder service. */
  private val ServiceName = "Test reorder service"
}

/**
  * Test class for ''ReorderManagerActor''.
  */
class ReorderManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ReorderManagerActorSpec._

  def this() = this(ActorSystem("ReorderManagerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem  system
  }

  "A ReorderManagerActor" should "create correct Props" in {
    val bus = mock[MessageBus]

    val props = ReorderManagerActor(bus)
    classOf[ReorderManagerActor] isAssignableFrom props.actorClass() shouldBe true
    classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
    props.args should have length 1
    props.args.head should be(bus)
  }

  it should "have no reorder services initially" in {
    val helper = new ReorderManagerActorTestHelper

    helper.actor ! ReorderManagerActor.GetAvailableReorderServices
    expectMsgType[ReorderManagerActor.AvailableReorderServices].services shouldBe empty
  }

  it should "allow adding reorder services" in {
    val reorder = mock[PlaylistReorderer]
    val helper = new ReorderManagerActorTestHelper

    helper.addReorderService(reorder, ServiceName)
    helper.actor ! ReorderManagerActor.GetAvailableReorderServices
    val msg = expectMsgType[ReorderManagerActor.AvailableReorderServices]
    msg.services should have length 1
    msg.services should contain(reorder, ServiceName)
  }

  it should "allow removing reorder services" in {
    val reorder = mock[PlaylistReorderer]
    val reorderOther = mock[PlaylistReorderer]
    val helper = new ReorderManagerActorTestHelper
    helper.addReorderService(reorder, ServiceName)
    helper.addReorderService(reorderOther, ServiceName + "_other")

    helper removeReorderService reorder
    helper.actor ! ReorderManagerActor.GetAvailableReorderServices
    val msg = expectMsgType[ReorderManagerActor.AvailableReorderServices]
    msg.services should have length 1
    msg.services should not contain(reorder, ServiceName)
  }

  it should "stop the associated child actor when a service is removed" in {
    val reorder = mock[PlaylistReorderer]
    val helper = new ReorderManagerActorTestHelper
    helper.addReorderService(reorder, ServiceName)

    val probe = TestProbe()
    val child = helper removeReorderService reorder
    probe watch child.ref
    probe.expectMsgType[Terminated].actor should be(child.ref)
  }

  it should "handle a remove request for an unknown service gracefully" in {
    val helper = new ReorderManagerActorTestHelper

    helper send ReorderManagerActor.RemoveReorderService(mock[PlaylistReorderer])
  }

  /**
    * Creates a message for invoking the specified reorder service.
    *
    * @param reorder the reorder service
    * @return the message for invoking this service
    */
  private def createReorderMessage(reorder: PlaylistReorderer): ReorderManagerActor
  .ReorderServiceInvocation =
    ReorderManagerActor.ReorderServiceInvocation(reorder,
      ReorderActor.ReorderRequest(createSongList(), 2))

  /**
    * Creates a sequence with some test songs.
    *
    * @return the sequence with test songs
    */
  private def createSongList(): List[SongData] =
    List(mock[SongData], mock[SongData])

  it should "allow invoking a reorder service" in {
    val reorder = mock[PlaylistReorderer]
    val invocation = createReorderMessage(reorder)
    val response = ReorderActor.ReorderResponse(createSongList(), invocation.request)
    val helper = new ReorderManagerActorTestHelper
    val child = helper.addReorderService(reorder, ServiceName)

    helper send invocation
    child.expectMsg(invocation.request)
    helper send response
    helper expectMessageOnBus response
  }

  it should "send an error response for an unknown reorder service" in {
    val reorder = mock[PlaylistReorderer]
    val helper = new ReorderManagerActorTestHelper

    helper send createReorderMessage(reorder)
    helper expectMessageOnBus ReorderManagerActor.FailedReorderServiceInvocation(reorder)
  }

  it should "react on a failed invocation via a child actor" in {
    val reorder = mock[PlaylistReorderer]
    val helper = new ReorderManagerActorTestHelper
    val child = helper.addReorderService(reorder, ServiceName)

    system stop child.ref
    awaitCond {
      helper.actor ! ReorderManagerActor.GetAvailableReorderServices
      expectMsgType[ReorderManagerActor.AvailableReorderServices].services.isEmpty
    }
    helper expectMessageOnBus ReorderManagerActor.FailedReorderServiceInvocation(reorder)
  }

  it should "use a supervision strategy that stops a failing child actor" in {
    val helper = new ReorderManagerActorTestHelper
    val strategy = helper.actor.underlyingActor.supervisorStrategy

    strategy shouldBe a[OneForOneStrategy]
    val supervisionTestActor = SupervisionTestActor(system, strategy, Props(new Actor {
      override def receive: Receive = {
        case ServiceName =>
          throw new RuntimeException("Service not available!")
      }
    }))
    val probe = TestProbe()
    val childActor = supervisionTestActor.underlyingActor.childActor
    probe watch childActor

    childActor ! ServiceName
    probe.expectMsgType[Terminated]
  }

  /**
    * A test helper class which collects required mock and helper objects
    * needed by the actor under test.
    */
  class ReorderManagerActorTestHelper {
    /** The mock message bus. */
    val messageBus: MessageBus = mock[MessageBus]

    /** A map with managed services and the corresponding child actors. */
    val managedServices = collection.mutable.Map.empty[PlaylistReorderer, TestProbe]

    /** Test reference to the test actor. */
    val actor: TestActorRef[ReorderManagerActor] = TestActorRef[ReorderManagerActor](createProps(messageBus))

    /**
      * Sends the specified message (synchronously) to the test actor.
      *
      * @param msg the message to be sent
      */
    def send(msg: Any): Unit = {
      actor receive msg
    }

    /**
      * Makes the specified reorder service available to the test actor. The
      * test probe returned by the child actor factory is returned.
      *
      * @param reorder the reorder service
      * @param name    the name of the service
      * @return the probe representing the wrapping child actor
      */
    def addReorderService(reorder: PlaylistReorderer, name: String): TestProbe = {
      send(ReorderManagerActor.AddReorderService(reorder, name))
      managedServices(reorder)
    }

    /**
      * Tells the test actor that the specified reorder service is gone. The
      * test probe representing the wrapping child actor is returned.
      *
      * @param reorder the reorder service to be removed
      * @return the probe representing the wrapping child actor
      */
    def removeReorderService(reorder: PlaylistReorderer): TestProbe = {
      send(ReorderManagerActor.RemoveReorderService(reorder))
      managedServices.remove(reorder).get
    }

    /**
      * Checks whether the specified message has been published on the
      * message bus.
      *
      * @param msg the expected message
      */
    def expectMessageOnBus(msg: Any): Unit = {
      verify(messageBus).publish(msg)
    }

    /**
      * Creates a ''Props'' object for creating a test actor instance. Here a
      * ''ChildActorFactory'' is used which stores the association to the
      * reorder service and returns a test probe.
      *
      * @param bus the message bus
      * @return the ''Props'' for the test actor
      */
    private def createProps(bus: MessageBus): Props = {
      Props(new ReorderManagerActor(bus) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() should be(classOf[ReorderActor])
          p.args should have length 1
          val reorder = p.args.head.asInstanceOf[PlaylistReorderer]
          managedServices should not contain reorder
          val probe = TestProbe()
          managedServices += reorder -> probe
          probe.ref
        }
      })
    }
  }

}
