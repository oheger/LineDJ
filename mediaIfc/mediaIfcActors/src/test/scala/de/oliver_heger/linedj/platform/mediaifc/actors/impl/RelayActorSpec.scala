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

package de.oliver_heger.linedj.platform.mediaifc.actors.impl

import java.util.concurrent.{CountDownLatch, TimeUnit}
import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{AddMetaDataStateListener, RemoveMediumListener, RemoveMetaDataStateListener}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object RelayActorSpec {
  /** The prefix for lookup paths. */
  private val LookupPrefix = "akka.tcp://LineDJ-Server@192.168.0.1/user/"

  /** A test message. */
  private val Message = new Object
}

/**
 * Test class for ''RelayActor''.
 */
class RelayActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import RelayActorSpec._

  def this() = this(ActorSystem("RelayActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A RelayActor" should "create correct creation properties" in {
    val bus = mock[MessageBus]
    val props = RelayActor(LookupPrefix, bus)
    classOf[RelayActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(LookupPrefix, bus))
  }

  it should "create the expected child actors" in {
    val helper = new RemoteRelayActorTestHelper

    helper.awaitChildrenCreation() shouldBe true
  }

  it should "send an archive available message when all remote actors have been retrieved" in {
    val helper = new RemoteRelayActorTestHelper

    helper.provideRemoteActors()
  }

  it should "send the current archive state when activated" in {
    val helper = new RemoteRelayActorTestHelper
    helper.registerRemoteActor(helper.probeMediaManager)

    helper.relayActor ! RelayActor.Activate(enabled = true)
    expectMsg(MediaFacade.MediaArchiveUnavailable)
  }

  it should "send any message received from outside to the message bus" in {
    val helper = new RemoteRelayActorTestHelper
    val actor = helper.provideRemoteActors()

    actor ! Message
    expectMsg(Message)
  }

  it should "send an archive unavailable message when a remote actor is lost" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.unregisterRemoteActor(helper.probeMediaManager)
    expectMsg(MediaFacade.MediaArchiveUnavailable)
  }

  it should "monitor the archive state" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.unregisterRemoteActor(helper.probeMetaDataManager)
    expectMsg(MediaFacade.MediaArchiveUnavailable)
    helper.unregisterRemoteActor(helper.probeMediaManager)
    helper.registerRemoteActor(helper.probeMetaDataManager)
    helper.registerRemoteActor(helper.probeMediaManager)
    expectMsg(MediaFacade.MediaArchiveAvailable)
  }

  it should "allow querying the current media archive state" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors() ! RelayActor.Activate(enabled = true)
    expectMsg(MediaFacade.MediaArchiveAvailable)

    helper.relayActor ! RelayActor.QueryServerState
    expectMsg(MediaFacade.MediaArchiveAvailable)
    helper.unregisterRemoteActor(helper.probeMediaManager)
    expectMsg(MediaFacade.MediaArchiveUnavailable)
    helper.relayActor ! RelayActor.QueryServerState
    expectMsg(MediaFacade.MediaArchiveUnavailable)
  }

  it should "allow sending a message to the media manager actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors() ! RelayActor.MediaMessage(MediaActors.MediaManager,
      Message)

    helper.probeMediaManager.expectMsg(Message)
  }

  it should "allow sending a message to the meta data manager actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors() ! RelayActor.MediaMessage(MediaActors.MetaDataManager,
      Message)

    helper.probeMetaDataManager.expectMsg(Message)
  }

  it should "ignore messages to media actors not available" in {
    val helper = new RemoteRelayActorTestHelper
    helper activateAndExpectState MediaFacade.MediaArchiveUnavailable
    helper registerRemoteActor helper.probeMediaManager

    helper.relayActor ! RelayActor.MediaMessage(MediaActors.MetaDataManager, "ignore")
    helper registerRemoteActor helper.probeMetaDataManager
    expectMsg(MediaFacade.MediaArchiveAvailable)
    helper.relayActor ! RelayActor.MediaMessage(MediaActors.MetaDataManager, Message)
    helper.probeMetaDataManager.expectMsg(Message)
  }

  it should "ignore messages to media actors if not connected to the archive" in {
    val helper = new RemoteRelayActorTestHelper
    helper activateAndExpectState MediaFacade.MediaArchiveUnavailable
    helper registerRemoteActor helper.probeMediaManager

    helper.relayActor ! RelayActor.MediaMessage(MediaActors.MediaManager, "ignore")
    helper registerRemoteActor helper.probeMetaDataManager
    expectMsg(MediaFacade.MediaArchiveAvailable)
    helper.relayActor ! RelayActor.MediaMessage(MediaActors.MediaManager, Message)
    helper.probeMediaManager.expectMsg(Message)
  }

  it should "ignore invalid remote actor paths" in {
    val helper = new RemoteRelayActorTestHelper
    helper activateAndExpectState MediaFacade.MediaArchiveUnavailable
    helper registerRemoteActor helper.probeMetaDataManager

    helper.relayActor ! LookupActor.RemoteActorAvailable("invalid path", testActor)
    helper registerRemoteActor helper.probeMediaManager
    expectMsg(MediaFacade.MediaArchiveAvailable)
  }

  it should "support its deactivation" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.Activate(enabled = false)
    helper.unregisterRemoteActor(helper.probeMediaManager)
    helper.registerRemoteActor(helper.probeMediaManager)
    helper.activateAndExpectState(MediaFacade.MediaArchiveAvailable)
    helper.relayActor ! Message
    expectMsg(Message)
  }

  it should "answer a request for a remote actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.MediaActorRequest(MediaActors.MediaManager)
    expectMsg(RelayActor.MediaActorResponse(MediaActors.MediaManager, Some(helper
      .probeMediaManager.ref)))
  }

  it should "correctly answer a request for an unavailable remote actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper registerRemoteActor helper.probeMediaManager

    helper.relayActor ! RelayActor.MediaActorRequest(MediaActors.MetaDataManager)
    expectMsg(RelayActor.MediaActorResponse(MediaActors.MetaDataManager, None))
  }

  it should "handle a message to remove a meta data listener" in {
    val mid = MediumID("a medium", None)
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors() ! RelayActor.RemoveListener(mid)

    helper.probeMetaDataManager.expectMsg(RemoveMediumListener(mid, helper.relayActor))
  }

  it should "support state listener registrations" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.RegisterStateListener(ComponentID())
    helper.expectStateListenerRegistration()
  }

  it should "only add a state listener for the first listener registration" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.RegisterStateListener(ComponentID())
    helper.relayActor ! RelayActor.RegisterStateListener(ComponentID())
    helper.expectStateListenerRegistration()
    helper.expectNoMsg(MediaActors.MetaDataManager)
  }

  it should "support removing a state listener registration" in {
    val compID = ComponentID()
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.RegisterStateListener(compID)
    helper.relayActor ! RelayActor.UnregisterStateListener(compID)
    helper.expectStateListenerRegistration()
      .expectStateListenerUnRegistration()
  }

  it should "evaluate component IDs correctly for state listener registrations" in {
    val id1 = ComponentID()
    val id2 = ComponentID()
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()
    helper.relayActor ! RelayActor.RegisterStateListener(id1)
    helper.expectStateListenerRegistration()
    helper.relayActor ! RelayActor.RegisterStateListener(id1)
    helper.relayActor ! RelayActor.RegisterStateListener(id2)

    helper.relayActor ! RelayActor.UnregisterStateListener(id1)
    helper.expectNoMsg(MediaActors.MetaDataManager)
    helper.relayActor ! RelayActor.UnregisterStateListener(id2)
    helper.expectStateListenerUnRegistration()
  }

  it should "ignore unknown IDs for listener un-registrations" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.UnregisterStateListener(ComponentID())
    helper.relayActor ! RelayActor.RegisterStateListener(ComponentID())
    helper.expectStateListenerRegistration()
  }

  it should "create a registration when re-connecting to the archive if necessary" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()
    helper.relayActor ! RelayActor.RegisterStateListener(ComponentID())
    helper.expectStateListenerRegistration()

    helper.unregisterRemoteActor(helper.probeMediaManager)
    helper.registerRemoteActor(helper.probeMediaManager)
    helper.expectStateListenerRegistration()
    expectMsg(MediaFacade.MediaArchiveUnavailable)
    expectMsg(MediaFacade.MediaArchiveAvailable)
  }

  it should "remove a state listener registration on stopping" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()
    helper.relayActor ! RelayActor.RegisterStateListener(ComponentID())
    helper.expectStateListenerRegistration()

    system stop helper.relayActor
    helper.expectStateListenerUnRegistration()
  }

  it should "not remove a state listener registration on stopping if there is none" in {
    val helper = new RemoteRelayActorTestHelper
    system stop helper.provideRemoteActors()

    val probe = TestProbe()
    probe watch helper.relayActor
    probe.expectMsgType[Terminated]
    helper.probeMetaDataManager.ref ! Message
    helper.probeMetaDataManager.expectMsg(Message)
  }

  /**
    * A helper class for testing the relay actor. It manages a set of
    * dependent objects.
    */
  private class RemoteRelayActorTestHelper {
    /** Test probe for the lookup actor for the media manager. */
    val probeMediaManagerLookup: TestProbe = TestProbe()

    /** Test probe for the lookup actor for the meta data manager actor. */
    val probeMetaDataManagerLookup: TestProbe = TestProbe()

    /** Test probe for the remote media manager actor. */
    val probeMediaManager: TestProbe = TestProbe()

    /** Test probe for the remote meta data manager actor. */
    val probeMetaDataManager: TestProbe = TestProbe()

    /** A mock for the message bus. */
    val messageBus: MessageBus = createMessageBus()

    /** A map that stores references for the paths to remote actors. */
    private val pathActorMapping = Map(
      LookupPrefix + "mediaManager" ->(probeMediaManagerLookup.ref, probeMediaManager.ref),
      LookupPrefix + "metaDataManager" ->(probeMetaDataManagerLookup.ref, probeMetaDataManager.ref))

    /** A map for retrieving the lookup path for a remote actor reference. */
    private val lookupMap = pathActorMapping map { e => e._2._2 -> e._1 }

    /** A latch for synchronizing with the child actor creation. */
    private val childActorLatch = new CountDownLatch(pathActorMapping.size)

    /** Temporary map for creating child actors. */
    private var lookupPaths = pathActorMapping

    /** The test relay actor. */
    val relayActor: ActorRef = system.actorOf(createProps())

    /**
     * Waits until all expected child actors have been created and returns a
     * flag whether this was successful.
     * @return a flag whether the child actors have been created
     */
    def awaitChildrenCreation(): Boolean = {
      childActorLatch.await(5, TimeUnit.SECONDS)
    }

    /**
     * Initializes the test actor with dependencies to the remote actors to be
     * tracked. The actor is activated. It is checked whether the initial
     * server available message is received.
     * @return a reference to the test actor
     */
    def provideRemoteActors(): ActorRef = {
      pathActorMapping foreach { e => relayActor ! LookupActor.RemoteActorAvailable(e._1, e
        ._2._2) }
      relayActor ! RelayActor.Activate(enabled = true)
      expectMsg(MediaFacade.MediaArchiveAvailable)
      relayActor
    }

    /**
     * Makes the specified remote actor available. Sends a corresponding
     * message to the test actor.
     * @param probe the probe representing the remote actor
     */
    def registerRemoteActor(probe: TestProbe): Unit = {
      relayActor ! LookupActor.RemoteActorAvailable(lookupMap(probe.ref), probe.ref)
    }

    /**
     * Removes the specified remote actor. Sends a corresponding message to the
     * test actor.
     * @param probe the probe representing the remote actor
     */
    def unregisterRemoteActor(probe: TestProbe): Unit = {
      relayActor ! LookupActor.RemoteActorUnavailable(lookupMap(probe.ref))
    }

    /**
     * Sends an activation message to the relay actor and awaits the expected
     * answer.
     * @param stateMsg the expected state message
     */
    def activateAndExpectState(stateMsg: Any): Unit = {
      relayActor ! RelayActor.Activate(enabled = true)
      expectMsg(stateMsg)
    }

    /**
      * Checks that no further message has been sent to the specified target
      * actor.
      *
      * @param target the target actor
      * @return this test helper
      */
    def expectNoMsg(target: MediaActors.MediaActor): RemoteRelayActorTestHelper = {
      relayActor ! RelayActor.MediaMessage(target, Message)
      probeForTarget(target).expectMsg(Message)
      this
    }

    /**
      * Checks that the relay actor sent a state listener registration.
      *
      * @return this test helper
      */
    def expectStateListenerRegistration(): RemoteRelayActorTestHelper = {
      probeMetaDataManager.expectMsg(AddMetaDataStateListener(relayActor))
      this
    }

    /**
      * Checks that the relay actor sent a state listener un-registration.
      *
      * @return this test helper
      */
    def expectStateListenerUnRegistration(): RemoteRelayActorTestHelper = {
      probeMetaDataManager.expectMsg(RemoveMetaDataStateListener(relayActor))
      this
    }

    /**
      * Returns the correct test probe for the specified target media actor.
      *
      * @param target the target
      * @return the test probe
      */
    private def probeForTarget(target: MediaActors.MediaActor): TestProbe =
    target match {
      case MediaActors.MediaManager => probeMediaManager
      case MediaActors.MetaDataManager => probeMetaDataManager
    }

    /**
     * Creates a properties object for the test relay actor.
     * @return the properties for creating the test relay actor
     */
    private def createProps(): Props = {
      Props(new RelayActor(LookupPrefix, messageBus) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() should be(classOf[LookupActor])
          p.args should have size 3
          lookupPaths should contain key p.args.head
          p.args(1) should be(relayActor)
          childActorLatch.countDown()
          val path = p.args.head.toString
          val child = lookupPaths(path)._1
          lookupPaths -= path
          child
        }
      })
    }

    /**
     * Creates a mock for the message bus. This mock simply passes all messages
     * to be published to the test actor.
     * @return the mock message bus
     */
    private def createMessageBus(): MessageBus = {
      val bus = mock[MessageBus]
      when(bus.publish(any())).thenAnswer((invocationOnMock: InvocationOnMock) => {
        testActor ! invocationOnMock.getArguments()(0)
        true
      })
      bus
    }
  }

}
