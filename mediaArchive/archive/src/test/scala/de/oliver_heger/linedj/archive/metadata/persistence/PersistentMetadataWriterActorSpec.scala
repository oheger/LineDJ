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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetadataWriterActor.{MediumData, ProcessMedium}
import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetadata, MediaMetadata, MetadataChunk, MetadataResponse}
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import org.apache.pekko.Done
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.mockito.ArgumentMatchers.{anyString, eq as eqArg}
import org.mockito.Mockito.*
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, Succeeded}
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object PersistentMetadataWriterActorSpec:
  /** A test medium ID. */
  private val TestMedium = MediumID("testMedium", Some("Test"))

  /** Another medium ID. */
  private val OtherMedium = MediumID("otherMedium", None)

  /** The block size used by tests. */
  private val BlockSize = 20

  /**
    * A data class for storing information about a response message that has
    * been sent to the triggering actor.
    *
    * @param msg    the message
    * @param sender the sending actor
    */
  private case class TriggerResponse(msg: Any, sender: ActorRef)

  /**
    * Generates a metadata object based on the given index.
    *
    * @param index the index
    * @return the metadata object
    */
  private def metaData(index: Int): MediaMetadata =
    MediaMetadata.UndefinedMediaData.copy(title = Some("TestSöng" + index), size = index, checksum = "check" + index)

  /**
    * Generates the URI for a test song based on the given index.
    *
    * @param index the index
    * @return the URI of this test song
    */
  private def uri(index: Int): String = "song://TestSong" + index

  /**
    * Generates a chunk of metadata containing test songs in a given index
    * range.
    *
    * @param startIndex the start index (inclusive)
    * @param endIndex   the end index (inclusive)
    * @param complete   the complete flag
    * @param mediumID   the ID of the medium
    * @return the chunk of metadata
    */
  private def chunk(startIndex: Int, endIndex: Int, complete: Boolean, mediumID: MediumID = TestMedium):
  MetadataResponse =
    val songMapping = (startIndex to endIndex) map (i => (uri(i), metaData(i)))
    MetadataResponse(MetadataChunk(mediumID, songMapping.toMap, complete), 0)

/**
  * Test class for ''PersistentMetaDataWriterActor''.
  */
class PersistentMetadataWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AsyncFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper
  with MockitoSugar:

  import PersistentMetadataWriterActorSpec.*

  def this() = this(ActorSystem("PersistentMetadataWriterActorSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    tearDownTestFile()

  "A PersistentMetadataWriterActor" should "register at the metadata manager" in :
    val msg = PersistentMetadataWriterActor.ProcessMedium(TestMedium, createPathInDirectory("data.mdt"),
      testActor, 0)
    val actor = system.actorOf(Props(classOf[PersistentMetadataWriterActor], 50))

    actor ! msg
    expectMsg(GetMetadata(TestMedium, registerAsListener = true, 0))
    Succeeded

  /**
    * Creates a test reference to the test actor.
    *
    * @return the test reference
    */
  private def createTestActorRef(): TestActorRef[PersistentMetadataWriterActor] =
    TestActorRef[PersistentMetadataWriterActor](Props
      (classOf[PersistentMetadataWriterActor], 10))

  /**
    * Creates a ''MediumData'' object to be passed to a future result handler.
    *
    * @param optSenderActor an option for the sending actor; if undefined, an
    *                       anonymous test probe is used
    * @return the data object
    */
  private def createMediumData(optSenderActor: Option[ActorRef] = None): MediumData =
    MediumData(processMessage(null, TestMedium, 0), 0, Map.empty,
      optSenderActor getOrElse TestProbe().ref)

  it should "create a default FutureIOResultHandler" in :
    val actor = createTestActorRef()

    actor.underlyingActor.resultHandler should not be null

  it should "use a result handler that logs failures when creating the future" in :
    val log = mock[LoggingAdapter]
    val promise = Promise[IOResult]()
    val ex = new Exception("Test exception")
    val actor = createTestActorRef()
    val writerActor = actor.underlyingActor

    writerActor.resultHandler.handleFutureResult(writerActor.context, promise.future, testActor,
      log, createMediumData())
    promise complete Failure(ex)
    expectMsg(PersistentMetadataWriterActor.StreamOperationComplete)
    verify(log).error(eqArg(ex), anyString())
    Succeeded

  it should "use a result handler that notifies the sender about failed operations" in :
    val log = mock[LoggingAdapter]
    val promise = Promise[IOResult]()
    val actor = createTestActorRef()
    val writerActor = actor.underlyingActor
    val triggerHelper = new TriggerActorTestHelper
    val mediumData = createMediumData(triggerHelper.trigger)

    writerActor.resultHandler.handleFutureResult(writerActor.context, promise.future,
      actor, log, mediumData)
    promise complete Failure(new Exception("Test exception"))
    val response = triggerHelper.nextMessage()
    response.msg should be(PersistentMetadataWriterActor.MetadataWritten(mediumData.process,
      success = false))
    response.sender should be(actor)

  it should "use a result handler that notifies the sender about successful operations" in :
    val ioResult = IOResult(100L, Success(Done))
    val promise = Promise[IOResult]()
    val actor = createTestActorRef()
    val writerActor = actor.underlyingActor
    val triggerHelper = new TriggerActorTestHelper
    val mediumData = createMediumData(triggerHelper.trigger)

    writerActor.resultHandler.handleFutureResult(writerActor.context, promise.future,
      actor, mock[LoggingAdapter], mediumData)
    promise complete Success(ioResult)
    val response = triggerHelper.nextMessage()
    response.msg should be(PersistentMetadataWriterActor.MetadataWritten(mediumData.process,
      success = true))
    response.sender should be(actor)

  /**
    * Checks whether the specified sequence of results contains all expected
    * results.
    *
    * @param futResults the sequence with results
    * @param startIndex the start index (inclusive)
    * @param endIndex   the end index (inclusive)
    * @param mid        the medium ID
    */
  private def checkProcessingResults(futResults: Future[List[MetadataProcessingSuccess]],
                                     startIndex: Int,
                                     endIndex: Int,
                                     mid: MediumID = TestMedium): Future[Assertion] =
    val expResults = (startIndex to endIndex) map (i => MetadataProcessingSuccess(mid, MediaFileUri(uri(i)),
      metaData(i)))
    futResults.map { results =>
      results should contain theSameElementsAs expResults
    }

  /**
    * Creates a test actor instance and sends it a ''ProcessMedium'' message.
    *
    * @param handler      the handler to be used
    * @param target       the target file to be written
    * @param mid          the medium ID
    * @param resolvedSize the resolved size
    * @return the test actor
    */
  private def createActorForMedium(handler: FutureIOResultHandler, target: Path, mid: MediumID =
  TestMedium, resolvedSize: Int = 0): ActorRef =
    val actor = createTestActor(handler)
    actor ! processMessage(target, mid, resolvedSize)
    actor

  /**
    * Creates a test actor that uses the specified result handler.
    *
    * @param handler the future result handler
    * @return the test actor instance
    */
  private def createTestActor(handler: FutureIOResultHandler): ActorRef =
    system.actorOf(Props(classOf[PersistentMetadataWriterActor], BlockSize, handler))

  /**
    * Returns a message that triggers the processing of a medium.
    *
    * @param target       the target file
    * @param mid          the medium ID
    * @param resolvedSize the number of resolved files
    * @return the message
    */
  private def processMessage(target: Path, mid: MediumID, resolvedSize: Int): ProcessMedium =
    PersistentMetadataWriterActor.ProcessMedium(mid, target, TestProbe().ref, resolvedSize)

  /**
    * Parses a file with metadata and returns all extracted results.
    *
    * @param file the file to be parsed
    * @param mid  the medium ID
    * @return a [[Future]] with the extracted results
    */
  private def parseMetadata(file: Path, mid: MediumID = TestMedium): Future[List[MetadataProcessingSuccess]] =
    val source = MetadataParser.parseMetadata(FileIO.fromPath(file), mid)
    val sink = Sink.fold[List[MetadataProcessingSuccess], MetadataProcessingSuccess](List.empty) { (lst, data) =>
      data :: lst
    }
    source.runWith(sink).map(_.reverse)

  it should "write a metadata file when sufficient metadata is available" in :
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("meta.mdt")
    val actor = createActorForMedium(handler, target, resolvedSize = 10)

    actor ! chunk(1, 10 + BlockSize, complete = false)
    handler.await()
    checkProcessingResults(parseMetadata(target), 1, 10 + BlockSize)

  it should "pass a correct MediumData object to the future result handler" in :
    val procMsg = processMessage(createPathInDirectory("metaData.mdt"), TestMedium, 0)
    val handler = new TestFutureResultHandler(new CountDownLatch(1)):
      override protected def performChecks(data: MediumData): Unit =
        data.process should be(procMsg)
        data.trigger should be(testActor)
    val actor = createTestActor(handler)
    actor ! procMsg

    actor ! chunk(1, 10 + BlockSize, complete = false)
    handler.await()
    Succeeded

  it should "not write a file before sufficient metadata is available" in :
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("other.mdt")
    val actor = createActorForMedium(handler, createPathInDirectory("meta.mdt"))
    actor ! processMessage(target, OtherMedium, 0)

    actor ! chunk(1, BlockSize - 1, complete = false, mediumID = OtherMedium)
    actor ! chunk(1, BlockSize, complete = false)
    handler.await()
    Files exists target shouldBe false

  it should "write a file for a chunk with complete flag set to true" in :
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("metaSmall.mdt")
    val actor = createActorForMedium(handler, target)

    actor ! chunk(1, BlockSize - 1, complete = true)
    handler.await()
    checkProcessingResults(parseMetadata(target), 1, BlockSize - 1)

  it should "ignore a chunk for an unknown medium" in :
    val actor = createTestActorRef()

    actor receive chunk(1, BlockSize, complete = false)
    Succeeded

  it should "handle multiple chunks for a medium" in :
    val handler = new TestFutureResultHandler(new CountDownLatch(2))
    val target = createPathInDirectory("metaMulti.mdt")
    val actor = createActorForMedium(handler, target, resolvedSize = 1)

    actor ! chunk(1, BlockSize - 1, complete = false)
    actor ! chunk(BlockSize, BlockSize + 5, complete = false)
    actor ! chunk(BlockSize + 6, 2 * BlockSize + 7, complete = false)
    handler.await()
    checkProcessingResults(parseMetadata(target), 1, 2 * BlockSize + 7)

  it should "take the initial resolved count into account" in :
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("otherUnresolved.mdt")
    val actor = createActorForMedium(handler, createPathInDirectory("meta.mdt"))
    actor ! processMessage(target, OtherMedium, 1)

    actor ! chunk(1, BlockSize, complete = false, mediumID = OtherMedium)
    actor ! chunk(1, BlockSize, complete = false)
    handler.await()
    Files exists target shouldBe false

  it should "remove a medium from the process map when the last chunk was received" in :
    val handler = new TestFutureResultHandler(new CountDownLatch(2))
    val target = createPathInDirectory("metaRemoved.mdt")
    val target2 = createPathInDirectory("metaStandard.mdt")
    val actor = createActorForMedium(handler, target)
    actor ! processMessage(target2, OtherMedium, resolvedSize = 0)

    actor ! chunk(1, 10, complete = true)
    actor ! chunk(11, 3 * BlockSize, complete = false)
    actor ! chunk(1, 2, complete = true, mediumID = OtherMedium)
    handler.await()
    checkProcessingResults(parseMetadata(target), 1, 10)

  it should "only write a single file at a given time" in :
    val counter = new AtomicInteger
    val target = createPathInDirectory("metaFirstMedium.mdt")
    val target2 = createPathInDirectory("metaSecondMedium.mdt")
    val handler = new TestFutureResultHandler(new CountDownLatch(2)):
      /**
        * Checks that the 2nd file has not been created yet.
        */
      override protected def performChecks(data: MediumData): Unit =
        if counter.incrementAndGet() == 1 then
          Files exists target2 shouldBe false
    val actor = createActorForMedium(handler, target)
    actor ! processMessage(target2, OtherMedium, resolvedSize = 0)

    actor ! chunk(1, 64, complete = false)
    actor ! chunk(1, 1, complete = true, mediumID = OtherMedium)
    handler.await()
    checkProcessingResults(parseMetadata(target), 1, 64)
    checkProcessingResults(parseMetadata(target2, OtherMedium), 1, 1, OtherMedium)

  it should "override existing files" in :
    val target = writeFileContent(createFileReference(), FileTestHelper.TestData * 10)
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val actor = createActorForMedium(handler, target)

    actor ! chunk(1, 2, complete = true)
    handler.await()
    checkProcessingResults(parseMetadata(target), 1, 2)

  /**
    * A specialized ''FutureIOResultHandler'' implementation that can execute
    * additional checks and notify test code on completion of a stream
    * operation.
    *
    * @param latch the latch for notifying test code
    */
  private class TestFutureResultHandler(latch: CountDownLatch) extends FutureIOResultHandler:
    /**
      * Waits until the stream operation is complete.
      */
    def await(): Unit =
      latch.await(5, TimeUnit.SECONDS) shouldBe true

    /**
      * @inheritdoc This implementation invokes the test code and triggers the
      *             latch to notify test code that the stream operation is
      *             complete.
      */
    override protected def onResultComplete(result: Try[_], actor: ActorRef, log: LoggingAdapter,
                                            data: MediumData)
    : Unit =
      performChecks(data)
      super.onResultComplete(result, actor, log, data)
      latch.countDown()

    /**
      * @inheritdoc Overrides this method to not send any messages.
      */
    override protected def notifyTriggerActor(data: MediumData, actor: ActorRef,
                                              success: Boolean): Unit = {}

    /**
      * Executes some checks directly after the stream was written. This can be
      * used in derived classes to implement some additional test conditions.
      * This base implementation is empty.
      *
      * @param data the data object for the current wirte operation
      */
    protected def performChecks(data: MediumData): Unit = {
    }

  /**
    * A helper class to verify whether the triggering actor is invoked
    * correctly.
    */
  private class TriggerActorTestHelper:
    /** A queue for storing messages sent to the trigger actor. */
    private val queue = new LinkedBlockingQueue[TriggerResponse]

    /**
      * An actor simulating the triggering actor. This actor just stores all
      * messages it receives in a queue.
      */
    private val triggerActor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case msg => queue.offer(TriggerResponse(msg, sender()))
      }
    }))

    /**
      * Returns an option with the trigger actor managed by this instance.
      *
      * @return the trigger actor option (which is always defined)
      */
    def trigger: Option[ActorRef] = Some(triggerActor)

    /**
      * Returns information about the next message that has been sent to the
      * trigger actor. Fails the test if no message has been received.
      *
      * @return information about the next message
      */
    def nextMessage(): TriggerResponse =
      val msg = queue.poll(3, TimeUnit.SECONDS)
      msg should not be null
      msg

