/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archive.group

import de.oliver_heger.linedj.StateTestHelper
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.shared.archive.media.{MediaScanCompleted, ScanAllMedia, StartMediaScan}
import de.oliver_heger.linedj.shared.archive.metadata
import de.oliver_heger.linedj.shared.archive.metadata.MetadataProcessingEvent
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, typed}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/**
  * Test class for ''ArchiveGroupActor''.
  */
class ArchiveGroupActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ArchiveGroupActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  "An ArchiveGroupActor" should "return correct Props" in:
    val mediaUnionActor = TestProbe().ref
    val metaDataUnionActor = TestProbe().ref
    val listenerBehavior = mock[Behavior[MetadataProcessingEvent]]
    val archiveConfigs = List(mock[MediaArchiveConfig], mock[MediaArchiveConfig])

    val props = ArchiveGroupActor(mediaUnionActor, metaDataUnionActor, listenerBehavior, archiveConfigs)
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ArchiveActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ArchiveGroupActor].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should have size 5
    props.args.head should be(mediaUnionActor)
    props.args(1) should be(metaDataUnionActor)
    props.args(2) should be(listenerBehavior)
    props.args(3) should be(archiveConfigs)
    props.args(4) should be(GroupScanStateServiceImpl)

  it should "create and initialize the archives in the group" in:
    val helper = new GroupActorTestHelper

    helper.verifyArchiveScanStarted()

  it should "handle a notification about a completed scan operation" in:
    val probeTarget = TestProbe()
    val state = GroupScanState(currentScanRequest = Some(probeTarget.ref), pendingScanRequests = Set.empty,
      scanInProgress = false)
    val helper = new GroupActorTestHelper

    helper.stub(Option(probeTarget.ref), state)(_.handleScanCompleted())
      .post(MediaScanCompleted)
      .expectStateUpdate(GroupScanStateServiceImpl.InitialState)
    probeTarget.expectMsg(StartMediaScan)

  it should "handle a new ScanAllMedia request" in:
    val stateInProgress = GroupScanStateServiceImpl.InitialState.copy(scanInProgress = true)
    val helper = new GroupActorTestHelper

    helper.verifyArchiveScanStarted()
      .expectStateUpdate(GroupScanStateServiceImpl.InitialState)
      .post(ScanAllMedia)
      .verifyArchiveScanStarted()
      .expectStateUpdate(stateInProgress)

  it should "instantiate and propagate a correct event listener actor" in:
    val helper = new GroupActorTestHelper

    helper.testMetadataEventListener()

  it should "send a ProcessingCompleted event when no more scans are in progress" in:
    val nextState = GroupScanState(currentScanRequest = None, pendingScanRequests = Set.empty, scanInProgress = false)
    val helper = new GroupActorTestHelper

    helper.testMetadataEventListener()
      .stub(None, nextState)(_.handleScanCompleted())
      .post(MediaScanCompleted)
      .expectProcessingCompletedEvent()

  /**
    * Test helper class that manages a test actor and its dependencies.
    */
  private class GroupActorTestHelper extends StateTestHelper[GroupScanState, GroupScanStateService]:
    override val updateService: GroupScanStateService = mock[GroupScanStateService]

    /** The media manager of the union archive. */
    private val mediaUnionActor = TestProbe().ref

    /** The metadata manager of the union archive. */
    private val metaDataUnionActor = TestProbe().ref

    /** A list with the configs for the archives in the group. */
    private val archiveConfigs = List(mock[MediaArchiveConfig], mock[MediaArchiveConfig])

    /**
      * A queue that is populated by the test behavior for the metadata event
      * listener actor. This is used to test whether the listener actor is
      * correctly spawned.
      */
    private val receivedMetadataEvents = new LinkedBlockingQueue[MetadataProcessingEvent]

    /**
      * The test probes for the media manager actors of the archives in the
      * group.
      */
    private val mediaManagers = List(TestProbe(), TestProbe())

    /**
      * A reference for storing the metadata event listener actor passed to the
      * archive actor factory.
      */
    private val refMetadataListener = new AtomicReference[typed.ActorRef[MetadataProcessingEvent]]

    stub(
      Option(mediaManagers.head.ref),
      GroupScanStateServiceImpl.InitialState.copy(scanInProgress = true)
    ) { service =>
      service.handleScanRequest(mediaManagers.map(_.ref))
    }

    /** The actor to be tested. */
    private val groupActor = createTestActor()

    /**
      * Verifies that the first archive actor is sent a scan request.
      *
      * @return this test helper
      */
    def verifyArchiveScanStarted(): GroupActorTestHelper =
      mediaManagers.head.expectMsg(StartMediaScan)
      this

    /**
      * Passes the given message to the group test actor.
      *
      * @param msg    the message
      * @param caller the actor that sends the message
      * @return this test helper
      */
    def post(msg: Any, caller: ActorRef = testActor): GroupActorTestHelper =
      groupActor.tell(msg, caller)
      this

    /**
      * Tests whether a correct metadata event listener has been created and
      * passed to the archive actor factory.
      *
      * @return this test helper
      */
    def testMetadataEventListener(): GroupActorTestHelper =
      awaitCond(refMetadataListener.get() != null)
      val listener = refMetadataListener.get()

      val event = MetadataProcessingEvent.ProcessingStarts(groupActor)
      nextMetadataEvent() should be(event)
      this

    /**
      * Tests whether the metadata listener has received a correct event
      * indicating the end of processing.
      *
      * @return this test helper
      */
    def expectProcessingCompletedEvent(): GroupActorTestHelper =
      nextMetadataEvent() should be(MetadataProcessingEvent.ProcessingCompleted(groupActor))
      this

    /**
      * Checks whether an event was sent to the metadata listener and returns
      * it.
      *
      * @return the event received by the listener
      */
    private def nextMetadataEvent(): MetadataProcessingEvent =
      receivedMetadataEvents.poll(3, TimeUnit.SECONDS)

    /**
      * Returns a [[Behavior]] for a test metadata processing listener actor
      * that passes the received event to a queue. This allows testing whether
      * the listener actor is correctly instantiated.
      *
      * @return the behavior for the test metadata event listener actor
      */
    private def handleMetadataEvent(): Behavior[MetadataProcessingEvent] =
      Behaviors.receiveMessage { event =>
        receivedMetadataEvents.offer(event)
        Behaviors.same
      }

    /**
      * Creates the actor to be tested. It uses a mock archive actor factory to
      * inject test probes as media manager actors.
      *
      * @return the test actor
      */
    private def createTestActor(): ActorRef =
      system.actorOf(Props(new ArchiveGroupActor(
        mediaUnionActor,
        metaDataUnionActor,
        handleMetadataEvent(),
        archiveConfigs,
        updateService
      ) with ArchiveActorFactory with ChildActorFactory {
        override def createArchiveActors(refMediaUnionActor: ActorRef,
                                         metadataUnionActor: ActorRef,
                                         metadataListener: typed.ActorRef[MetadataProcessingEvent],
                                         archiveConfig: MediaArchiveConfig): ActorRef = {
          refMediaUnionActor should be(mediaUnionActor)
          metadataUnionActor should be(metaDataUnionActor)
          refMetadataListener.set(metadataListener)
          if archiveConfig == archiveConfigs.head then mediaManagers.head.ref
          else if archiveConfig == archiveConfigs(1) then mediaManagers(1).ref
          else fail("Unexpected archive config: " + archiveConfig)
        }
      }))
