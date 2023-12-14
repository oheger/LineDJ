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

package de.oliver_heger.linedj.pleditor.ui.playlist.plexport

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.stream.CancelableStreamSupport
import de.oliver_heger.linedj.shared.archive.media.*
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.*
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.nio.file.{Path, Paths}

object CopyFileActorSpec:
  /** A list with chunks returned by the test download actor. */
  val DownloadChunks: List[ByteString] = List(ByteString(FileTestHelper.TestData),
    ByteString("another chunk of data"), ByteString("last chunk"))

  /** A test chunk size. */
  val ChunkSize = 4444

  /** Constant for a test medium file request. */
  private val FileRequest = MediumFileRequest(MediaFileID(MediumID("someMedium", Some("somePath")),
    "someFile"), withMetaData = true)

  /** Constant for a test target file name. */
  private val TargetFile = "002 - TestSong.mp3"

  /** A test message. */
  private val TestMessage = "PING"

  /** A test progress size. */
  private val ProgressSize = 1024 * 1024

  /**
    * Verifies that the specified probe did not receive an unexpected message.
    *
    * @param probe the probe to be checked
    */
  private def expectNoMoreMessages(probe: TestProbe): Unit =
    probe.ref ! TestMessage
    probe.expectMsg(TestMessage)

  /**
    * Returns the concatenated content of the chunks returned by the test
    * download actor. The resulting file should have this content.
    *
    * @return the content of the test file from the simulated download actor
    */
  private def downloadContent(): ByteString =
    DownloadChunks.foldLeft(ByteString.empty)(_ ++ _)

/**
  * Test class for ''CopyFileActor''.
  */
class CopyFileActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper:

  import CopyFileActorSpec._

  def this() = this(ActorSystem("CopyFileActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  after:
    tearDownTestFile()

  /**
    * Creates a ''Props'' object for creating a test actor instance.
    *
    * @param mediaManagerProbe an optional test probe for the media manager
    *                          actor
    * @param progressSize      the progress size to be used
    * @return the properties for creating a test actor
    */
  private def actorProps(mediaManagerProbe: Option[TestProbe] = None,
                         progressSize: Int = ProgressSize): Props =
    CopyFileActor(testActor, mediaManagerProbe map (_.ref) getOrElse TestProbe().ref,
      ChunkSize, progressSize)

  /**
    * Helper method for testing whether an actor was terminated.
    *
    * @param termActor the actor in question
    */
  private def expectTermination(termActor: ActorRef): Unit =
    val probe = TestProbe()
    probe watch termActor
    probe.expectMsgType[Terminated].actor should be(termActor)

  /**
    * Prepares the test actor for a copy operation.
    *
    * @param actor       the test actor
    * @param readerActor the reader actor as source of the operation
    * @param target      the target file
    * @return the test actor
    */
  private def prepareCopyOperation(actor: ActorRef, readerActor: ActorRef, target: Path):
  ActorRef =
    actor ! CopyFileActor.CopyMediumFile(FileRequest, target)
    actor ! MediumFileResponse(FileRequest, Some(readerActor), 100)
    actor

  "A CopyFileActor" should "create correct properties" in:
    val probeManager = TestProbe()
    val props = CopyFileActor(testActor, probeManager.ref, ChunkSize, ProgressSize)

    classOf[CopyFileActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CancelableStreamSupport].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(testActor, probeManager.ref, ChunkSize, ProgressSize))

  it should "pass a file request to the media manager actor" in:
    val probe = TestProbe()
    val actor = system.actorOf(actorProps(Some(probe)))

    actor ! CopyFileActor.CopyMediumFile(FileRequest, createPathInDirectory(TargetFile))
    probe.expectMsg(FileRequest)

  it should "download and copy a file to the target location" in:
    val readerActor = system.actorOf(Props[DownloadActorTestImpl]())
    val actor = system.actorOf(actorProps())
    val target = createPathInDirectory(TargetFile)

    prepareCopyOperation(actor, readerActor, target)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))
    readDataFile(target) should be(downloadContent().utf8String)

  it should "send the confirmation message to the correct client actor" in:
    val readerActor = system.actorOf(Props[DownloadActorTestImpl]())
    val probeManager = TestProbe()
    val actor = system.actorOf(actorProps(mediaManagerProbe = Some(probeManager)))
    val target = createPathInDirectory(TargetFile)

    actor ! CopyFileActor.CopyMediumFile(FileRequest, target)
    actor.tell(MediumFileResponse(FileRequest, Some(readerActor), 100), probeManager.ref)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))

  it should "ignore MediumFileResponse messages during processing and support multiple copy " +
    "operations" in:
    val mediaManagerProbe = TestProbe()
    val actor = system.actorOf(actorProps(Some(mediaManagerProbe)))

    // First copy request with unexpected MediumFileResponse message
    val downloadActor = system.actorOf(Props(classOf[SingleStepDownloadActor], testActor))
    val target1 = createPathInDirectory("someOutputFile.txt")
    val Data2 = "This is alternative test data"
    prepareCopyOperation(actor, downloadActor, target1)
    expectMsg(DownloadData(ChunkSize))
    actor ! MediumFileResponse(FileRequest.copy(fileID = FileRequest.fileID.copy(
      uri = "toBeIgnored")), Some(TestProbe().ref), 111)
    downloadActor ! DownloadDataResult(ByteString(Data2))
    expectMsg(DownloadData(ChunkSize))
    downloadActor ! DownloadComplete
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target1))
    readDataFile(target1) should be(Data2)
    mediaManagerProbe.expectMsg(FileRequest)

    val readerActor = system.actorOf(Props[DownloadActorTestImpl]())
    val target2 = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, readerActor, target2)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target2))
    readDataFile(target2) should be(downloadContent().utf8String)
    mediaManagerProbe.expectMsg(FileRequest)

  it should "ignore a MediumFileResponse if there is no current copy request" in:
    val actor = TestActorRef[CopyFileActor](actorProps())

    actor receive MediumFileResponse(FileRequest.copy(fileID = FileRequest.fileID.copy(
      uri = "toBeIgnored")), Some(TestProbe().ref), 111)

  it should "ignore another copy request while one is processed" in:
    val mediaManagerProbe = TestProbe()
    val actor = TestActorRef[CopyFileActor](actorProps(Some(mediaManagerProbe)))
    val target = createPathInDirectory(TargetFile)
    actor receive CopyFileActor.CopyMediumFile(FileRequest, target)

    actor receive CopyFileActor.CopyMediumFile(FileRequest.copy(fileID = FileRequest.fileID.copy(
      uri = "toBeIgnored")), target)
    mediaManagerProbe.expectMsg(FileRequest)
    expectNoMoreMessages(mediaManagerProbe)

  it should "stop the reader actor when the copy operation is complete" in:
    val readerActor = system.actorOf(Props[DownloadActorTestImpl]())
    val actor = system.actorOf(actorProps())
    val target = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, readerActor, target)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))

    expectTermination(readerActor)

  it should "allow canceling a copy operation" in:
    val downloadActor = system.actorOf(Props(classOf[SingleStepDownloadActor], testActor))
    val target = createPathInDirectory(TargetFile)
    val actor = system.actorOf(actorProps())
    prepareCopyOperation(actor, downloadActor, target)
    expectMsgType[DownloadData]

    actor ! CopyFileActor.CancelCopyOperation
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))
    expectTermination(downloadActor)

  it should "ignore a cancel request if no copy operation is ongoing" in:
    val actor = TestActorRef[CopyFileActor](actorProps())

    actor receive CopyFileActor.CancelCopyOperation

  it should "crash if the target file could not be written" in:
    val downloadActor = system.actorOf(Props[DownloadActorTestImpl]())
    val actor = system.actorOf(actorProps())
    val invalidPath = Paths.get("")
    prepareCopyOperation(actor, downloadActor, invalidPath)

    expectTermination(actor)

  it should "crash if the current reader actor dies" in:
    val downloadActor = system.actorOf(Props(classOf[SingleStepDownloadActor], testActor))
    val target = createPathInDirectory(TargetFile)
    val actor = system.actorOf(actorProps())
    prepareCopyOperation(actor, downloadActor, target)
    expectMsg(DownloadData(ChunkSize))

    system stop downloadActor
    expectTermination(actor)

  it should "crash if there is a stream processing error" in:
    val target = createPathInDirectory(TargetFile)
    val actor = system.actorOf(Props(new CopyFileActor(testActor, TestProbe().ref,
      ChunkSize, ProgressSize) with ChildActorFactory with CancelableStreamSupport {
      override private[plexport] def createSource(): Source[ByteString, NotUsed] =
        Source.failed(new Exception("BOOM!"))
    }))
    prepareCopyOperation(actor, TestProbe().ref, target)

    expectTermination(actor)

  it should "send progress notifications when copying a file" in:
    val actor = system.actorOf(actorProps(progressSize = 500))
    val reader = system.actorOf(Props[DownloadActorTestImpl]())
    val target = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, reader, target)

    expectMsg(CopyFileActor.CopyProgress(CopyFileActor.CopyMediumFile(FileRequest, target), 500))
    expectMsgType[CopyFileActor.MediumFileCopied]

  it should "reset the progress counters when starting a new copy operation" in:
    val actor = system.actorOf(actorProps(progressSize = 500))
    val reader = system.actorOf(Props[DownloadActorTestImpl]())
    val target = createPathInDirectory(TargetFile)
    prepareCopyOperation(actor, reader, target)
    expectMsg(CopyFileActor.CopyProgress(CopyFileActor.CopyMediumFile(FileRequest, target), 500))
    expectMsgType[CopyFileActor.MediumFileCopied]

    val reader2 = system.actorOf(Props(classOf[SingleStepDownloadActor], testActor))
    prepareCopyOperation(actor, reader2, target)
    expectMsgType[DownloadData]
    reader2 ! DownloadDataResult(ByteString(FileTestHelper.TestData.substring(0, 200)))
    expectMsgType[DownloadData]
    reader2 ! DownloadComplete
    expectMsgType[CopyFileActor.MediumFileCopied]

  it should "handle a failed MediumFileResponse" in:
    val probeManager = TestProbe()
    val target = createPathInDirectory(TargetFile)
    val actor = system.actorOf(actorProps(mediaManagerProbe = Some(probeManager)))
    actor ! CopyFileActor.CopyMediumFile(FileRequest, target)
    probeManager.expectMsg(FileRequest)

    actor ! MediumFileResponse(FileRequest, None, 0)
    expectMsg(CopyFileActor.MediumFileCopied(FileRequest, target))

/**
  * A test download actor which always sends a sequence of chunks.
  */
class DownloadActorTestImpl extends Actor:
  var chunks: List[ByteString] = CopyFileActorSpec.DownloadChunks

  override def receive: Receive =
    case DownloadData(size) if size == CopyFileActorSpec.ChunkSize =>
      val response = chunks match
        case h :: t =>
          chunks = t
          DownloadDataResult(h)
        case _ =>
          DownloadComplete
      sender() ! response

/**
  * A test download actor which passes download requests to a trigger actor
  * and sends responses received from this actor. This is used when more
  * control over the flow of messages is needed; e.g. when messages need to be
  * sent to the test actor while a stream is in progress.
  *
  * @param trigger the trigger actor
  */
class SingleStepDownloadActor(trigger: ActorRef) extends Actor:
  private var receiver: ActorRef = _

  override def receive: Receive =
    case d: DownloadData =>
      trigger ! d
      receiver = sender()

    case m if sender() == trigger =>
      receiver ! m
