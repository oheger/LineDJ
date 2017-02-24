/*
 * Copyright 2015-2017 The Developers Team.
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
package de.oliver_heger.linedj.archive.metadata

import java.nio.file.{Path, Paths}
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.ForwardTestActor
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media._
import de.oliver_heger.linedj.io._
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.shared.archive.union.{MediaContribution, MetaDataProcessingResult}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec

object MetaDataManagerActorSpec {
  /** The maximum message size. */
  private val MaxMessageSize = 24

  /** ID of a test medium. */
  private val TestMediumID = mediumID("medium1")

  /** A list of medium IDs used by the tests. */
  private val MediaIDs = List(TestMediumID, mediumID("otherMedium"),
    mediumID("coolMusic"))

  /** A test scan result object. */
  private val ScanResult = createScanResult()

  /** A test enhanced scan result object. */
  private val EnhancedScanResult = createEnhancedScanResult(ScanResult)

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /**
   * Helper method for generating a path.
    *
    * @param s the name of this path
   * @return the path
   */
  private def path(s: String): Path = Paths get s

  /**
    * Generates the URI for a path. This is used to construct a URI mapping.
    *
    * @param path the path
    * @return the URI for this path
    */
  private def uriFor(path: Path): String = "song://" + path.toString

  /**
   * Generates a medium ID.
    *
    * @param name a unique name for the ID
   * @return the medium ID
   */
  private def mediumID(name: String): MediumID = {
    val settingsPath = Paths.get(name, "playlist.settings")
    MediumID fromDescriptionPath settingsPath
  }

  /**
   * Creates a test meta data object for the specified path.
    *
    * @param path the path
   * @return the meta data for this path (following conventions)
   */
  private def metaDataFor(path: Path): MediaMetaData = {
    val index = extractPathIndex(path)
    MediaMetaData(title = Some(path.getFileName.toString),
      duration = Some(index * 10),
      size = index * 100)
  }

  /**
    * Generates a ''MetaDataProcessingResult'' for the specified parameters.
    *
    * @param mediumID the medium ID
    * @param file     the object with file data
    * @return the processing result
    */
  private def processingResultFor(mediumID: MediumID, file: FileData): MetaDataProcessingResult =
    MetaDataProcessingResult(file.path, mediumID, uriFor(file.path),
      metaDataFor(file.path))

  /**
   * Generates a number of media files that belong to the specified test
   * medium.
    *
    * @param mediumPath the path of the medium
   * @param count the number of files to generate
   * @return the resulting list
   */
  private def generateMediaFiles(mediumPath: Path, count: Int): List[FileData] = {
    val basePath = Option(mediumPath.getParent) getOrElse mediumPath
    @tailrec
    def loop(current: List[FileData], index: Int): List[FileData] = {
      if (index == 0) current
      else loop(FileData(basePath.resolve(s"TestFile_$index.mp3"), 20) :: current, index - 1)
    }

    loop(Nil, count)
  }

  /**
    * Extracts an index from the given path. All test paths produced by
    * ''generateMediaFiles()'' end on a numeric index (before the file
    * extension). This function extracts this index. (Note: it is not very
    * robust for other paths.)
    *
    * @param path the path
    * @return the index of this path
    */
  private def extractPathIndex(path: Path): Int = {
    val pathStr = path.toString takeWhile(_ != '.')
    @tailrec def loop(index: Int): Int =
      if (Character isDigit pathStr(index)) loop(index - 1)
      else pathStr.substring(index + 1).toInt

    loop(pathStr.length - 1)
  }

  /**
   * Creates a test scan result object.
   */
  private def createScanResult(): MediaScanResult = {
    val numbersOfSongs = List(3, 8, 4)
    val rootPath = path("Root")
    val fileData = MediaIDs zip numbersOfSongs map { e =>
      (e._1, generateMediaFiles(path(e._1.mediumDescriptionPath.get), e._2))
    }
    val fileMap = Map(fileData: _*) + (MediumID(rootPath.toString, None) -> generateMediaFiles
      (path("noMedium"), 11))
    MediaScanResult(rootPath, fileMap)
  }

  /**
   * Creates an enhanced scan result. This method adds checksum information.
    *
    * @param result the plain result
   * @return the enhanced result
   */
  private def createEnhancedScanResult(result: MediaScanResult): EnhancedMediaScanResult = {
    EnhancedMediaScanResult(result, result.mediaFiles map (e => (e._1, "checksum_" + e._1
      .mediumURI)), createFileUriMapping(result))
  }

  /**
    * Generates an ''AvailableMedia'' message for the specified scan result.
    *
    * @param result the scan result
    * @return the corresponding available message
    */
  private def createAvailableMedia(result: MediaScanResult): AvailableMedia = {
    def createMediumInfo(mid: MediumID): MediumInfo =
      MediumInfo(mediumID = mid, name = "Medium " + mid.mediumURI,
        description = "", orderMode = "", orderParams = "", checksum = "c" + mid.mediumURI)

    val mediaInfo = result.mediaFiles.keys.map { mid =>
      (mid, createMediumInfo(mid))
    }.toMap + (MediumID.UndefinedMediumID -> createMediumInfo(MediumID.UndefinedMediumID))
    AvailableMedia(mediaInfo)
  }

  /**
    * Generates a global URI to file mapping for the given result object.
    *
    * @param result the ''MediaScanResult''
    * @return the URI to file mapping for this result
    */
  private def createFileUriMapping(result: MediaScanResult): Map[String, FileData] =
    result.mediaFiles.values.flatten.map(f => (uriFor(f.path), f)).toMap

  /**
    * Helper method to ensure that no more messages are sent to a test probe.
    * This message sends a special message to the probe and checks whether it is
    * immediately received.
    *
    * @param probe the probe to be checked
    */
  private def expectNoMoreMessage(probe: TestProbe): Unit = {
    probe.ref ! TestMessage
    probe.expectMsg(TestMessage)
  }
}

