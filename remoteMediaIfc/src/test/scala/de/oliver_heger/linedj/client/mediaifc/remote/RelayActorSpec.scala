/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.client.mediaifc.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.client.ActorSystemTestHelper
import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.client.mediaifc.MediaActors
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object RelayActorSpec {
  /** The address of the remote system. */
  private val Host = "remoteHost"

  /** The port of the remote system. */
  private val Port = 1414

  /** A test message. */
  private val Message = new Object
}

/**
 * Test class for ''RelayActor''.
 */
class RelayActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import RelayActorSpec._

  def this() = this(ActorSystemTestHelper createActorSystem "RelayActorSpec")

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A RelayActor" should "create correct creation properties" in {
    val bus = mock[MessageBus]
    val props = RelayActor(Host, Port, bus)
    classOf[RelayActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should have size 3
    props.args should contain inOrderOnly(Host, Port, bus)
  }

  it should "create the expected child actors" in {
    val helper = new RemoteRelayActorTestHelper

    helper.awaitChildrenCreation() shouldBe true
  }

  it should "send a ServerAvailable message when all remote actors have been retrieved" in {
    val helper = new RemoteRelayActorTestHelper

    helper.provideRemoteActors()
  }

  it should "send the current server state when activated" in {
    val helper = new RemoteRelayActorTestHelper
    helper.registerRemoteActor(helper.probeMediaManager)

    helper.relayActor ! RelayActor.Activate(enabled = true)
    expectMsg(RelayActor.ServerUnavailable)
  }

  it should "send any message received from outside to the message bus" in {
    val helper = new RemoteRelayActorTestHelper
    val actor = helper.provideRemoteActors()

    actor ! Message
    expectMsg(Message)
  }

  it should "send a server unavailable message when a remote actor is lost" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.unregisterRemoteActor(helper.probeMediaManager)
    expectMsg(RelayActor.ServerUnavailable)
  }

  it should "monitor the server state" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.unregisterRemoteActor(helper.probeMetaDataManager)
    expectMsg(RelayActor.ServerUnavailable)
    helper.unregisterRemoteActor(helper.probeMediaManager)
    helper.registerRemoteActor(helper.probeMetaDataManager)
    helper.registerRemoteActor(helper.probeMediaManager)
    expectMsg(RelayActor.ServerAvailable)
  }

  it should "allow querying the current server state" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors() ! RelayActor.Activate(enabled = true)
    expectMsg(RelayActor.ServerAvailable)

    helper.relayActor ! RelayActor.QueryServerState
    expectMsg(RelayActor.ServerAvailable)
    helper.unregisterRemoteActor(helper.probeMediaManager)
    expectMsg(RelayActor.ServerUnavailable)
    helper.relayActor ! RelayActor.QueryServerState
    expectMsg(RelayActor.ServerUnavailable)
  }

  it should "allow sending a message to the media manager actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors() ! RelayActor.RemoteMessage(MediaActors.MediaManager,
      Message)

    helper.probeMediaManager.expectMsg(Message)
  }

  it should "allow sending a message to the meta data manager actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors() ! RelayActor.RemoteMessage(MediaActors.MetaDataManager,
      Message)

    helper.probeMetaDataManager.expectMsg(Message)
  }

  it should "ignore messages to remote actors not available" in {
    val helper = new RemoteRelayActorTestHelper
    helper activateAndExpectState RelayActor.ServerUnavailable
    helper registerRemoteActor helper.probeMediaManager

    helper.relayActor ! RelayActor.RemoteMessage(MediaActors.MetaDataManager, "ignore")
    helper registerRemoteActor helper.probeMetaDataManager
    expectMsg(RelayActor.ServerAvailable)
    helper.relayActor ! RelayActor.RemoteMessage(MediaActors.MetaDataManager, Message)
    helper.probeMetaDataManager.expectMsg(Message)
  }

  it should "ignore invalid remote actor paths" in {
    val helper = new RemoteRelayActorTestHelper
    helper activateAndExpectState RelayActor.ServerUnavailable
    helper registerRemoteActor helper.probeMetaDataManager

    helper.relayActor ! LookupActor.RemoteActorAvailable("invalid path", testActor)
    helper registerRemoteActor helper.probeMediaManager
    expectMsg(RelayActor.ServerAvailable)
  }

  it should "support its deactivation" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.Activate(enabled = false)
    helper.unregisterRemoteActor(helper.probeMediaManager)
    helper.registerRemoteActor(helper.probeMediaManager)
    helper.activateAndExpectState(RelayActor.ServerAvailable)
    helper.relayActor ! Message
    expectMsg(Message)
  }

  it should "answer a request for a remote actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper.provideRemoteActors()

    helper.relayActor ! RelayActor.RemoteActorRequest(MediaActors.MediaManager)
    expectMsg(RelayActor.RemoteActorResponse(MediaActors.MediaManager, Some(helper
      .probeMediaManager.ref)))
  }

  it should "correctly answer a request for an unavailable remote actor" in {
    val helper = new RemoteRelayActorTestHelper
    helper registerRemoteActor helper.probeMediaManager

    helper.relayActor ! RelayActor.RemoteActorRequest(MediaActors.MetaDataManager)
    expectMsg(RelayActor.RemoteActorResponse(MediaActors.MetaDataManager, None))
  }

  /**
   * A helper class for testing the relay actor. It manages a set of
   * dependent objects.
   */
  private class RemoteRelayActorTestHelper {
    /** Test probe for the lookup actor for the media manager. */
    val probeMediaManagerLookup = TestProbe()

    /** Test probe for the lookup actor for the meta data manager actor. */
    val probeMetaDataManagerLookup = TestProbe()

    /** Test probe for the remote media manager actor. */
    val probeMediaManager = TestProbe()

    /** Test probe for the remote meta data manager actor. */
    val probeMetaDataManager = TestProbe()

    /** A mock for the message bus. */
    val messageBus = createMessageBus()

    /** The test relay actor. */
    val relayActor = system.actorOf(createProps())

    /** The prefix for lookup paths. */
    private val LookupPrefix = "akka.tcp://LineDJ-Server@" + Host + ":" + Port + "/user/"

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
      expectMsg(RelayActor.ServerAvailable)
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
     * Creates a properties object for the test relay actor.
     * @return the properties for creating the test relay actor
     */
    private def createProps(): Props = {
      Props(new RelayActor(Host, Port, messageBus) with ChildActorFactory {
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
      when(bus.publish(org.mockito.Matchers.any())).then(new Answer[Boolean] {
        override def answer(invocationOnMock: InvocationOnMock): Boolean = {
          testActor ! invocationOnMock.getArguments()(0)
          true
        }
      })
      bus
    }
  }

}
