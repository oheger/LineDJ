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

package de.oliver_heger.linedj.archive.metadata.persistence

import java.nio.file.{Path, Paths}
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.io.RemoveFileActor
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object MetaDataFileRemoveActorSpec {
  /** The number of test paths. */
  private val TestPathCount = 16

  /** The test checksum to path mapping. */
  private val ChecksumMapping = createChecksumMapping()

  /**
    * Creates a test path with the given index.
    *
    * @param idx the index
    * @return the test path with this index
    */
  private def createPath(idx: Int): Path = Paths.get("test", "file" + idx)

  /**
    * Creates a set of test paths in the given index range.
    *
    * @param fromIdx the from index (inclusive)
    * @param toIdx   the end index (exclusive)
    * @return the set with generated test paths
    */
  private def createPathSet(fromIdx: Int, toIdx: Int): Set[Path] =
  (fromIdx until toIdx).map(createPath).toSet

  /**
    * Creates a test checksum.
    *
    * @param idx the index
    * @return the checksum with this index
    */
  private def createChecksum(idx: Int): String = "Check" + idx

  /**
    * Creates a set of checksum values in the given index range.
    *
    * @param fromIdx the from index (inclusive)
    * @param toIdx   the end index (exclusive)
    * @return the set with generated test checksum values
    */
  private def createChecksumSet(fromIdx: Int, toIdx: Int): Set[String] =
  (fromIdx until toIdx).map(createChecksum).toSet

  /**
    * Creates a mapping with test checksum values and paths.
    *
    * @return the mapping
    */
  private def createChecksumMapping(): Map[String, Path] =
  (1 to TestPathCount).map(i => (createChecksum(i), createPath(i))).toMap

  /**
    * A simple actor implementation which simulates a remove file actor. The
    * actor reports successful removals and tracks the passed in files in a
    * queue.
    *
    * @param queue the queue for tracking removed paths
    */
  private class SimulatedRemoveFileActor(queue: BlockingQueue[Path]) extends Actor {
    override def receive: Receive = {
      case RemoveFileActor.RemoveFile(path) =>
        queue offer path
        sender() ! RemoveFileActor.FileRemoved(path)
    }
  }

}

/**
  * Test class for ''MetaDataFileRemoveActor''.
  */
class MetaDataFileRemoveActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {

  import MetaDataFileRemoveActorSpec._

  def this() = this(ActorSystem("MetaDataFileRemoveActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a simulated file remove actor and returns a reference to it and
    * the queue used to track remove operations.
    *
    * @return the actor reference and the tracking queue
    */
  private def createRemoveActor(): (ActorRef, BlockingQueue[Path]) = {
    val queue = new LinkedBlockingQueue[Path]
    val actor = system.actorOf(Props(classOf[SimulatedRemoveFileActor], queue))
    (actor, queue)
  }

  /**
    * Creates a test actor instance with a child actor factory returning the
    * specified child actors.
    *
    * @return the test actor
    */
  private def createTestActor(childActors: List[ActorRef]):
  TestActorRef[MetaDataFileRemoveActor] = {
    val props = Props(new MetaDataFileRemoveActor with ChildActorFactory {
      private var childActorList = childActors

      override def createChildActor(p: Props): ActorRef = {
        classOf[RemoveFileActor] isAssignableFrom p.actorClass() shouldBe true
        p.args shouldBe empty
        val child = childActorList.head
        childActorList = childActorList.tail
        child
      }
    })
    TestActorRef[MetaDataFileRemoveActor](props)
  }

  /**
    * Reads an element from the given queue with a timeout. Fails if no
    * element is found.
    *
    * @param queue the queue
    * @return the element that has been read
    */
  private def readQueue(queue: BlockingQueue[Path]): Path = {
    awaitCond(!queue.isEmpty)
    val result = queue.poll(1, TimeUnit.SECONDS)
    result should not be null
    result
  }

  /**
    * Expects that the given number of remove operations have been tracked by
    * the specified queue.
    *
    * @param queue the queue
    * @param count the number of remove operations
    * @return a set with the paths of the tracked remove operations
    */
  private def expectRemovedFiles(queue: BlockingQueue[Path], count: Int): Set[Path] =
  (1 to count).map(_ => readQueue(queue)).toSet

  "A MetaDataFileRemoveActor" should "create correct properties" in {
    val props = MetaDataFileRemoveActor()

    classOf[MetaDataFileRemoveActor] isAssignableFrom props.actorClass() shouldBe true
    classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
  }

  it should "process a single remove request" in {
    val removeFiles = createChecksumSet(1, TestPathCount)
    val request = MetaDataFileRemoveActor.RemoveMetaDataFiles(removeFiles,
      ChecksumMapping, testActor)
    val (childActor, queue) = createRemoveActor()
    val removeActor = createTestActor(List(childActor))

    removeActor ! request
    expectMsg(MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request, removeFiles))
    expectRemovedFiles(queue, removeFiles.size) should be(createPathSet(1, TestPathCount))
  }

  it should "handle a request with an empty file list" in {
    val request = MetaDataFileRemoveActor.RemoveMetaDataFiles(Set.empty, ChecksumMapping,
      testActor)
    val (childActor, _) = createRemoveActor()
    val removeActor = createTestActor(List(childActor))

    removeActor ! request
    expectMsg(MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request, Set.empty))
  }

