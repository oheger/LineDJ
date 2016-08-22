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

package de.oliver_heger.linedj.pleditor.ui.playlist.export

import java.nio.file.{Path, Paths}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.client.model.SongData
import de.oliver_heger.linedj.pleditor.ui.playlist.export.CopyFileActor.CopyProgress
import de.oliver_heger.linedj.pleditor.ui.playlist.export.ExportActor.ExportResult
import de.oliver_heger.linedj.client.ActorSystemTestHelper
import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.io.{FileData, ScanResult}
import de.oliver_heger.linedj.media.{MediumFileRequest, MediumID}
import de.oliver_heger.linedj.metadata.MediaMetaData
import de.oliver_heger.linedj.client.mediaifc.{RemoteActors, RemoteMessageBus, RemoteRelayActor}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.reflect.ClassTag

object ExportActorSpec {
  /** Constant for the remove file actor class. */
  private val ClassRemoveFileActor = classOf[RemoveFileActor]

  /** Constant for the copy file actor class. */
  private val ClassCopyFileActor = classOf[CopyFileActor]

  /** Constant for the export path. */
  private val ExportPath = Paths get "export"

  /** A default file size for generated files. */
  private val DefaultFileSize = 100

  /** A test scan result object. */
  private val TestScanResult = createScanResult()

  /**
   * Another test scan result with one file to be deleted. This can be used in
   * tests that need a setup with both remove and copy operations.
   */
  private val TestScanResultWithSingleRemoveFile = createScanResultWithSingleFile()

  /** A test message. */
  private val PingMsg = "PING"

  /** A strategy for stopping failed actors. */
  private val StopStrategy = OneForOneStrategy() {
    case _: Exception => Stop
  }

  /**
   * Generates a path on the target export medium with the given name.
   * @param name the name of the path
   * @return the resulting path
   */
  private def targetPath(name: String): Path = ExportPath resolve name

  /**
   * Generates the path for a test file with the specified index.
   * @param index the index of the test file
   * @return the corresponding path
   */
  private def targetPath(index: Int): Path =
    targetPath(f"$index%03d - ${songTitle(index)}.mp3")

  /**
   * Generates the ID of a test medium.
   * @param index the index
   * @return the test medium ID for this index
   */
  private def medium(index: Int): MediumID =
    MediumID("medium" + index, None)

  /**
   * Generates the URI of a test song.
   * @param index the index
   * @return the URI of this test song
   */
  private def songUri(index: Int): String =
    "song://Test" + index + ".mp3"

  /**
   * Generates the title of a test song.
   * @param index the index
   * @return the title for the test song with this index
   */
  private def songTitle(index: Int): String = "Mambo No " + index

  /**
   * Generates the size of a test song (in a rather simple fashion).
   * @param index the index
   * @return the size of the test song with this index
   */
  private def songSize(index: Int): Int = index * 10

  /**
   * Creates a test ''SongData'' object with unique properties based on the
   * given index.
   * @param index the index for generating unique properties
   * @param size an optional size for the test song
   * @return the test ''SongData'' object
   */
  private def createSongData(index: Int, size: Option[Long] = None): SongData =
    SongData(medium(index), songUri(index), MediaMetaData(title = Some(songTitle(index)), size =
      size getOrElse songSize(index)), null)

  /**
   * Generates a list of test files.
   * @param names the file names
   * @return the sequence of files
   */
  private def generateFiles(names: String*): Seq[FileData] =
    names map (n => FileData(targetPath(n), DefaultFileSize))

  /**
   * Generates a number of test songs in a specified range.
   * @param count the number of songs to be generated
   * @param from the optional start index
   * @return the sequence of test songs
   */
  private def songs(count: Int, from: Int = 1): Seq[SongData] = from until (from + count) map
    (createSongData(_))

  /**
   * Extracts the paths from the specified operations.
   * @param ops the operations
   * @return the paths affected by these operations
   */
  private def extractPaths(ops: Seq[ExportOperation]): Seq[Path] =
    ops map (_.affectedPath)

  /**
   * Generates a test scan result object.
   * @return the test scan result
   */
  private def createScanResult(): ScanResult = {
    val files = generateFiles("file1", "file2", "003 - " + songTitle(3) + ".mp3", "anotherFile")
    val dirs = List(targetPath("root"), targetPath("dir1"), targetPath("dir2"))
    ScanResult(directories = dirs, files = files)
  }

  /**
   * Generates a test scan result object that contains a single file which has
   * to be deleted on the target directory.
   * @return the test scan result
   */
  private def createScanResultWithSingleFile(): ScanResult = {
    val files = generateFiles("003 - " + songTitle(3) + ".mp3")
    val dirs = List(targetPath("root"))
    ScanResult(directories = dirs, files = files)
  }

