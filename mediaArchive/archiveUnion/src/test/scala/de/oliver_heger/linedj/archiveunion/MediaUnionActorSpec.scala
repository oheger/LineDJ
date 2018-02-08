/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archiveunion

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.TestActor.KeepRunning
import akka.testkit.{ImplicitSender, TestActor, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.ForwardTestActor
import de.oliver_heger.linedj.io.{CloseHandlerActor, CloseRequest, CloseSupport}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object MediaUnionActorSpec {
  /** Prefix string for archive component IDs. */
  private val ComponentPrefix = "ArchiveComponent_"

  /** Prefix string for the URI of a medium. */
  private val MediumPrefix = "medium://TestMedium_"

  /**
    * Generates a test archive component ID.
    *
    * @param idx the index
    * @return the test archive component ID with this index
    */
  private def componentID(idx: Int): String = ComponentPrefix + idx

  /**
    * Creates a test medium ID from the specified parameters.
    *
    * @param idx          an index to generate a unique medium URI
    * @param componentIdx the index of the archive component
    * @return the medium ID
    */
  private def mediumID(idx: Int, componentIdx: Int): MediumID =
    MediumID(MediumPrefix + idx, None, componentID(componentIdx))

  /**
    * Generates a test medium info object.
    *
    * @param mid the associated medium ID
    * @param idx the index
    * @return the test medium info
    */
  private def mediumInfo(mid: MediumID, idx: Int): MediumInfo =
    MediumInfo(name = "MediumName" + idx, description = "desc" + idx, orderMode = "",
      orderParams = "", checksum = idx.toString, mediumID = mid)

  /**
    * Creates a mapping for a test medium.
    *
    * @param idx          the test index
    * @param componentIdx the index of the archive component
    * @return the mapping
    */
  private def mediaMapping(idx: Int, componentIdx: Int): (MediumID, MediumInfo) = {
    val mid = mediumID(idx, componentIdx)
    mid -> mediumInfo(mid, idx)
  }
}

/**
  * Test class for ''MediaUnionActor''.
  */
class MediaUnionActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers {

  import MediaUnionActorSpec._

  def this() = this(ActorSystem("MediaUnionActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Stops the specified actor and waits until the death watch notification
    * arrives.
    *
    * @param actor the actor to be stopped
    * @return the received ''Terminated'' message
    */
  private def stopActor(actor: ActorRef): Terminated = {
    system stop actor
    val watcher = TestProbe()
    watcher watch actor
    watcher.expectMsgType[Terminated]
  }

  "A MediaUnionActor" should "create correct properties" in {
    val metaDataActor = TestProbe().ref
    val props = MediaUnionActor(metaDataActor)

    props.args should contain only metaDataActor
    classOf[MediaUnionActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true
  }

  it should "have an empty initial map of media" in {
    val helper = new MediaUnionActorTestHelper

    val availableMedia = helper.queryMedia()
    availableMedia.media should have size 0
  }

  it should "allow adding media information" in {
    val mediaMap = Map(mediaMapping(1, 1), mediaMapping(2, 1), mediaMapping(3, 1))
    val helper = new MediaUnionActorTestHelper

    helper.addMedia(mediaMap, 1)
    helper.queryMedia().media should be(mediaMap)
  }

  it should "construct a union of available media" in {
    val mediaMap1 = Map(mediaMapping(1, 1), mediaMapping(2, 1), mediaMapping(3, 1))
    val mediaMap2 = Map(mediaMapping(1, 2), mediaMapping(2, 2))
    val helper = new MediaUnionActorTestHelper

    helper.addMedia(mediaMap1, 1)
    helper.addMedia(mediaMap2, 2)
    val mediaMap = helper.queryMedia().media
    mediaMap should be(mediaMap1 ++ mediaMap2)
  }

  it should "handle a request for a medium file" in {
    val mediaMap = Map(mediaMapping(1, 1))
    val mid = mediumID(1, 1)
    val request = MediumFileRequest(MediaFileID(mid, "someFile"), withMetaData = false)
    val controller = ForwardTestActor()
    val helper = new MediaUnionActorTestHelper
    helper.addMedia(mediaMap, 1, controller)

    helper.manager ! request
    expectMsg(ForwardTestActor.ForwardedMessage(request))
  }

  it should "handle a medium file request for a non-existing controller actor" in {
    val request = MediumFileRequest(MediaFileID(mediumID(1, 1), "someFile"), withMetaData = true)
    val helper = new MediaUnionActorTestHelper

    helper.manager ! request
    val response = expectMsgType[MediumFileResponse]
    response.request should be(request)
    response.length should be(-1)
  }

  it should "return an undefined reader actor for an invalid file request" in {
    val request = MediumFileRequest(MediaFileID(mediumID(1, 1), "someFile"), withMetaData = true)
    val helper = new MediaUnionActorTestHelper

    helper.manager ! request
    val response = expectMsgType[MediumFileResponse]
    response.contentReader shouldBe 'empty
  }

  it should "handle a ReaderActorAlive message" in {
    val mediaMap = Map(mediaMapping(1, 1))
    val mid = mediumID(1, 1)
    val request = DownloadActorAlive(null, mid)
    val controller = ForwardTestActor()
    val helper = new MediaUnionActorTestHelper
    helper.addMedia(mediaMap, 1, controller)

    helper.manager ! request
    expectMsg(ForwardTestActor.ForwardedMessage(request))
  }

  it should "ignore a ReaderActorAlive message for an unknown component" in {
    val helper = new MediaUnionActorTestHelper
    val request = DownloadActorAlive(null, mediumID(42, 28))

    helper.manager receive request
  }

  it should "forward a scan request to all controller actors" in {
    val helper = new MediaUnionActorTestHelper
    val ctrl1 = helper.addMedia(Map(mediaMapping(1, 1)), 1)
    val ctrl2 = helper.addMedia(Map(mediaMapping(1, 2)), 2)

    helper.manager ! ScanAllMedia
    List(ctrl1, ctrl2).foreach(_.expectMsg(ScanAllMedia))
  }

  it should "remove the media of a controller when it terminates" in {
    val mediaMap1 = Map(mediaMapping(1, 1), mediaMapping(2, 1), mediaMapping(3, 1))
    val mediaMap2 = Map(mediaMapping(1, 2), mediaMapping(2, 2))
    val helper = new MediaUnionActorTestHelper
    val ctrl = helper.addMedia(mediaMap1, 1)
    helper.addMedia(mediaMap2, 2)

    stopActor(ctrl.ref)
    awaitCond(helper.queryMedia().media == mediaMap2)
  }

  it should "remove the media of a controller on request" in {
    val mediaMap1 = Map(mediaMapping(1, 1), mediaMapping(2, 1), mediaMapping(3, 1))
    val mediaMap2 = Map(mediaMapping(1, 2), mediaMapping(2, 2))
    val helper = new MediaUnionActorTestHelper
    helper.addMedia(mediaMap1, 1)
    helper.addMedia(mediaMap2, 2)

    helper.manager ! ArchiveComponentRemoved(componentID(1))
    helper.queryMedia().media should be(mediaMap2)
  }

  it should "remove a terminated controller actor from the mapping" in {
    val mediaMap = Map(mediaMapping(1, 1))
    val helper = new MediaUnionActorTestHelper
    val ctrl = helper.addMedia(mediaMap, 1)

    stopActor(ctrl.ref)
    helper.manager ! MediumFileRequest(MediaFileID(mediumID(1, 1), "someUri"), withMetaData = true)
    expectMsgType[MediumFileResponse].contentReader shouldBe 'empty
  }

  it should "use the sender as controller actor if not specified explicitly" in {
    val helper = new MediaUnionActorTestHelper

    helper.manager ! AddMedia(Map(mediaMapping(1, 1)), componentID(1), None)
    helper.manager ! ScanAllMedia
    expectMsg(ScanAllMedia)
  }

  it should "process a CloseRequest message" in {
    val helper = new MediaUnionActorTestHelper
    val ctrl1 = helper.addMedia(Map(mediaMapping(1, 1)), 1)
    val ctrl2 = helper.addMedia(Map(mediaMapping(1, 2)), 2)

    helper.initCloseActors(ctrl1.ref, ctrl2.ref).triggerAndExpectCloseHandling()
  }

  it should "process a CloseComplete message" in {
    val helper = new MediaUnionActorTestHelper

    helper.triggerAndExpectCompletedClose()
  }

  it should "notify the meta data actor about a scan request" in {
    val helper = new MediaUnionActorTestHelper
    helper.manager ! ScanAllMedia

    helper.metaDataActor.expectMsg(ScanAllMedia)
  }

  it should "notify the meta data actor about a removed archive component" in {
    val helper = new MediaUnionActorTestHelper
    helper.addMedia(Map(mediaMapping(1, 1)), 1)
    val ctrl2 = helper.addMedia(Map(mediaMapping(1, 2)), 2)

    system stop ctrl2.ref
    helper.metaDataActor.expectMsg(ArchiveComponentRemoved(componentID(2)))
  }

  it should "notify the meta data actor about a request to remove a component" in {
    val helper = new MediaUnionActorTestHelper
    helper.metaDataActor.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): KeepRunning.type = {
        sender ! ForwardTestActor.ForwardedMessage(msg)
        TestActor.KeepRunning
      }
    })
    val msg = ArchiveComponentRemoved(componentID(1))

    helper.manager ! msg
    expectMsg(ForwardTestActor.ForwardedMessage(msg))
  }

  /**
    * A test helper class managing all dependencies of the test actor.
    */
  private class MediaUnionActorTestHelper {
    /** Test probe for the meta data manager actor. */
    val metaDataActor = TestProbe()

    /** The actor to be tested. */
    val manager: TestActorRef[MediaUnionActor] = createTestActor()

    /** Counter for close requests handled by the test actor. */
    private val closeRequestCount = new AtomicInteger

    /** Counter for completed close operations. */
    private val closeCompleteCount = new AtomicInteger

    /** Stores actors to be handled by a close request. */
    private var closeActors: Iterable[ActorRef] = Iterable.empty[ActorRef]

    /**
      * Sends a query for available media to the test actor and returns the
      * result.
      *
      * @return the currently available media
      */
    def queryMedia(): AvailableMedia = {
      manager ! GetAvailableMedia
      expectMsgType[AvailableMedia]
    }

    /**
      * Sends a message to the test actor which adds the specified media
      * information. A test probe is created for the controlling actor.
      *
      * @param data         the media data to be added
      * @param componentIdx the index of the archive component
      * @return the test probe for the controlling actor
      */
    def addMedia(data: Map[MediumID, MediumInfo], componentIdx: Int): TestProbe = {
      val probe = TestProbe()
      addMedia(data, componentIdx, probe.ref)
      probe
    }

    /**
      * Sends a message to the test actor which adds the specified media
      * information on behalf of the given controller actor.
      *
      * @param data         the media data to be added
      * @param componentIdx the index of the archive component
      * @param actor        the controller actor
      * @return this test helper
      */
    def addMedia(data: Map[MediumID, MediumInfo], componentIdx: Int, actor: ActorRef):
    MediaUnionActorTestHelper = {
      manager ! AddMedia(data, componentID(componentIdx), Some(actor))
      this
    }

    /**
      * Initializes the actors to be taken into account when processing a close
      * request.
      *
      * @param actors the actors to be closed
      * @return this test helper
      */
    def initCloseActors(actors: ActorRef*): MediaUnionActorTestHelper = {
      closeActors = metaDataActor.ref :: actors.toList
      this
    }

    /**
      * Sends a close request to the test actor and checks that close handling
      * has been triggered correctly.
      *
      * @return this test helper
      */
    def triggerAndExpectCloseHandling(): MediaUnionActorTestHelper = {
      manager ! CloseRequest
      awaitCond(closeRequestCount.get() == 1)
      this
    }

    /**
      * Returns the number of completed close requests.
      *
      * @return the number of completed close requests
      */
    def numberOfCloseCompleted(): Int = closeCompleteCount.get()

    /**
      * Sends a completed close message to the test actor and checks whether it
      * is processed correctly.
      *
      * @return this test helper
      */
    def triggerAndExpectCompletedClose(): MediaUnionActorTestHelper = {
      manager ! CloseHandlerActor.CloseComplete
      awaitCond(numberOfCloseCompleted() == 1)
      this
    }

    /**
      * Creates the test actor reference.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[MediaUnionActor] =
      TestActorRef[MediaUnionActor](Props(new MediaUnionActor(metaDataActor.ref)
        with ChildActorFactory with CloseSupport {
        /**
          * Checks parameters and records this invocation.
          */
        override def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef], target:
        ActorRef, factory: ChildActorFactory, conditionState: => Boolean): Boolean = {
          subject should be(manager)
          factory should be(this)
          target should be(testActor)
          conditionState shouldBe true
          deps should contain theSameElementsAs closeActors
          closeRequestCount.incrementAndGet() < 2
        }

        /**
          * Records this invocation.
          */
        override def onCloseComplete(): Unit = closeCompleteCount.incrementAndGet()
      }))
  }

}