  it should "process multiple remove requests" in {
    val removeFiles1 = createChecksumSet(1, TestPathCount / 2)
    val removeFiles2 = createChecksumSet(TestPathCount / 2, TestPathCount + 1)
    val request1 = MetaDataFileRemoveActor.RemoveMetaDataFiles(removeFiles1,
      ChecksumMapping, testActor)
    val request2 = MetaDataFileRemoveActor.RemoveMetaDataFiles(removeFiles2,
      ChecksumMapping, testActor)
    val (childActor, queue) = createRemoveActor()
    val removeActor = createTestActor(List(childActor))

    removeActor ! request1
    removeActor ! request2
    expectMsg(MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request1, removeFiles1))
    expectMsg(MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request2, removeFiles2))
    expectRemovedFiles(queue, TestPathCount) should be(createPathSet(1, TestPathCount + 1))
  }

  it should "handle failed delete operations" in {
    val removeFiles = createChecksumSet(1, TestPathCount)
    val request = MetaDataFileRemoveActor.RemoveMetaDataFiles(removeFiles,
      ChecksumMapping, testActor)
    val removeActor = system.actorOf(MetaDataFileRemoveActor())

    removeActor ! request
    expectMsg(MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request, Set.empty))
  }

  it should "ignore unexpected FileRemoved messages" in {
    val removeFiles = createChecksumSet(2, 4)
    val request = MetaDataFileRemoveActor.RemoveMetaDataFiles(removeFiles,
      ChecksumMapping, testActor)
    val probeChild = TestProbe()
    val removeActor1 = createTestActor(List(probeChild.ref))
    removeActor1 ! MetaDataFileRemoveActor.RemoveMetaDataFiles(createChecksumSet(1, 2),
      ChecksumMapping, testActor)
    probeChild.expectMsgType[RemoveFileActor.RemoveFile]
    val (childActor, _) = createRemoveActor()
    val removeActor2 = createTestActor(List(childActor))

    removeActor1 ! RemoveFileActor.FileRemoved(createPath(28))
    removeActor2 ! request
    expectMsg(MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request, removeFiles))
  }

  it should "reset the path in progress" in {
    val (childActor, _) = createRemoveActor()
    val removeActor = createTestActor(List(childActor))
    removeActor ! MetaDataFileRemoveActor.RemoveMetaDataFiles(Set(createChecksum(1)),
      ChecksumMapping, testActor)
    expectMsgType[MetaDataFileRemoveActor.RemoveMetaDataFilesResult]

    removeActor receive RemoveFileActor.FileRemoved(createPath(1))
    val request = MetaDataFileRemoveActor.RemoveMetaDataFiles(createChecksumSet(2, 5),
      ChecksumMapping, testActor)
    removeActor ! request
    expectMsgType[MetaDataFileRemoveActor.RemoveMetaDataFilesResult].request should be(request)
  }

  it should "ignore files that are not contained in the path mapping" in {
    val removeFiles = createChecksumSet(1, 5)
    val request = MetaDataFileRemoveActor.RemoveMetaDataFiles(removeFiles + createChecksum(111),
      ChecksumMapping, testActor)
    val (child, queue) = createRemoveActor()
    val removeActor = createTestActor(List(child))

    removeActor ! request
    expectMsg(MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request, removeFiles))
    expectRemovedFiles(queue, removeFiles.size) should be(createPathSet(1, 5))
  }
}
