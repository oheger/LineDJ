/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.playlist.export

import java.nio.file.{Path, Paths}

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.platform.ActorSystemTestHelper
import de.oliver_heger.linedj.io.FileReaderActor.ReadData
import de.oliver_heger.linedj.io.{ChannelHandler, CloseAck, FileReaderActor}
import de.oliver_heger.linedj.shared.archive.media.{MediumFileRequest, MediumFileResponse, MediumID}
import de.oliver_heger.linedj.{FileTestHelper, SupervisionTestActor}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

object CopyFileActorSpec {
  /** Constant for a test medium file request. */
  private val FileRequest = MediumFileRequest(MediumID("someMedium", Some("somePath")),
    "someFile", withMetaData = true)

  /** Constant for a test target file name. */
  private val TargetFile = "002 - TestSong.mp3"

  /**
   * A specialized supervisor strategy which causes the death of the affected
   * actor for all exceptions.
   */
  private val DieAlwaysStrategy = OneForOneStrategy() {
    case _ => Stop
  }

  /** A test message. */
  private val TestMessage = "PING"

  /**
   * Verifies that the specified probe did not receive an unexpected message.
   * @param probe the probe to be checked
   */
  private def expectNoMoreMessages(probe: TestProbe): Unit = {
    probe.ref ! TestMessage
    probe.expectMsg(TestMessage)
  }
}

/**
 * Test class for ''CopyFileActor''.
 */
class CopyFileActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
with FlatSpecLike with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper {

  import CopyFileActorSpec._

  def this() = this(ActorSystemTestHelper createActorSystem "CopyFileActorSpec")

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  after {
    tearDownTestFile()
  }

  /**
   * Creates a ''Props'' object for creating a test actor instance.
   * @param mediaManagerProbe an optional test probe for the media manager
   *                          actor
   * @return the properties for creating a test actor
   */
  private def actorProps(mediaManagerProbe: Option[TestProbe] = None): Props =
    CopyFileActor(parent = testActor, mediaManager = mediaManagerProbe map (_.ref) getOrElse
      TestProbe().ref)

  /**
   * Helper method for testing whether an actor was terminated.
   * @param termActor the actor in question
   */
  private def expectTermination(termActor: ActorRef): Unit = {
    val probe = TestProbe()
    probe watch termActor
    probe.expectMsgType[Terminated].actor should be(termActor)
  }

  /**
   * Prepares the test actor for a copy operation.
   * @param actor the test actor
   * @param readerActor the reader actor as source of the operation
   * @param target the target file
   * @return the test actor
   */
  private def prepareCopyOperation(actor: ActorRef, readerActor: ActorRef, target: Path):
  ActorRef = {
    actor ! CopyFileActor.CopyMediumFile(FileRequest, target)
    actor ! MediumFileResponse(FileRequest, readerActor, 100)
    actor
  }

  /**
   * Prepares a reader actor for a test source.
   * @param content the content of the test file
   * @return the initialized reader actor
   */
  private def prepareReaderForSource(content: String = FileTestHelper.TestData): ActorRef = {
    val source = createDataFile(content)
    val readerActor = system.actorOf(Props[FileReaderActor])
    readerActor ! ChannelHandler.InitFile(source)
    readerActor
  }

  "A CopyFileActor" should "pass a file request to the media manager actor" in {
    val probe = TestProbe()
    val actor = system.actorOf(actorProps(Some(probe)))

    actor ! CopyFileActor.CopyMediumFile(FileRequest, createPathInDirectory(TargetFile))
    probe.expectMsg(FileRequest)
  }

  it should "download and copy a file to the target location" in {
    val readerActor: ActorRef = prepareReaderForSource()

    val actor = system.actorOf(actorProps())
    val target = createPathInDirectory(TargetFile)

    prepareCopyOperation(actor, readerActor, target)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))
    readDataFile(target) should be(FileTestHelper.TestData)
  }

  it should "ignore copy messages if no copy operation is ongoing" in {
    val actor = TestActorRef[CopyFileActor](actorProps())

    actor receive CloseAck(null)
  }

  it should "ignore MediumFileResponse messages during processing and support multiple copy " +
    "operations" in {
    val mediaManagerProbe = TestProbe()
    val actor = system.actorOf(actorProps(Some(mediaManagerProbe)))

    // First copy request with unexpected MediumFileResponse message
    val readerProbe = TestProbe()
    val target1 = createPathInDirectory("someOutputFile.txt")
    val Data2 = "This is alternative test data"
    prepareCopyOperation(actor, readerProbe.ref, target1)
    readerProbe.expectMsgType[ReadData]
    actor ! MediumFileResponse(FileRequest.copy(uri = "toBeIgnored"), TestProbe().ref, 111)
    actor ! FileReaderActor.ReadResult(FileTestHelper.toBytes(Data2), Data2.length)
    actor ! FileReaderActor.EndOfFile(null)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target1))
    readDataFile(target1) should be(Data2)
    mediaManagerProbe.expectMsg(FileRequest)

    val readerActor: ActorRef = prepareReaderForSource()

    val target2 = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, readerActor, target2)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target2))
    readDataFile(target2) should be(FileTestHelper.TestData)
    mediaManagerProbe.expectMsg(FileRequest)
  }

  it should "ignore a MediumFileResponse if there is no current copy request" in {
    val actor = TestActorRef[CopyFileActor](actorProps())

    actor receive MediumFileResponse(FileRequest.copy(uri = "toBeIgnored"), TestProbe().ref, 111)
  }

  it should "ignore another copy request while one is processed" in {
    val mediaManagerProbe = TestProbe()
    val actor = TestActorRef[CopyFileActor](actorProps(Some(mediaManagerProbe)))
    val target = createPathInDirectory(TargetFile)
    actor receive CopyFileActor.CopyMediumFile(FileRequest, target)

    actor receive CopyFileActor.CopyMediumFile(FileRequest.copy(uri = "toBeIgnored"), target)
    mediaManagerProbe.expectMsg(FileRequest)
    expectNoMoreMessages(mediaManagerProbe)
  }

  it should "stop the reader actor when the copy operation is complete" in {
    val readerActor: ActorRef = prepareReaderForSource()
    val actor = system.actorOf(actorProps())
    val target = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, readerActor, target)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))

    expectTermination(readerActor)
  }

  it should "allow canceling a copy operation" in {
    val readerActor = TestProbe()
    val target = createPathInDirectory(TargetFile)
    val actor = system.actorOf(actorProps())
    prepareCopyOperation(actor, readerActor.ref, target)
    readerActor.expectMsgType[ReadData]

    actor ! CopyFileActor.CancelCopyOperation
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))
    expectTermination(readerActor.ref)
    system stop actor // make sure that the file is closed
    expectTermination(actor)
  }

  it should "ignore a cancel request if no copy operation is ongoing" in {
    val actor = TestActorRef[CopyFileActor](actorProps())

    actor receive CopyFileActor.CancelCopyOperation
  }

  it should "pass exceptions in child actors to its supervisor" in {
    val supervisor = SupervisionTestActor(system, DieAlwaysStrategy, actorProps())
    val invalidPath = Paths.get("non", "existing", "path")
    val actor = supervisor.underlyingActor.childActor
    actor ! CopyFileActor.CopyMediumFile(FileRequest, invalidPath)
    actor ! MediumFileResponse(FileRequest, TestProbe().ref, 120)

    expectTermination(actor)
  }

  it should "crash if the current reader actor dies" in {
    val supervisor = SupervisionTestActor(system, DieAlwaysStrategy, actorProps())
    val readerActor = system.actorOf(Props[FileReaderActor])
    val target = createPathInDirectory(TargetFile)
    val actor = prepareCopyOperation(supervisor.underlyingActor.childActor, readerActor, target)

    system stop readerActor
    expectTermination(actor)
  }

  it should "set a default progress notification size" in {
    val actor = TestActorRef[CopyFileActor](actorProps())

    actor.underlyingActor.progressNotificationSize should be(CopyFileActor
      .DefaultProgressNotificationSize)
  }

  it should "send progress notifications when copying a file" in {
    val actor = system.actorOf(Props(classOf[CopyFileActor], testActor, TestProbe().ref, 500))
    val reader = prepareReaderForSource()
    val target = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, reader, target)

    expectMsg(CopyFileActor.CopyProgress(CopyFileActor.CopyMediumFile(FileRequest, target), 500))
    expectMsgType[CopyFileActor.MediumFileCopied]
  }

  it should "reset the progress counters when starting a new copy operation" in {
    val actor = system.actorOf(Props(classOf[CopyFileActor], testActor, TestProbe().ref, 500))
    val reader = prepareReaderForSource()
    val target = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, reader, target)
    expectMsg(CopyFileActor.CopyProgress(CopyFileActor.CopyMediumFile(FileRequest, target), 500))
    expectMsgType[CopyFileActor.MediumFileCopied]

    val reader2 = prepareReaderForSource(FileTestHelper.TestData.substring(0, 200))
    prepareCopyOperation(actor, reader2, target)
    expectMsgType[CopyFileActor.MediumFileCopied]
  }
}
