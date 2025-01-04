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
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

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
    val archiveConfigs = List(mock[MediaArchiveConfig], mock[MediaArchiveConfig])

    val props = ArchiveGroupActor(mediaUnionActor, metaDataUnionActor, archiveConfigs)
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ArchiveActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ArchiveGroupActor].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should have size 4
    props.args.head should be(mediaUnionActor)
    props.args(1) should be(metaDataUnionActor)
    props.args(2) should be(archiveConfigs)
    props.args(3) should be(GroupScanStateServiceImpl)

  it should "create and initialize the archives in the group" in:
    val helper = new GroupActorTestHelper

    helper.verifyArchiveInitialization()

  it should "handle a media scan request from the group" in:
    val probeTarget = TestProbe()
    val state = GroupScanState(currentScanRequest = Some(probeTarget.ref), pendingScanRequests = Set.empty,
      scanInProgress = false)
    val helper = new GroupActorTestHelper

    helper.stub(Option(probeTarget.ref), state)(_.handleScanRequest(testActor))
      .post(ScanAllMedia)
      .expectStateUpdate(GroupScanStateServiceImpl.InitialState)
    probeTarget.expectMsg(StartMediaScan)

  it should "handle a media scan request if no scan should be triggered" in:
    val state1 = GroupScanState(currentScanRequest = None, pendingScanRequests = Set.empty,
      scanInProgress = true)
    val state2 = GroupScanState(currentScanRequest = None, pendingScanRequests = Set(TestProbe().ref),
      scanInProgress = true)
    val sender2 = TestProbe().ref
    val helper = new GroupActorTestHelper

    helper.stub(Option(TestProbe().ref), state1)(_.handleScanRequest(testActor))
      .stub(Option[ActorRef](null), state2)(_.handleScanRequest(sender2))
      .post(ScanAllMedia)
      .expectStateUpdate(GroupScanStateServiceImpl.InitialState)
      .post(ScanAllMedia, sender2)
      .expectStateUpdate(state1)

  it should "handle a notification about a completed scan operation" in:
    val probeTarget = TestProbe()
    val state = GroupScanState(currentScanRequest = Some(probeTarget.ref), pendingScanRequests = Set.empty,
      scanInProgress = false)
    val helper = new GroupActorTestHelper

    helper.stub(Option(probeTarget.ref), state)(_.handleScanCompleted())
      .post(MediaScanCompleted)
      .expectStateUpdate(GroupScanStateServiceImpl.InitialState)
    probeTarget.expectMsg(StartMediaScan)

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
      * The test probes for the media manager actors of the archives in the
      * group.
      */
    private val mediaManagers = List(TestProbe(), TestProbe())

    /** The actor to be tested. */
    private val groupActor = createTestActor()

    /**
      * Verifies that all archives have been created and initialized.
      */
    def verifyArchiveInitialization(): Unit =
      mediaManagers foreach { manager =>
        manager.expectMsg(ScanAllMedia)
      }

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
      * Creates the actor to be tested. It uses a mock archive actor factory to
      * inject test probes as media manager actors.
      *
      * @return the test actor
      */
    private def createTestActor(): ActorRef =
      system.actorOf(Props(new ArchiveGroupActor(mediaUnionActor, metaDataUnionActor, archiveConfigs,
        updateService) with ArchiveActorFactory with ChildActorFactory {
        override def createArchiveActors(refMediaUnionActor: ActorRef, metadataUnionActor: ActorRef,
                                         groupManager: ActorRef, archiveConfig: MediaArchiveConfig): ActorRef = {
          refMediaUnionActor should be(mediaUnionActor)
          metadataUnionActor should be(metaDataUnionActor)
          groupManager should be(self)
          if archiveConfig == archiveConfigs.head then mediaManagers.head.ref
          else if archiveConfig == archiveConfigs(1) then mediaManagers(1).ref
          else fail("Unexpected archive config: " + archiveConfig)
        }
      }))