/**
 * Test class for ''MetaDataManagerActor''.
 */
class MetaDataManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import MetaDataManagerActorSpec._

  def this() = this(ActorSystem("MetaDataManagerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MetaDataManagerActor" should "create correct properties" in {
    val config = mock[MediaArchiveConfig]
    val persistenceMan, unionActor = TestProbe()

    val props = MetaDataManagerActor(config, persistenceMan.ref, unionActor.ref)
    props.args should contain inOrderOnly(config, persistenceMan.ref, unionActor.ref)
    classOf[MetaDataManagerActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true
  }

  it should "pass a scan result to the meta data persistence manager actor" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.startProcessing()
    helper.persistenceManager.expectMsg(EnhancedScanResult)
  }

  it should "pass a contribution to the union actor for a scan result" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.startProcessing()
    helper.expectMediaContribution()
  }

  it should "ignore a scan result before the scan is started" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.actor receive EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
    expectNoMoreMessage(helper.metaDataUnionActor)
  }

  /**
    * Generates an alternative scan result and tells the test actor to process
    * it.
    * @param helper the test helper
    * @param files the files to be found in this scan result
    * @return the alternative scan result
    */
  private def processAnotherScanResult(helper: MetaDataManagerActorTestHelper, files: List[FileData]): EnhancedMediaScanResult = {
    val root = path("anotherRootDirectory")
    val medID = MediumID(root.toString, Some("someDescFile.txt"))
    val scanResult2 = MediaScanResult(root, Map(medID -> files))
    val esr = EnhancedMediaScanResult(scanResult2, Map(medID -> "testCheckSum"),
      createFileUriMapping(scanResult2))
    helper.actor ! esr
    helper.sendProcessingResults(medID, files)
    esr
  }

  it should "extract meta data from files that could not be resolved" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    val unresolved1 = UnresolvedMetaDataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head) drop 1, EnhancedScanResult)
    val unresolved2 = UnresolvedMetaDataFiles(MediaIDs(1), ScanResult.mediaFiles(MediaIDs(1)),
      EnhancedScanResult)
    helper.actor ! unresolved1
    val processor = helper.nextChild()
    processor.expectMsg(ProcessMediaFiles(unresolved1.mediumID, unresolved1.files))
    helper.actor ! unresolved2
    processor.expectMsg(ProcessMediaFiles(unresolved2.mediumID, unresolved2.files))
    helper.numberOfChildActors should be(1)
  }

  it should "create different processor actors for different media roots" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val files = generateMediaFiles(path("otherPath"), 2)
    val otherResult = processAnotherScanResult(helper, files)
    val unresolved1 = UnresolvedMetaDataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val unresolved2 = UnresolvedMetaDataFiles(otherResult.scanResult.mediaFiles.keys.head, files, otherResult)
    helper.actor ! unresolved1
    helper.nextChild().expectMsgType[ProcessMediaFiles]

    helper.actor ! unresolved2
    helper.nextChild().expectMsg(ProcessMediaFiles(unresolved2.mediumID, unresolved2.files))
    helper.numberOfChildActors should be(2)
  }

  it should "reset the scanInProgress flag if all data is available" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendAllProcessingResults(ScanResult)
    helper.sendAvailableMedia()

    helper.actor ! EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
  }

  it should "not reset the scanInProgress flag before all processing results arrived" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendAvailableMedia().
      sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.actor ! EnhancedScanResult
    helper.persistenceManager.expectMsg(EnhancedScanResult)
  }

  it should "propagate processing results to the meta data union actor" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.expectMediaContribution().sendAvailableMedia().
      sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.expectPropagatedProcessingResults(TestMediumID,
      ScanResult.mediaFiles(TestMediumID))
  }

  it should "handle a Cancel request in the middle of processing" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    val files = generateMediaFiles(path("otherPath"), 2)
    val otherResult = processAnotherScanResult(helper, files)
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.actor ! UnresolvedMetaDataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val processor1 = helper.nextChild()
    processor1.expectMsgType[ProcessMediaFiles]
    helper.actor ! UnresolvedMetaDataFiles(otherResult.scanResult.mediaFiles.keys.head, files,
      otherResult)
    val processor2 = helper.nextChild()
    processor2.expectMsgType[ProcessMediaFiles]
    helper.sendAvailableMedia()
      .prepareCloseRequest(closing = true)(processor1.ref, processor2.ref)

    helper.actor receive CloseRequest
    helper.numberOfCloseRequests should be(1)

    helper.actor receive EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
  }

  it should "not complete a Cancel request before all media have been received" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.prepareCloseRequest(avMediaPresent = false)()
    helper.actor receive CloseRequest

    helper.numberOfCloseRequests should be(1)
  }

  it should "notify the condition as satisfied when available media are received" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.prepareCloseRequest(avMediaPresent = false)()

    helper.actor ! CloseRequest
    helper.sendAvailableMedia()
    awaitCond(helper.numberOfSatisfiedConditions == 1)
  }

  it should "reset the cancel request after the scan has been aborted" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.actor receive CloseHandlerActor.CloseComplete
    helper.numberOfCloseCompleted should be(1)
  }

  it should "ignore processing results while a Cancel request is pending" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    val results = ScanResult.mediaFiles(TestMediumID) take 2
    helper.expectMediaContribution().sendProcessingResults(TestMediumID, results)
      .expectPropagatedProcessingResults(TestMediumID, results)
      .prepareCloseRequest(closing = true)()

    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID) drop 2)
    expectNoMoreMessage(helper.metaDataUnionActor)
  }

  it should "handle a Cancel request if no scan is in progress" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))
  }

  it should "ignore a scan start message if a scan is in progress" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.actor receive MediaScanStarts
    expectNoMoreMessage(helper.persistenceManager)
  }

  it should "ignore a processing result for an unknown medium" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    val mid = MediumID("unknown medium", Some("unknown path"))

    helper.expectMediaContribution()
      .sendProcessingResults(mid, ScanResult.mediaFiles(TestMediumID))
    expectNoMoreMessage(helper.metaDataUnionActor)
  }

  it should "ignore a processing result for an unknown URI" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    helper.expectMediaContribution()
      .sendProcessingResults(TestMediumID, List(FileData(path("unknownPath_42"), 42)))
    expectNoMoreMessage(helper.metaDataUnionActor)
  }

  it should "forward a GetMetaDataFileInfo message to the persistence manager" in {
    val helper = new MetaDataManagerActorTestHelper(
      optPersistenceManager = Some(ForwardTestActor()))

    helper.actor ! GetMetaDataFileInfo
    expectMsg(ForwardTestActor.ForwardedMessage(GetMetaDataFileInfo))
  }

  it should "forward a RemovePersistentMetaData message to the persistence manager" in {
    val helper = new MetaDataManagerActorTestHelper(
      optPersistenceManager = Some(ForwardTestActor()))
    val removeMsg = RemovePersistentMetaData(Set("someChecksum"))

    helper.actor ! removeMsg
    expectMsg(ForwardTestActor.ForwardedMessage(removeMsg))
  }

  it should "reject a RemovePersistenceMetaData message while a scan is in progress" in {
    val helper = new MetaDataManagerActorTestHelper
    val removeMsg = RemovePersistentMetaData(Set("ignored"))

    helper.startProcessing() ! removeMsg
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    expectMsg(RemovePersistentMetaDataResult(removeMsg, Set.empty))
    expectNoMoreMessage(helper.persistenceManager)
  }

  /**
    * A test helper class that manages a couple of helper objects needed for
    * more complex tests of a meta data manager actor.
    *
    * @param checkChildActorProps  flag whether the properties passed to child
    *                              actors should be checked
    * @param optPersistenceManager an option for a special persistence manager
    *                              actor; this overrides the test probe passed
    *                              per default
    */
  private class MetaDataManagerActorTestHelper(checkChildActorProps: Boolean = true,
                                               optPersistenceManager: Option[ActorRef] = None) {
    /**
      * A test probe that simulates the persistence manager actor.
      */
    val persistenceManager = TestProbe()

    /** Test probe for the meta data union actor. */
    val metaDataUnionActor = TestProbe()

    /** The configuration. */
    val config: MediaArchiveConfig = createConfig()

    /** The test actor reference. */
    val actor: TestActorRef[MetaDataManagerActor] = createTestActor()

    /** A counter for the number of child actors created by the test actor. */
    private val childActorCounter = new AtomicInteger

    /** A counter for close requests that have been triggered. */
    private val closeRequestCounter = new AtomicInteger

    /** A counter for completed close requests. */
    private val closeCompleteCounter = new AtomicInteger

    /** A counter for satisfied condition notifications. */
    private val conditionSatisfiedCounter = new AtomicInteger

    /**
      * A queue that tracks the child actors created by the test actor. It can
      * be used to access the corresponding test probes and check whether the
      * expected messages have been sent to child actors.
      */
    private val childActorQueue = new LinkedBlockingQueue[TestProbe]

    /**
      * Stores the currently active processor actors. This is used to determine
      * actors to be closed by the CloseSupport implementation.
      */
    private var activeProcessors = Iterable.empty[ActorRef]

    /**
      * Flag whether available media are present. This is needed to check the
      * condition flag passed to the CloseSupport implementation.
      */
    private var availableMediaArrived = true

    /**
      * Flag whether currently a close operation is in progress. This flag is
      * used by the CloseSupport implementation.
      */
    private var closeInProgress = false

    /**
      * Convenience function for sending a message to the test actor that starts
      * processing.
      *
      * @param esr                 the enhanced scan result to be sent
      * @param checkPersistenceMan flag whether the persistence manager actor
      *                            is to be checked
      * @return the test actor
      */
    def startProcessing(esr: EnhancedMediaScanResult = EnhancedScanResult,
                        checkPersistenceMan: Boolean = true): ActorRef = {
      actor ! MediaScanStarts
      if (checkPersistenceMan) {
        persistenceManager.expectMsg(ScanForMetaDataFiles)
      }
      actor ! esr
      actor
    }

    /**
      * Sends processing result objects for the given files to the test actor.
      *
      * @param mediumID the medium ID
      * @param files    the list of files
      * @return this test helper
      */
    def sendProcessingResults(mediumID: MediumID, files: List[FileData]):
    MetaDataManagerActorTestHelper = {
      files foreach { m =>
        actor receive processingResultFor(mediumID, m)
      }
      this
    }

    /**
      * Sends complete processing results for all files of the provided scan
      * result.
      *
      * @param result the scan result
      * @return this test helper
      */
    def sendAllProcessingResults(result: MediaScanResult): MetaDataManagerActorTestHelper = {
      result.mediaFiles foreach { e => sendProcessingResults(e._1, e._2) }
      this
    }

    /**
      * Expects that a contribution message was sent to the meta data union
      * actor.
      *
      * @param esr the result the contribution is based on
      * @return this test helper
      */
    def expectMediaContribution(esr: EnhancedMediaScanResult = EnhancedScanResult):
    MetaDataManagerActorTestHelper = {
      metaDataUnionActor.expectMsg(MediaContribution(esr.scanResult.mediaFiles))
      this
    }

    /**
      * Expects that meta data processing results for the specified files have
      * been propagated to the meta data union actor.
      *
      * @param mediumID the medium ID
      * @param files    the list of files
      * @return this test helper
      */
    def expectPropagatedProcessingResults(mediumID: MediumID, files: List[FileData]):
    MetaDataManagerActorTestHelper = {
      files foreach { f =>
        metaDataUnionActor.expectMsg(processingResultFor(mediumID, f))
      }
      this
    }

    /**
      * Returns the number of child actors created by the test actor.
      *
      * @return the number of child actors
      */
    def numberOfChildActors: Int = childActorCounter.get()

    /**
      * Returns the number of close requests that have been handled.
      *
      * @return the number of close requests
      */
    def numberOfCloseRequests: Int = closeRequestCounter.get()

    /**
      * Returns the number of close requests that have been completed.
      *
      * @return the number of complete close requests
      */
    def numberOfCloseCompleted: Int = closeCompleteCounter.get()

    /**
      * Returns the number of invocations of satisfied conditions.
      *
      * @return the satisfied conditions count
      */
    def numberOfSatisfiedConditions: Int = conditionSatisfiedCounter.get()

    /**
      * Returns the next child actor that has been created or fails if no
      * child creation took place.
      *
      * @return the probe for the next child actor
      */
    def nextChild(): TestProbe = {
      awaitCond(!childActorQueue.isEmpty)
      childActorQueue.poll()
    }

    /**
      * Sends an ''AvailableMedia'' message for the given scan result to the
      * test actor.
      *
      * @param sr the scan result
      * @return this test helper
      */
    def sendAvailableMedia(sr: MediaScanResult = ScanResult): MetaDataManagerActorTestHelper = {
      actor ! createAvailableMedia(sr)
      this
    }

    /**
      * Returns a reference to the persistence manager actor used by the test
      * actor instance. This is either the actor reference passed to the
      * constructor or a reference from a test probe.
      *
      * @return the persistence manager actor reference
      */
    def persistenceManagerActorRef: ActorRef =
    optPersistenceManager getOrElse persistenceManager.ref

    /**
      * Initializes flags required by tests of the close/cancel handling.
      *
      * @param avMediaPresent flag whether available media are set
      * @param closing        flag whether a closing operation is in progress
      * @param processors     the currently active processor actors
      * @return this test helper
      */
    def prepareCloseRequest(avMediaPresent: Boolean = true, closing: Boolean = false)
                           (processors: ActorRef*): MetaDataManagerActorTestHelper = {
      availableMediaArrived = avMediaPresent
      closeInProgress = closing
      activeProcessors = processors
      this
    }

    /**
     * Creates the standard test actor.
      *
      * @return the test actor
     */
    private def createTestActor(): TestActorRef[MetaDataManagerActor] =
    TestActorRef(creationProps())

    private def creationProps(): Props =
      Props(new MetaDataManagerActor(config, persistenceManagerActorRef, metaDataUnionActor.ref)
        with ChildActorFactory with CloseSupport {
        override def createChildActor(p: Props): ActorRef = {
          childActorCounter.incrementAndGet()
          if (checkChildActorProps) {
            val sampleProps = MediumProcessorActor(EnhancedScanResult, config)
            p.actorClass() should be(sampleProps.actorClass())
            p.args should be(sampleProps.args)
          }
          val probe = TestProbe()
          childActorQueue offer probe
          probe.ref
        }

        /**
          * @inheritdoc Returns the value of the flag set for the test class.
          */
        override def isCloseRequestInProgress: Boolean = closeInProgress

        /**
          * Checks parameters and records this invocation.
          */
        override def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef], target:
        ActorRef, factory: ChildActorFactory, conditionState: => Boolean): Boolean = {
          subject should be(actor)
          target should be(sender())
          factory should be(this)
          conditionState shouldBe availableMediaArrived
          val allDeps = persistenceManagerActorRef :: activeProcessors.toList
          deps should contain theSameElementsAs allDeps
          closeRequestCounter.incrementAndGet() < 2
        }

        /**
          * Records this invocation.
          */
        override def onCloseComplete(): Unit = closeCompleteCounter.incrementAndGet()

        /**
          * Records this invocation.
          */
        override def onConditionSatisfied(): Unit = conditionSatisfiedCounter.incrementAndGet()
      })

    /**
     * Creates a mock for the central configuration object.
      *
      * @return the mock configuration
     */
    private def createConfig() = {
      val config = mock[MediaArchiveConfig]
      when(config.metaDataUpdateChunkSize).thenReturn(2)
      when(config.metaDataMaxMessageSize).thenReturn(MaxMessageSize)
      config
    }
  }
}