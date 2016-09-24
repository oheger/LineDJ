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

package de.oliver_heger.linedj.archive.metadata.persistence

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.stream.{ActorMaterializer, IOResult}
import akka.stream.scaladsl.{FileIO, Source}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.metadata.MetaDataProcessingResult
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataWriterActor.{MediumData, ProcessMedium}
import de.oliver_heger.linedj.archive.metadata.persistence.parser.{JSONParser, MetaDataParser, ParserImpl, ParserTypes}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetaData, MediaMetaData, MetaDataChunk}
import org.mockito.Mockito._
import org.mockito.Matchers.{anyString, eq => eqArg}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object PersistentMetaDataWriterActorSpec {
  /** A test medium ID. */
  private val TestMedium = MediumID("testMedium", Some("Test"))

  /** Another medium ID. */
  private val OtherMedium = MediumID("otherMedium", None)

  /** A mapping from URIs to paths. */
  private val UriPathMapping = createUriPathMapping()

  /** The block size used by tests. */
  private val BlockSize = 20

  /** A JSON parser used by tests. */
  private val Parser = new MetaDataParser(ParserImpl, JSONParser.jsonParser(ParserImpl))

  /**
    * Generates a meta data object based on the given index.
    *
    * @param index the index
    * @return the meta data object
    */
  private def metaData(index: Int): MediaMetaData =
    MediaMetaData(title = Some("TestSöng" + index))

  /**
    * Generates the URI for a test song based on the given index.
    *
    * @param index the index
    * @return the URI of this test song
    */
  private def uri(index: Int): String = "song://TestSong" + index

  /**
    * Generates the path for a test song based on the given index.
    *
    * @param index the index
    * @return the path of this test song
    */
  private def path(index: Int): Path = Paths.get("testPath" + index + ".mp3")

  /**
    * Generates a chunk of meta data containing test songs in a given index
    * range.
    *
    * @param startIndex the start index (inclusive)
    * @param endIndex   the end index (inclusive)
    * @param complete   the complete flag
    * @param mediumID   the ID of the medium
    * @return the chunk of meta data
    */
  private def chunk(startIndex: Int, endIndex: Int, complete: Boolean, mediumID: MediumID =
  TestMedium): MetaDataChunk = {
    val songMapping = (startIndex to endIndex) map (i => (uri(i), metaData(i)))
    MetaDataChunk(mediumID, songMapping.toMap, complete)
  }

  /**
    * Generates a global mapping from URIs to files.
    *
    * @return the URI to path mapping
    */
  private def createUriPathMapping(): Map[String, FileData] =
    (1 to 100).map(i => (uri(i), FileData(path(i), i))).toMap
}

/**
  * Test class for ''PersistentMetaDataWriterActor''.
  */
class PersistentMetaDataWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper with
  MockitoSugar {

  import PersistentMetaDataWriterActorSpec._

  def this() = this(ActorSystem("PersistentMetaDataWriterActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    tearDownTestFile()
  }

  "A PersistentMetaDataWriterActor" should "register at the meta data manager" in {
    val msg = PersistentMetaDataWriterActor.ProcessMedium(TestMedium, createPathInDirectory("data" +
      ".mdt"), testActor, Map.empty, 0)
    val actor = system.actorOf(Props(classOf[PersistentMetaDataWriterActor], 50))

    actor ! msg
    expectMsg(GetMetaData(TestMedium, registerAsListener = true))
  }

  /**
    * Creates a test reference to the test actor.
    *
    * @return the test reference
    */
  private def createTestActorRef(): TestActorRef[PersistentMetaDataWriterActor] =
    TestActorRef[PersistentMetaDataWriterActor](Props
    (classOf[PersistentMetaDataWriterActor], 10))

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

  it should "create a default FutureIOResultHandler" in {
    val actor = createTestActorRef()

    actor.underlyingActor.resultHandler should not be null
  }

  it should "use a result handler that logs failures when creating the future" in {
    val log = mock[LoggingAdapter]
    val promise = Promise[IOResult]()
    val ex = new Exception("Test exception")
    val actor = createTestActorRef()
    val writerActor = actor.underlyingActor

    writerActor.resultHandler.handleFutureResult(writerActor.context, promise.future, testActor,
      log, createMediumData())
    promise complete Failure(ex)
    expectMsg(PersistentMetaDataWriterActor.StreamOperationComplete)
    verify(log).error(eqArg(ex), anyString())
  }

  it should "use a result handler that notifies the sender about failed operations" in {
    val log = mock[LoggingAdapter]
    val promise = Promise[IOResult]()
    val actor = createTestActorRef()
    val writerActor = actor.underlyingActor
    val mediumData = createMediumData(Some(testActor))

    writerActor.resultHandler.handleFutureResult(writerActor.context, promise.future,
      TestProbe().ref, log, mediumData)
    promise complete Failure(new Exception("Test exception"))
    expectMsg(PersistentMetaDataWriterActor.MetaDataWritten(mediumData.process,
      success = false))
  }

  /**
    * Creates an ''IOResult'' for a failed operation. Unfortunately,
    * ''IOResult'' objects cannot be created directly nor mocked. Therefore, a
    * real operation has to be executed.
    *
    * @return the ''IOResult''
    */
  private def createFailedIOResult(): IOResult = {
    val path = createPathInDirectory("Some path").resolve("someFile.tst")
    createIOResult(path)
  }

  /**
    * Creates an ''IOResult'' for an operation on the given path.
    * Unfortunately, ''IOResult'' objects cannot be created directly nor
    * mocked. Therefore, a real operation has to be executed.
    *
    * @param path the path for the operation
    * @return the ''IOResult''
    */
  private def createIOResult(path: Path): IOResult = {
    implicit val mat = ActorMaterializer()
    val futureResult = Source.single(ByteString("Test")).runWith(FileIO.toPath(path))
    Await.result(futureResult, 5.seconds)
  }

  it should "use a result handler that logs failed IOResults" in {
    val log = mock[LoggingAdapter]
    val ioResult = createFailedIOResult()
    val promise = Promise[IOResult]()
    val ex = ioResult.status.asInstanceOf[Failure[IOResult]].exception
    val actor = createTestActorRef()
    val writerActor = actor.underlyingActor

    writerActor.resultHandler.handleFutureResult(writerActor.context, promise.future, testActor,
      log, createMediumData())
    promise complete Success(ioResult)
    expectMsg(PersistentMetaDataWriterActor.StreamOperationComplete)
    verify(log).error(eqArg(ex), anyString())
  }

  it should "use a result handler that notifies the sender about successful operations" in {
    val file = createPathInDirectory("successfulIOOperation.tmp")
    val ioResult = createIOResult(file)
    val promise = Promise[IOResult]()
    val actor = createTestActorRef()
    val writerActor = actor.underlyingActor
    val mediumData = createMediumData(Some(testActor))

    writerActor.resultHandler.handleFutureResult(writerActor.context, promise.future,
      TestProbe().ref, mock[LoggingAdapter], mediumData)
    promise complete Success(ioResult)
    expectMsg(PersistentMetaDataWriterActor.MetaDataWritten(mediumData.process,
      success = true))
  }

  /**
    * Checks whether the specified sequence of results contains all expected
    * results.
    *
    * @param results    the sequence with results
    * @param startIndex the start index (inclusive)
    * @param endIndex   the end index (inclusive)
    * @param mid        the medium ID
    */
  private def checkProcessingResults(results: Seq[MetaDataProcessingResult], startIndex: Int,
                                     endIndex: Int, mid: MediumID = TestMedium): Unit = {
    val expResults = (startIndex to endIndex) map (i => MetaDataProcessingResult(path(i), mid,
      uri(i), metaData(i)))
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
  TestMedium, resolvedSize: Int = 0): ActorRef = {
    val actor = createTestActor(handler)
    actor ! processMessage(target, mid, resolvedSize)
    actor
  }

  /**
    * Creates a test actor that uses the specified result handler.
    *
    * @param handler the future result handler
    * @return the test actor instance
    */
  private def createTestActor(handler: FutureIOResultHandler): ActorRef =
  system.actorOf(Props(classOf[PersistentMetaDataWriterActor], BlockSize, handler))

  /**
    * Returns a message that triggers the processing of a medium.
    *
    * @param target       the target file
    * @param mid          the medium ID
    * @param resolvedSize the number of resolved files
    * @return the message
    */
  private def processMessage(target: Path, mid: MediumID, resolvedSize: Int): ProcessMedium =
    PersistentMetaDataWriterActor.ProcessMedium(mid, target, TestProbe().ref, UriPathMapping,
      resolvedSize)

  /**
    * Parses a file with meta data and returns all extracted results.
    *
    * @param file the file to be parsed
    * @param mid  the medium ID
    * @return the extracted results
    */
  private def parseMetaData(file: Path, mid: MediumID = TestMedium):
  Seq[MetaDataProcessingResult] = {
    val json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    val (results, failure) = invokeParser(json, mid)
    failure shouldBe 'empty
    results
  }

  /**
    * Actually parses a JSON string. This method simulates a real chunk-wise
    * parsing operation. If the input string is longer than a given threshold,
    * it is parsed in two chunks. This is the same as in production code.
    *
    * @param json the JSON string to be parsed
    * @param mid  the medium ID
    * @return a tuple with the results and the optional failure
    */
  private def invokeParser(json: String, mid: MediumID): (Seq[MetaDataProcessingResult],
    Option[ParserTypes.Failure]) = {
    val split = json.length > 1024
    if (split) {
      val (res1, fail1) = Parser.processChunk(json.substring(0, 1024), mid, lastChunk = false, None)
      val (res2, fail2) = Parser.processChunk(json.substring(1024), mid, lastChunk = true, fail1)
      (res1.toList ::: res2.toList, fail2)
    } else {
      Parser.processChunk(json, mid, lastChunk = true, None)
    }
  }

  it should "write a meta data file when sufficient meta data is available" in {
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("meta.mdt")
    val actor = createActorForMedium(handler, target, resolvedSize = 10)

    actor ! chunk(1, 10 + BlockSize, complete = false)
    handler.await()
    checkProcessingResults(parseMetaData(target), 1, 10 + BlockSize)
  }

  it should "pass a correct MediumData object to the future result handler" in {
    val procMsg = processMessage(createPathInDirectory("metaData.mdt"), TestMedium, 0)
    val handler = new TestFutureResultHandler(new CountDownLatch(1)) {
      override protected def performChecks(data: MediumData): Unit = {
        data.process should be(procMsg)
        data.trigger should be(testActor)
      }
    }
    val actor = createTestActor(handler)
    actor ! procMsg

    actor ! chunk(1, 10 + BlockSize, complete = false)
    handler.await()
  }

  it should "not write a file before sufficient meta data is available" in {
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("other.mdt")
    val actor = createActorForMedium(handler, createPathInDirectory("meta.mdt"), resolvedSize = 0)
    actor ! processMessage(target, OtherMedium, 0)

    actor ! chunk(1, BlockSize - 1, complete = false, mediumID = OtherMedium)
    actor ! chunk(1, BlockSize, complete = false)
    handler.await()
    Files exists target shouldBe false
  }

  it should "write a file for a chunk with complete flag set to true" in {
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("metaSmall.mdt")
    val actor = createActorForMedium(handler, target, resolvedSize = 0)

    actor ! chunk(1, BlockSize - 1, complete = true)
    handler.await()
    checkProcessingResults(parseMetaData(target), 1, BlockSize - 1)
  }

  it should "ignore a chunk for an unknown medium" in {
    val actor = createTestActorRef()

    actor receive chunk(1, BlockSize, complete = false)
  }

  it should "handle multiple chunks for a medium" in {
    val handler = new TestFutureResultHandler(new CountDownLatch(2))
    val target = createPathInDirectory("metaMulti.mdt")
    val actor = createActorForMedium(handler, target, resolvedSize = 1)

    actor ! chunk(1, BlockSize - 1, complete = false)
    actor ! chunk(BlockSize, BlockSize + 5, complete = false)
    actor ! chunk(BlockSize + 6, 2 * BlockSize + 7, complete = false)
    handler.await()
    checkProcessingResults(parseMetaData(target), 1, 2 * BlockSize + 7)
  }

  it should "take the initial resolved count into account" in {
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val target = createPathInDirectory("otherUnresolved.mdt")
    val actor = createActorForMedium(handler, createPathInDirectory("meta.mdt"), resolvedSize = 0)
    actor ! processMessage(target, OtherMedium, 1)

    actor ! chunk(1, BlockSize, complete = false, mediumID = OtherMedium)
    actor ! chunk(1, BlockSize, complete = false)
    handler.await()
    Files exists target shouldBe false
  }

  it should "remove a medium from the process map when the last chunk was received" in {
    val handler = new TestFutureResultHandler(new CountDownLatch(2))
    val target = createPathInDirectory("metaRemoved.mdt")
    val target2 = createPathInDirectory("metaStandard.mdt")
    val actor = createActorForMedium(handler, target, resolvedSize = 0)
    actor ! processMessage(target2, OtherMedium, resolvedSize = 0)

    actor ! chunk(1, 10, complete = true)
    actor ! chunk(11, 3 * BlockSize, complete = false)
    actor ! chunk(1, 2, complete = true, mediumID = OtherMedium)
    handler.await()
    checkProcessingResults(parseMetaData(target), 1, 10)
  }

  it should "only write a single file at a given time" in {
    val counter = new AtomicInteger
    val target = createPathInDirectory("metaFirstMedium.mdt")
    val target2 = createPathInDirectory("metaSecondMedium.mdt")
    val handler = new TestFutureResultHandler(new CountDownLatch(2)) {
      /**
        * Checks that the 2nd file has not been created yet.
        */
      override protected def performChecks(data: MediumData): Unit = {
        if (counter.incrementAndGet() == 1) {
          Files exists target2 shouldBe false
        }
      }
    }
    val actor = createActorForMedium(handler, target, resolvedSize = 0)
    actor ! processMessage(target2, OtherMedium, resolvedSize = 0)

    actor ! chunk(1, 64, complete = false)
    actor ! chunk(1, 1, complete = true, mediumID = OtherMedium)
    handler.await()
    checkProcessingResults(parseMetaData(target), 1, 64)
    checkProcessingResults(parseMetaData(target2, OtherMedium), 1, 1, OtherMedium)
  }

  it should "override existing files" in {
    val target = writeFileContent(createFileReference(), FileTestHelper.TestData * 10)
    val handler = new TestFutureResultHandler(new CountDownLatch(1))
    val actor = createActorForMedium(handler, target, resolvedSize = 0)

    actor ! chunk(1, 2, complete = true)
    handler.await()
    checkProcessingResults(parseMetaData(target), 1, 2)
  }

  /**
    * A specialized ''FutureIOResultHandler'' implementation that can execute
    * additional checks and notify test code on completion of a stream
    * operation.
    *
    * @param latch the latch for notifying test code
    */
  private class TestFutureResultHandler(latch: CountDownLatch) extends FutureIOResultHandler {
    /**
      * Waits until the stream operation is complete.
      */
    def await(): Unit = {
      latch.await(5, TimeUnit.SECONDS) shouldBe true
    }

    /**
      * @inheritdoc This implementation invokes the test code and triggers the
      *             latch to notify test code that the stream operation is
      *             complete.
      */
    override protected def onResultComplete(result: Try[_], actor: ActorRef, log: LoggingAdapter,
                                            data: MediumData)
    : Unit = {
      performChecks(data)
      super.onResultComplete(result, actor, log, data)
      latch.countDown()
    }

    /**
      * @inheritdoc Overrides this method to not send any messages.
      */
    override protected def notifyTriggerActor(data: MediumData, success: Boolean): Unit = {}

    /**
      * Executes some checks directly after the stream was written. This can be
      * used in derived classes to implement some additional test conditions.
      * This base implementation is empty.
      *
      * @param data the data object for the current wirte operation
      */
    protected def performChecks(data: MediumData): Unit = {
    }
  }

}