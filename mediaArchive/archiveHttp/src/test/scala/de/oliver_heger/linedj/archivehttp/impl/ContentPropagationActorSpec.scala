/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.StateTestHelper
import de.oliver_heger.linedj.shared.archive.union.RemovedArchiveComponentProcessed
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ContentPropagationActorSpec {
  /** The URI of the test archive. */
  private val ArchiveID = "test-music-archive"

  /** The sequence number. */
  private val SeqNo = 20180619
}

/**
  * Test class for ''ContentPropagationActor''.
  */
class ContentPropagationActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("ContentPropagationActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import ContentPropagationActorSpec._

  /**
    * Checks whether all messages defined the message data object were sent to
    * the given test probe.
    *
    * @param target   the receiving test probe
    * @param messages the expected messages
    */
  private def verifyMessages(target: TestProbe, messages: MessageData): Unit = {
    messages.messages foreach { m =>
      target.expectMsg(m)
    }
  }

  /**
    * Creates a mock for a ''MediumProcessingResult''.
    *
    * @return the mock result
    */
  private def createResult(): MediumProcessingResult = {
    val result = mock[MediumProcessingResult]
    when(result.seqNo).thenReturn(SeqNo)
    result
  }

  "A ContentPropagationActor" should "use a correct propagation service" in {
    val mediaManager = TestProbe().ref
    val metaManager = TestProbe().ref
    val actor = TestActorRef[ContentPropagationActor](Props(classOf[ContentPropagationActor],
      mediaManager, metaManager, ArchiveID))

    actor.underlyingActor.propagationService should be(ContentPropagationUpdateServiceImpl)
  }

  it should "handle medium results" in {
    val result1 = createResult()
    val result2 = createResult()
    val helper = new PropagationActorTestHelper
    val sendMsg1 = List(MessageData(helper.actors.mediaManager,
      Seq("foo", "bar")), MessageData(helper.actors.metaManager, Seq("baz")))
    val sendMsg2 = List(MessageData(helper.actors.metaManager, Seq("test")))
    val state1 = ContentPropagationUpdateServiceImpl.InitialState.copy(messages = sendMsg1)
    val state2 = state1.copy(messages = sendMsg2)

    helper.stub(sendMsg1: Iterable[MessageData], state1) { svc =>
      svc.handleMediumProcessed(result1, helper.actors, ArchiveID, remove = true)
    }
      .stub(sendMsg2: Iterable[MessageData], state2) { svc =>
        svc.handleMediumProcessed(result2, helper.actors, ArchiveID, remove = false)
      }
      .send(PropagateMediumResult(result1, removeContent = true))
      .expectStateUpdate(ContentPropagationUpdateServiceImpl.InitialState)
      .send(PropagateMediumResult(result2, removeContent = false))
      .expectStateUpdate(state1)

    verifyMessages(helper.probeMediaManager, sendMsg1.head)
    verifyMessages(helper.probeMetaManager, sendMsg1(1))
    verifyMessages(helper.probeMetaManager, sendMsg2.head)
  }

  it should "handle a remove confirmation" in {
    val confirm = RemovedArchiveComponentProcessed(ArchiveID)
    val result = createResult()
    val helper = new PropagationActorTestHelper
    val sendMsg1 = List(MessageData(helper.actors.mediaManager, Seq("confirm")))
    val sendMsg2 = List(MessageData(helper.actors.metaManager, Seq("data")))
    val state1 = ContentPropagationUpdateServiceImpl.InitialState.copy(messages = sendMsg1)
    val state2 = state1.copy(messages = sendMsg2)

    helper.stub(sendMsg1: Iterable[MessageData], state1) { svc => svc.handleRemovalConfirmed() }
      .stub(sendMsg2: Iterable[MessageData], state2) { svc =>
        svc.handleMediumProcessed(result, helper.actors, ArchiveID, remove = false)
      }
      .send(confirm)
      .expectStateUpdate(ContentPropagationUpdateServiceImpl.InitialState)
      .send(PropagateMediumResult(result, removeContent = false))
      .expectStateUpdate(state1)
    verifyMessages(helper.probeMediaManager, sendMsg1.head)
    verifyMessages(helper.probeMetaManager, sendMsg2.head)
  }

  /**
    * A test helper class managing a test actor instance and its dependencies.
    */
  private class PropagationActorTestHelper
    extends StateTestHelper[ContentPropagationState, ContentPropagationUpdateService] {
    /** The mock for the state update service to be used by tests. */
    override val updateService: ContentPropagationUpdateService = mock[ContentPropagationUpdateService]

    /** Test probe for the media manager. */
    val probeMediaManager: TestProbe = TestProbe()

    /** Test probe for the meta data manager. */
    val probeMetaManager: TestProbe = TestProbe()

    /** The actor to be tested. */
    private val propagationActor = createTestActor()

    /**
      * An object with actors involved in content propagation.
      */
    val actors: PropagationActors = PropagationActors(probeMediaManager.ref,
      probeMetaManager.ref, testActor)

    /**
      * Sends the given message to the test actor.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): PropagationActorTestHelper = {
      propagationActor ! msg
      this
    }

    /**
      * Creates a test actor instance.
      *
      * @return the test actor
      */
    private def createTestActor(): ActorRef =
      system.actorOf(Props(classOf[ContentPropagationActor], updateService, probeMediaManager.ref,
        probeMetaManager.ref, ArchiveID))
  }

}
