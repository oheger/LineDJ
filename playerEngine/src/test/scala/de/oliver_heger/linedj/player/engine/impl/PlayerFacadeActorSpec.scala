/*
 * Copyright 2015-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.impl

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport}
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import de.oliver_heger.linedj.player.engine.impl.PlayerFacadeActor.TargetPlaybackActor
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerConfig}
import de.oliver_heger.linedj.player.engine.impl.PlaybackActor.{AddPlaybackContextFactory, RemovePlaybackContextFactory}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.reflect.ClassTag

object PlayerFacadeActorSpec {
  /** The classes of child actors created dynamically. */
  private val DynamicChildrenClasses = List(classOf[LocalBufferActor],
    classOf[SourceDownloadActor], classOf[SourceReaderActor],
    classOf[PlaybackActor])

  /** Prefix for buffer files. */
  private val BufferFilePrefix = "TestBufferFilePrefix"

  /** Extension for buffer files. */
  private val BufferFileExt = ".buf"

  /** The number of child actors that may be replaced. */
  private val DynamicChildrenCount = DynamicChildrenClasses.size

  /** The number of child actors that remain constant. */
  private val StaticChildrenCount = 1

  /** The total number of child actors created initially. */
  private val TotalChildrenCount = DynamicChildrenCount + StaticChildrenCount

  /**
    * Data class for keeping track of child actor creations.
    *
    * @param props     the Props of the child actor
    * @param testProbe the test probe used for the actor
    */
  private case class ActorCreationData(props: Props, testProbe: TestProbe)

  /**
    * Searches a collection of actor creations for the specified child actor
    * class.
    *
    * @param creations  the collection of creation data
    * @param actorClass the target actor class
    * @return the found creation data
    */
  private def findActorCreation(creations: Iterable[ActorCreationData], actorClass: Class[_]):
  ActorCreationData =
    creations.find(d => actorClass.isAssignableFrom(d.props.actorClass())).get

  /**
    * Searches a collection of actor creations for a specific actor. The data
    * for this actor is returned.
    *
    * @param creations the collection of creation data
    * @param t         the class tag for the target actor class
    * @tparam C the target actor class
    * @return the found creation data
    */
  private def findActorCreation[C](creations: Iterable[ActorCreationData])
                                  (implicit t: ClassTag[C]): ActorCreationData =
    findActorCreation(creations, t.runtimeClass)

  /**
    * Searches a collection of actor creations for a specific actor class and
    * returns the associated test probe.
    *
    * @param creations the collection of creation data
    * @param t         the class tag for the target actor class
    * @tparam C the target actor class
    * @return the test probe from the found creation data
    */
  private def findProbeFor[C](creations: Iterable[ActorCreationData])
                             (implicit t: ClassTag[C]): TestProbe =
    findActorCreation[C](creations).testProbe

  /**
    * Returns a test message to be dispatched to child actors. The message
    * should be pretty unique to test clearly whether it is dispatched to the
    * correct recipient.
    *
    * @return the test message
    */
  private def testMsg(): Any = new Object

  /**
    * Generates a propagate message to the delay actor.
    *
    * @param msg    the message to be propagated
    * @param target the target actor
    * @param delay  the delay
    * @return the ''Propagate'' message
    */
  private def delayedMsg(msg: Any, target: TestProbe, delay: FiniteDuration =
  PlayerFacadeActor.NoDelay): DelayActor.Propagate =
    DelayActor.Propagate(msg, target.ref, delay)
}

/**
  * Test class for ''PlayerFacadeActor''. Note: This class also tests the
  * ''SourceActorCreator'' function used by [[AudioPlayer]].
  */
class PlayerFacadeActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper
  with MockitoSugar {
  def this() = this(ActorSystem("PlayerFacadeActorSpec"))

  import PlayerFacadeActorSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Creates a test audio player configuration.
    *
    * @return the test configuration
    */
  private def createPlayerConfig(): PlayerConfig =
    PlayerConfig(mediaManagerActor = null, actorCreator = null,
      bufferTempPath = Some(createPathInDirectory("tempBufferDir")),
      bufferFilePrefix = BufferFilePrefix, bufferFileExtension = BufferFileExt,
      blockingDispatcherName = None)

  "A PlayerFacadeActor" should "create correct creation Props" in {
    val config = createPlayerConfig()
    val eventActor = TestProbe()
    val lineWriter = TestProbe()
    val srcCreator = AudioPlayer.AudioPlayerSourceCreator

    val props = PlayerFacadeActor(config, eventActor.ref, lineWriter.ref, srcCreator)
    classOf[PlayerFacadeActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(config, eventActor.ref, lineWriter.ref, srcCreator))
  }

  it should "create correct child actors immediately" in {
    val helper = new PlayerFacadeTestHelper

    helper.checkChildActorCreations(helper.expectActorCreations(TotalChildrenCount))
  }

  it should "create a delay actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)

    val cdDelay = findActorCreation[DelayActor](creations)
    cdDelay.props.args shouldBe 'empty
  }

  it should "dispatch a message to the download actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)
    val msg = testMsg()
    val expMsg = delayedMsg(msg, findProbeFor[SourceDownloadActor](creations))

    helper.post(PlayerFacadeActor.Dispatch(msg, PlayerFacadeActor.TargetSourceReader("AudioPlayer.DownloadActor")))
    findProbeFor[DelayActor](creations).expectMsg(expMsg)
  }

  it should "dispatch a message to the playback actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)
    val msg = testMsg()
    val expMsg = delayedMsg(msg, findProbeFor[PlaybackActor](creations))

    helper.post(PlayerFacadeActor.Dispatch(msg, PlayerFacadeActor.TargetPlaybackActor))
    findProbeFor[DelayActor](creations).expectMsg(expMsg)
  }

  it should "dispatch a delayed message to the download actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)
    val msg = testMsg()
    val delay = 10.seconds
    val dispMsg = delayedMsg(msg, findProbeFor[SourceDownloadActor](creations), delay)

    helper.post(PlayerFacadeActor.Dispatch(msg, PlayerFacadeActor.TargetSourceReader("AudioPlayer.DownloadActor"),
      delay))
    findProbeFor[DelayActor](creations).expectMsg(dispMsg)
  }

  it should "dispatch a delayed message to the playback actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)
    val msg = testMsg()
    val delay = 5.seconds
    val dispMsg = delayedMsg(msg, findProbeFor[PlaybackActor](creations), delay)

    helper.post(PlayerFacadeActor.Dispatch(msg, PlayerFacadeActor.TargetPlaybackActor, delay))
    findProbeFor[DelayActor](creations).expectMsg(dispMsg)
  }

  it should "dispatch a Propagate message to the delay actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)
    val msg = delayedMsg(testMsg(), TestProbe(), 3.minutes)

    helper post msg
    findProbeFor[DelayActor](creations).expectMsg(msg)
  }

  it should "trigger a close operation on an engine reset" in {
    val helper = new PlayerFacadeTestHelper

    helper.resetEngine()
      .awaitChildrenCloseRequest()
      .sendCloseCompleted()
      .awaitCloseComplete()
  }

  it should "stop the dynamic children when a close request completes" in {
    val helper = new PlayerFacadeTestHelper
    val probeWatcher = TestProbe()
    val creations = helper.resetEngine().expectActorCreations(TotalChildrenCount)
    helper.awaitChildrenCloseRequest(Some(creations))
      .sendCloseCompleted()

    DynamicChildrenClasses.map(c => findActorCreation(creations, c).testProbe.ref) foreach { a =>
      probeWatcher watch a
      probeWatcher.expectMsgType[Terminated]
    }
  }

  it should "re-create child actors after a reset of the engine" in {
    val helper = new PlayerFacadeTestHelper
    helper.resetEngine().awaitChildrenCloseRequest().sendCloseCompleted()

    val creations = helper.expectActorCreations(DynamicChildrenCount)
    helper.checkChildActorCreations(creations)
  }

  it should "correctly dispatch messages again after an engine reset" in {
    val helper = new PlayerFacadeTestHelper
    val allCreations = helper.expectActorCreations(TotalChildrenCount)
    val probeDelay = findProbeFor[DelayActor](allCreations)
    helper.resetEngine().awaitChildrenCloseRequest(Some(allCreations))
      .sendCloseCompleted().awaitCloseComplete()
    val creations = helper.expectActorCreations(DynamicChildrenCount)
    val msg = testMsg()
    val expMsg = delayedMsg(msg, findProbeFor[PlaybackActor](creations))

    helper.post(PlayerFacadeActor.Dispatch(msg, TargetPlaybackActor))
    probeDelay.expectMsg(expMsg)
  }

  it should "buffer incoming messages during a reset of the engine" in {
    val helper = new PlayerFacadeTestHelper
    val allCreations = helper.expectActorCreations(TotalChildrenCount)
    val probeDelay = findProbeFor[DelayActor](allCreations)
    helper.resetEngine().awaitChildrenCloseRequest(Some(allCreations))
    val msg1 = testMsg()
    val msg2 = testMsg()

    helper.post(PlayerFacadeActor.Dispatch(msg1, TargetPlaybackActor))
    helper.post(PlayerFacadeActor.Dispatch(msg2, TargetPlaybackActor))
    val creations = helper.sendCloseCompleted().expectActorCreations(DynamicChildrenCount)
    val probePlayback = findProbeFor[PlaybackActor](creations)
    probeDelay.expectMsg(delayedMsg(msg1, probePlayback))
    probeDelay.expectMsg(delayedMsg(msg2, probePlayback))
  }

  it should "handle another reset request while one is in progress" in {
    val helper = new PlayerFacadeTestHelper
    val allCreations = helper.expectActorCreations(TotalChildrenCount)
    val probeDelay = findProbeFor[DelayActor](allCreations)
    val msgDropped = testMsg()
    val msgExp = testMsg()
    helper.resetEngine().awaitChildrenCloseRequest(Some(allCreations))
      .post(PlayerFacadeActor.Dispatch(msgDropped, TargetPlaybackActor))

    helper.resetEngine()
      .post(PlayerFacadeActor.Dispatch(msgExp, TargetPlaybackActor))
      .sendCloseCompleted()
    val creations = helper.expectActorCreations(DynamicChildrenCount)
    probeDelay.expectMsg(delayedMsg(msgExp, findProbeFor[PlaybackActor](creations)))
    helper.numberOfCloseRequests should be(1)
  }

  it should "not send a CloseAck after a reset engine request" in {
    val helper = new PlayerFacadeTestHelper
    helper.resetEngine().awaitChildrenCloseRequest()
      .sendCloseCompleted().awaitCloseComplete()

    helper.closeNotifyActor should not be null
    helper.closeNotifyActor should not be testActor
  }

  it should "handle a close request" in {
    val helper = new PlayerFacadeTestHelper

    helper.post(CloseRequest)
      .awaitChildrenCloseRequest()
      .sendCloseCompleted()
      .expectNoActorCreation()
    helper.closeNotifyActor should be(testActor)
  }

  it should "forward an AddPlaybackContextFactory message to the playback actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)
    val msg = AddPlaybackContextFactory(mock[PlaybackContextFactory])

    helper.post(msg)
    findProbeFor[PlaybackActor](creations).expectMsg(msg)
  }

  it should "forward a RemovePlaybackContextFactory message to the playback actor" in {
    val helper = new PlayerFacadeTestHelper
    val creations = helper.expectActorCreations(TotalChildrenCount)
    val msg = RemovePlaybackContextFactory(mock[PlaybackContextFactory])

    helper.post(msg)
    findProbeFor[PlaybackActor](creations).expectMsg(msg)
  }

  it should "pass playback context factories to a new playback actor instance" in {
    val fact1, fact2, fact3 = mock[PlaybackContextFactory]
    val helper = new PlayerFacadeTestHelper
    helper.post(AddPlaybackContextFactory(fact1))
      .post(AddPlaybackContextFactory(fact2))
      .post(AddPlaybackContextFactory(fact3))
      .post(RemovePlaybackContextFactory(fact2))
      .resetEngine().awaitChildrenCloseRequest()
      .sendCloseCompleted().awaitCloseComplete()

    val creations = helper.expectActorCreations(DynamicChildrenCount)
    val probePlayback = findProbeFor[PlaybackActor](creations)
    val factories = Set(probePlayback.expectMsgType[AddPlaybackContextFactory].factory,
      probePlayback.expectMsgType[AddPlaybackContextFactory].factory)
    factories should contain only(fact1, fact3)
    val msg = testMsg()
    probePlayback.ref ! msg
    probePlayback.expectMsg(msg)
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class PlayerFacadeTestHelper {
    /** Test probe for the event manager actor. */
    private val eventManager = TestProbe()

    /** Test probe for the line writer actor. */
    private val lineWriter = TestProbe()

    /** The configuration for the player. */
    val config: PlayerConfig = createPlayerConfig()

    /** A queue for monitoring actor creations. */
    private val actorCreationQueue = new LinkedBlockingQueue[ActorCreationData]

    /** Stores actors passed to CloseSupport. */
    private val refClosedActors = new AtomicReference[Iterable[ActorRef]]

    /** Stores the actor to be notified about a close operation. */
    private val refCloseNotifyActor = new AtomicReference[ActorRef]

    /** A counter for close operations. */
    private val closeCount = new AtomicInteger

    /** A counter for completed close operations. */
    private val closeCompletedCount = new AtomicInteger

    /** The actor to be tested. */
    private val facadeActor = createTestActor()

    /**
      * Posts the specified message to the test actor.
      *
      * @param msg the message to be posted
      * @return this test helper
      */
    def post(msg: Any): PlayerFacadeTestHelper = {
      facadeActor ! msg
      this
    }

    /**
      * Expects the given number of actor creations and returns corresponding
      * ''ActorCreationData'' objects.
      *
      * @param count the number of expected actor creations
      * @return a collection with actor creation data
      */
    def expectActorCreations(count: Int): Iterable[ActorCreationData] = {
      (1 to count) map { _ =>
        val data = actorCreationQueue.poll(3, TimeUnit.SECONDS)
        data should not be null
        data
      }
    }

    /**
      * Checks that no child actors have been created.
      *
      * @return this test helper
      */
    def expectNoActorCreation(): PlayerFacadeTestHelper = {
      actorCreationQueue shouldBe 'empty
      this
    }

    /**
      * Checks whether all expected child actors have been created with correct
      * parameters.
      *
      * @param creations the collection of child actor creations
      * @return this test helper
      */
    def checkChildActorCreations(creations: Iterable[ActorCreationData]):
    PlayerFacadeTestHelper = {
      val cdBuffer = findActorCreation[LocalBufferActor](creations)
      val cdSrcReader = findActorCreation[SourceReaderActor](creations)
      val cdSrcDownload = findActorCreation[SourceDownloadActor](creations)
      val cdPlayback = findActorCreation[PlaybackActor](creations)

      classOf[ChildActorFactory] isAssignableFrom cdBuffer.props.actorClass() shouldBe true
      cdBuffer.props.args should have size 2
      cdBuffer.props.args.head should be(config)
      val bufMan = cdBuffer.props.args(1).asInstanceOf[BufferFileManager]
      bufMan.prefix should be(BufferFilePrefix)
      bufMan.extension should be(BufferFileExt)
      bufMan.directory should be(config.bufferTempPath.get)

      cdSrcReader.props.args should have size 1
      cdSrcReader.props.args.head should be(cdBuffer.testProbe.ref)

      classOf[SchedulerSupport] isAssignableFrom cdSrcDownload.props.actorClass() shouldBe true
      cdSrcDownload.props.args should contain theSameElementsAs List(config,
        cdBuffer.testProbe.ref, cdSrcReader.testProbe.ref)

      cdPlayback.props.args should contain theSameElementsAs List(config,
        cdSrcReader.testProbe.ref, lineWriter.ref, eventManager.ref)
      this
    }

    /**
      * Waits for a close operation of the dynamic child actors. If a
      * collection with child creations is passed in, it is used to find the
      * actor references affected. Otherwise, they are obtained via
      * ''expectActorCreations()''.
      *
      * @param optCreations optional child actor creation data
      * @return this test helper
      */
    def awaitChildrenCloseRequest(optCreations: Option[Iterable[ActorCreationData]] = None):
    PlayerFacadeTestHelper = {
      val creations = optCreations getOrElse expectActorCreations(TotalChildrenCount)
      val childActors = creations.map(_.testProbe.ref).toSet
      awaitCond(closeOperationTriggered(childActors))
      refClosedActors set null
      this
    }

    /**
      * Waits until a close operation is complete.
      *
      * @return this test helper
      */
    def awaitCloseComplete(): PlayerFacadeTestHelper = {
      awaitCond(numberOfCompletedCloseRequests == 1)
      this
    }

    /**
      * Sends a message to reset the engine to the test actor.
      *
      * @return this test helper
      */
    def resetEngine(): PlayerFacadeTestHelper = {
      post(PlayerFacadeActor.ResetEngine)
      this
    }

    /**
      * Sends a message about a completed close operation to the test actor.
      *
      * @return this test helper
      */
    def sendCloseCompleted(): PlayerFacadeTestHelper = {
      post(CloseComplete)
      this
    }

    /**
      * Returns the number of close operations triggered by the test actor.
      *
      * @return the number of close operations
      */
    def numberOfCloseRequests: Int = closeCount.get()

    /**
      * Returns the number of completed close operations performed by the
      * test actor.
      *
      * @return the number of completed close operations
      */
    def numberOfCompletedCloseRequests: Int = closeCompletedCount.get()

    /**
      * Returns the actor ref that receives an ACK about a close operation.
      *
      * @return the actor to be notified on a completed close operation
      */
    def closeNotifyActor: ActorRef = refCloseNotifyActor.get()

    /**
      * Checks whether a close operation on the specified actors has been
      * triggered.
      *
      * @param actors the expected actors
      * @return a flag whether these actors have been closed
      */
    private def closeOperationTriggered(actors: Set[ActorRef]): Boolean = {
      val closedActors = refClosedActors.get()
      closedActors != null && closedActors.toSet == actors
    }

    /**
      * Creates a test actor reference.
      *
      * @return the test actor reference
      */
    private def createTestActor(): ActorRef = {
      system.actorOf(Props(new PlayerFacadeActor(config, eventManager.ref, lineWriter.ref,
        AudioPlayer.AudioPlayerSourceCreator) with ChildActorFactory with CloseSupport {
        override def createChildActor(p: Props): ActorRef = {
          val probe = TestProbe()
          actorCreationQueue offer ActorCreationData(p, probe)
          probe.ref
        }

        /**
          * @inheritdoc Checks parameters and records the actors to be closed.
          */
        override def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef], target:
        ActorRef, factory: ChildActorFactory, conditionState: => Boolean): Boolean = {
          subject should be(facadeActor)
          factory should be(this)
          conditionState shouldBe true
          refClosedActors set deps
          refCloseNotifyActor set target
          closeCount.incrementAndGet()
          true
        }

        /**
          * @inheritdoc Records this invocation.
          */
        override def onCloseComplete(): Unit = {
          closeCompletedCount.incrementAndGet()
        }

        /**
          * @inheritdoc Adapts this method to the test logic.
          */
        override def isCloseRequestInProgress: Boolean =
          closeCount.get() > closeCompletedCount.get()
      }))
    }
  }

}