  /**
   * Checks that the specified probe has not received a message.
   * @param probe the probe
   * @return the same probe
   */
  private def ensureNoMessage(probe: TestProbe): TestProbe = {
    probe.ref ! PingMsg
    probe.expectMsg(PingMsg)
    probe
  }
}

/**
 * Test class for ''ExportActor''.
 */
class ExportActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ExportActorSpec._

  def this() = this(ActorSystemTestHelper createActorSystem "ExportActorSpec")

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An ExportActor" should "generate operations for copying files" in {
    val Count = 10
    val data = ExportActor.ExportData(songs(Count), TestScanResult, ExportPath, clearTarget =
      false, overrideFiles = false)

    val (ops, size) = ExportActor.initializeExportData(data)
    val expPaths = 1 to Count map targetPath
    extractPaths(ops) should be(expPaths)
    ops forall (_.operationType == ExportActor.OperationType.Copy) shouldBe true
    size should be(550)
  }

  it should "use the correct file extension when generating operations" in {
    val songList = List(SongData(medium(1), "song://Test1.mp2", MediaMetaData(title = Some
    (songTitle(1))), null),
      SongData(medium(2), "song://Test2", MediaMetaData(title = Some(songTitle(2))), null))
    val data = ExportActor.ExportData(songList, TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)

    val (ops, _) = ExportActor.initializeExportData(data)
    val expPaths = List(targetPath("001 - " + songTitle(1) + ".mp2"), targetPath("002 - " +
      songTitle(2)))
    extractPaths(ops) should be(expPaths)
  }

  it should "use the necessary number of digits in the song index" in {
    val data = ExportActor.ExportData(songs(1111), TestScanResult, ExportPath, clearTarget =
      false, overrideFiles = false)

    val (ops, _) = ExportActor.initializeExportData(data)
    val p = ops.head.affectedPath.getFileName.toString
    p should startWith("0001 -")
  }

  it should "replace invalid characters in file names" in {
    val title = "Song:\t My *, \"<Love>|Pet/Heart Come\\ - yes?"
    val replacedTitle = "Song__ My _, __Love__Pet_Heart Come_ - yes_"
    val songList = List(SongData(medium(1), songUri(1), MediaMetaData(title = Some(title)), null))
    val data = ExportActor.ExportData(songList, TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)

    val (ops, _) = ExportActor.initializeExportData(data)
    val expPaths = List(targetPath("001 - " + replacedTitle + ".mp3"))
    extractPaths(ops) should be(expPaths)
  }

  it should "generate operations for cleaning existing files" in {
    val data = ExportActor.ExportData(songs(1), TestScanResult, ExportPath, clearTarget = true,
      overrideFiles = false)

    val (ops, _) = ExportActor.initializeExportData(data)
    val removeOps = ops takeWhile (_.operationType == ExportActor.OperationType.Remove)
    removeOps should have size (ops.size - 1)
    val expPaths = TestScanResult.files.map(_.path) ++ TestScanResult.directories.tail.reverse
    extractPaths(removeOps) should be(expPaths)
    val copyOp = ops.last
    copyOp.operationType should be(ExportActor.OperationType.Copy)
    copyOp.affectedPath should be(targetPath(1))
  }

  it should "not copy already existing files on the target medium if overriding is disabled" in {
    val songList = List(createSongData(1), createSongData(2), createSongData(3, Some
    (DefaultFileSize)), createSongData(4))
    val data = ExportActor.ExportData(songList, TestScanResult, ExportPath, clearTarget =
      false, overrideFiles = false)

    val (ops, size) = ExportActor.initializeExportData(data)
    val expPaths = List(targetPath(1), targetPath(2), targetPath(4))
    extractPaths(ops) should be(expPaths)
    size should be(70)
  }

  it should "process a list of operations during an export" in {
    val Count = 5
    val data = ExportActor.ExportData(songs(Count), TestScanResultWithSingleRemoveFile,
      ExportPath, clearTarget = true, overrideFiles = false)
    val helper = new ExportActorTestHelper

    helper prepareActor data
    helper expectAndAnswerRemoveOperation targetPath(3)
    helper expectCopyOperations Count
  }

  it should "send feedback messages during an export" in {
    val Count = 6
    val data = ExportActor.ExportData(songs(Count), TestScanResultWithSingleRemoveFile,
      ExportPath, clearTarget = true, overrideFiles = true)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    helper expectAndAnswerRemoveOperation targetPath(3)
    helper expectCopyOperations Count

    @tailrec def fileSizes(index: Int, currentSize: Long, currentList: List[Long]): List[Long] = {
      if (index > Count) currentList
      else {
        val elem = currentSize + songSize(index)
        fileSizes(index + 1, elem, elem :: currentList)
      }
    }
    val sizes = fileSizes(1, 0, Nil).reverse
    val totalSize = sizes.last

    def progressMsg(index: Int, currentSize: Long, currentOp: Int, currentType: ExportActor
    .OperationType.Value): ExportActor.ExportProgress = {
      ExportActor.ExportProgress(totalOperations = Count + 1, totalSize = totalSize, currentPath
        = targetPath(index),
        currentOperation = currentOp, operationType = currentType, currentSize = currentSize)
    }

    helper.expectMessageOnBus[ExportActor.ExportProgress] should be(progressMsg(3, 0, 1,
      ExportActor.OperationType.Remove))
    for (i <- sizes.zipWithIndex) {
      val msg = helper.expectMessageOnBus[ExportActor.ExportProgress]
      msg should be(progressMsg(i._2 + 1, i._1, i._2 + 2, ExportActor.OperationType.Copy))
    }
  }

  it should "send feedback messages during a copy operation" in {
    def createProgressMessage(index: Int, expSize: Long): ExportActor.ExportProgress =
      ExportActor.ExportProgress(totalOperations = 2, totalSize = 30, currentSize = expSize,
        currentOperation = index, currentPath = targetPath(index), operationType = ExportActor
          .OperationType.Copy)

    val data = ExportActor.ExportData(songs(2), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = true)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    val copyRequest = helper expectCopyOperation 1

    helper send CopyFileActor.CopyProgress(copyRequest, 4)
    helper.expectMessageOnBus[ExportActor.ExportProgress] should be(createProgressMessage(1, 4))
    helper send CopyFileActor.CopyProgress(copyRequest, 8)
    helper.expectMessageOnBus[ExportActor.ExportProgress] should be(createProgressMessage(1, 8))
    helper.answerCopyOperation(copyRequest)
    helper.expectMessageOnBus[ExportActor.ExportProgress] should be(createProgressMessage(1,
      songSize(1)))

    val copyRequest2 = helper expectCopyOperation 2
    helper send CopyFileActor.CopyProgress(copyRequest2, 10)
    helper.expectMessageOnBus[ExportActor.ExportProgress] should be(createProgressMessage(2,
      songSize(1) + 10))
  }

  it should "ignore unexpected copy progress messages" in {
    def createCopyProgress(index: Int): CopyProgress = {
      CopyFileActor.CopyProgress(CopyFileActor.CopyMediumFile(MediumFileRequest(medium(index),
        songUri(index), withMetaData = true), targetPath(index)), 111)
    }

    val data = ExportActor.ExportData(songs(1), TestScanResultWithSingleRemoveFile, ExportPath,
      clearTarget = true, overrideFiles = false)
    val helper = new ExportActorTestHelper
    helper prepareActor data

    val removePath = TestScanResultWithSingleRemoveFile.files.head.path
    helper.removeFileActor.expectMsg(RemoveFileActor.RemoveFile(removePath))
    helper send createCopyProgress(3)
    helper send RemoveFileActor.FileRemoved(removePath)
    helper.expectMessageOnBus[ExportActor.ExportProgress].operationType should be(ExportActor
      .OperationType.Remove)

    val copyRequest = helper.expectCopyOperation(1)
    helper send createCopyProgress(2)
    helper answerCopyOperation copyRequest
    helper.expectMessageOnBus[ExportActor.ExportProgress].currentSize should be(songSize(1))
  }

  it should "send a result message when the export is over" in {
    val data = ExportActor.ExportData(songs(1), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    helper.expectAndAnswerCopyOperation(1)
    helper.expectMessageOnBus[ExportActor.ExportProgress]

    helper.expectMessageOnBus[ExportActor.ExportResult] should be(ExportActor.ExportResult(None))
  }

  it should "allow canceling an export" in {
    val data = ExportActor.ExportData(songs(1), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    val request = helper.expectCopyOperation(1)

    helper send ExportActor.CancelExport
    helper.copyFileActor.expectMsg(CopyFileActor.CancelCopyOperation)
    helper.answerCopyOperation(request)
    helper.expectMessageOnBus[ExportActor.ExportProgress]
    helper.expectMessageOnBus[ExportActor.ExportResult] should be(ExportActor.ExportResult(None))
  }

  it should "allow canceling an export before it is actually started" in {
    val helper = new ExportActorTestHelper

    helper sendDirect ExportActor.CancelExport
    helper.expectMessageOnBus[ExportActor.ExportResult] should be(ExportActor.ExportResult(None))
  }

  it should "only start an export if all required data is available" in {
    val helper = new ExportActorTestHelper
    val data = ExportActor.ExportData(songs(1), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)

    helper send data
    helper.initializeMediaManager()
    helper.expectCopyOperation(1)
  }

  it should "ignore an ExportData message after the export was canceled" in {
    val helper = new ExportActorTestHelper
    helper.initializeMediaManager()
    helper send ExportActor.CancelExport
    helper.expectMessageOnBus[ExportActor.ExportResult]

    helper sendDirect ExportActor.ExportData(songs(1), TestScanResult, ExportPath, clearTarget =
      false, overrideFiles = false)
    ensureNoMessage(helper.copyFileActor)
  }

  it should "ignore an unexpected file removed message" in {
    val data = ExportActor.ExportData(songs(2), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    helper.expectCopyOperation(1)

    helper sendDirect RemoveFileActor.FileRemoved(targetPath(1))
    ensureNoMessage(helper.copyFileActor)
  }

  it should "ignore an incorrect file removed message" in {
    val data = ExportActor.ExportData(songs(4), TestScanResultWithSingleRemoveFile, ExportPath,
      clearTarget = true, overrideFiles = true)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    helper.removeFileActor.expectMsgType[RemoveFileActor.RemoveFile]

    helper sendDirect RemoveFileActor.FileRemoved(targetPath(1))
    ensureNoMessage(helper.copyFileActor)
  }

  it should "ignore an incorrect file copied message" in {
    val data = ExportActor.ExportData(songs(2), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    val request = helper.expectCopyOperation(1)

    helper answerCopyOperation request.copy(target = targetPath(2))
    ensureNoMessage(helper.copyFileActor)
  }

  it should "ignore further messages after the export is completed" in {
    val data = ExportActor.ExportData(songs(1), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    helper.expectAndAnswerCopyOperation(1)
    helper.expectMessageOnBus[ExportActor.ExportProgress]
    helper.expectMessageOnBus[ExportActor.ExportResult] should be(ExportActor.ExportResult(None))

    helper sendDirect ExportActor.CancelExport
    ensureNoMessage(helper.copyFileActor)
  }

  it should "handle a failed remove operation" in {
    val helper = new ExportActorTestHelper
    val data = ExportActor.ExportData(songs(4), TestScanResultWithSingleRemoveFile, ExportPath,
      clearTarget = true, overrideFiles = true)
    val exportActor = system.actorOf(ExportActor(helper.remoteBus))
    exportActor ! RemoteRelayActor.RemoteActorResponse(RemoteActors.MediaManager,
      Some(TestProbe().ref))

    exportActor ! data // the first remove operation will fail
    val msg = helper.expectMessageOnBus[ExportActor.ExportResult]
    val error = msg.error.get
    error.errorPath should be(targetPath(3))
    error.errorType should be(ExportActor.OperationType.Remove)
  }

  /**
   * Executes an export which fails because of a copy operation.
   * @return the test helper
   */
  private def executeFailedExport(): ExportActorTestHelper = {
    val data = ExportActor.ExportData(songs(1), TestScanResult, ExportPath, clearTarget = false,
      overrideFiles = false)
    val helper = new ExportActorTestHelper
    helper prepareActor data
    helper.expectCopyOperation(1)

    system stop helper.copyFileActor.ref
    helper
  }

  it should "handle a failed copy operation" in {
    val helper = executeFailedExport()
    val msg = helper.expectMessageOnBus[ExportResult]
    val error = msg.error.get
    error.errorPath should be(targetPath(1))
    error.errorType should be(ExportActor.OperationType.Copy)
  }

  /**
   * A test helper class managing some dependencies of the actor under test.
   */
  private class ExportActorTestHelper {
    /** Test probe for the remove file actor. */
    val removeFileActor = TestProbe()

    /** Test probe for the copy file actor. */
    val copyFileActor = TestProbe()

    /** Test probe for the remote relay actor. */
    val relayActor = TestProbe()

    /** A queue for receiving messages produced by the test actor. */
    val messageQueue = new LinkedBlockingQueue[Any]

    /** A mock for the remote message bus. */
    val remoteBus = createRemoteMessageBus(messageQueue, relayActor.ref)

    /** The test actor. */
    val exportActor = TestActorRef[ExportActor](actorProps())

    /** Test probe for the media manager actor. */
    private val mediaManagerActor = TestProbe()

    /**
     * Sends messages to the test actor for initializing an export.
     * @param data the export data
     * @return a reference to the test actor
     */
    def prepareActor(data: ExportActor.ExportData): ActorRef = {
      relayActor.expectMsg(RemoteRelayActor.RemoteActorRequest(RemoteActors.MediaManager))
      initializeMediaManager()
      exportActor ! data
      exportActor
    }

    /**
     * Initializes the dependency to the media manager actor.
     */
    def initializeMediaManager(): Unit = {
      exportActor ! RemoteRelayActor.RemoteActorResponse(RemoteActors.MediaManager, Some
      (mediaManagerActor.ref))
    }

    /**
     * Expects an interaction with the remove actor for the specified path.
     * @param path the path to be removed
     */
    def expectAndAnswerRemoveOperation(path: Path): Unit = {
      ensureNoMessage(copyFileActor)
      removeFileActor.expectMsg(RemoveFileActor.RemoveFile(path))
      exportActor ! RemoveFileActor.FileRemoved(path)
    }

    /**
     * Expects an interaction with the copy actor for the specified test file.
     * @param index the index of the test file
     * @return the message expected from the copy actor
     */
    def expectCopyOperation(index: Int): CopyFileActor.CopyMediumFile = {
      ensureNoMessage(removeFileActor)
      val path = targetPath(index)
      copyFileActor.expectMsg(CopyFileActor.CopyMediumFile(MediumFileRequest(medium(index),
        songUri(index), withMetaData = true), path))
    }

    /**
     * Sends an answer to the test actor simulating a finished copy operation.
     * @param copyRequest the copy request to be answered
     */
    def answerCopyOperation(copyRequest: CopyFileActor.CopyMediumFile): Unit = {
      exportActor ! CopyFileActor.MediumFileCopied(copyRequest.request, copyRequest.target)
    }

    /**
     * Expects an interaction with the copy actor for the specified test file
     * and sends a corresponding answer.
     * @param index the index of the test file
     */
    def expectAndAnswerCopyOperation(index: Int): Unit = {
      answerCopyOperation(expectCopyOperation(index))
    }

    /**
     * Expects the copy phase of an export. For each file to be exported a
     * corresponding copy message is expected and answered.
     * @param count the number of files to be copied
     */
    def expectCopyOperations(count: Int): Unit = {
      for (i <- 1 to count) {
        expectAndAnswerCopyOperation(i)
      }
    }

    /**
     * Checks that a message of the specified type has been published on the
     * message bus.
     * @param t the class tag
     * @tparam T the type of the expected message
     * @return the received message
     */
    def expectMessageOnBus[T](implicit t: ClassTag[T]): T = {
      val msg = messageQueue.poll(3, TimeUnit.SECONDS)
      val cls = t.runtimeClass
      cls isInstance msg shouldBe true
      msg.asInstanceOf[T]
    }

    /**
     * Sends the specified message to the test actor via the normal tell
     * operator.
     * @param msg the message to be sent
     */
    def send(msg: Any): Unit = {
      exportActor ! msg
    }

    /**
     * Sends the specified message to the test actor via the receive method of
     * the ''TestActorRef''.
     * @param msg the message to be sent
     */
    def sendDirect(msg: Any): Unit = {
      exportActor receive msg
    }

    /**
     * Generates properties for creating a test actor instance.
     * @return the properties
     */
    private def actorProps(): Props = {
      Props(new ExportActor(remoteBus) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() match {
            case ClassRemoveFileActor =>
              p.args shouldBe 'empty
              removeFileActor.ref

            case ClassCopyFileActor =>
              p.args should have size 2
              p.args should contain inOrderOnly(exportActor, mediaManagerActor.ref)
              copyFileActor.ref
          }
        }
      })
    }

    /**
     * Creates a mock for the remote message bus. All messages published on
     * this bus are recorded in a queue.
     * @param queue the queue for receiving messages
     * @param actor the remote relay actor
     * @return the mock message bus
     */
    private def createRemoteMessageBus(queue: LinkedBlockingQueue[Any], actor: ActorRef):
    RemoteMessageBus = {
      val bus = mock[RemoteMessageBus]
      val msgBus = mock[MessageBus]
      when(bus.bus).thenReturn(msgBus)
      when(bus.relayActor).thenReturn(actor)
      doAnswer(new Answer[Object] {
        override def answer(invocationOnMock: InvocationOnMock): Object = {
          queue offer invocationOnMock.getArguments.head
          null
        }
      }).when(msgBus).publish(org.mockito.Matchers.any())
      bus
    }
  }

}
