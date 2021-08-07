/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3.processor

import java.nio.file.{Files, Paths}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.extract.id3.model.{ID3FrameExtractor, ID3Header}
import de.oliver_heger.linedj.extract.id3.processor.ID3v2ProcessingStageSpec.FetchFrameData
import de.oliver_heger.linedj.extract.metadata.MetaDataProvider
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

object ID3v2ProcessingStageSpec {

  /**
    * A message class to be sent to the test processor actor telling it to
    * provide the frame data it has collected.
    */
  case object FetchFrameData

  /** Timeout value. */
  private val TimeoutDuration = 3.seconds

  /** The name of the test file. */
  private val TestFile = "/testID3v2Data.bin"

  /** The actual data content of the test file. */
  private val TestData = "Lorem ipsum dolor sit amet, consetetur sadipscing " +
    "elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore " +
    "magna aliquyam erat, sed diam voluptua. At vero eos et " +
    "accusam et justo duo"

  /**
    * Reads the test file and returns its content as a single byte string.
    *
    * @return the content of the test file
    */
  private def readTestFile(): ByteString = {
    val fileURI = getClass.getResource(TestFile).toURI
    val path = Paths get fileURI
    ByteString(Files readAllBytes path)
  }

  /**
    * Reads the test file and returns its content as an iteration of chunks
    * of the specified size.
    *
    * @param chunkSize the chunk size
    * @return the content as an iteration of chunks
    */
  private def readTestFileChunked(chunkSize: Int): Iterable[ByteString] =
    readTestFile().grouped(chunkSize).iterator.to(Iterable)

  /**
    * Produces a source with chunked content of the test file.
    *
    * @param chunkSize the chunk size
    * @return the source
    */
  private def chunkedSource(chunkSize: Int): Source[ByteString, Any] =
    Source(readTestFileChunked(chunkSize).toList)

  /**
    * Decodes the binary frame data to a meta data provider.
    *
    * @param header the header
    * @param data   the data
    * @return the meta data provider
    */
  private def createTagProvider(header: ID3Header, data: ByteString): MetaDataProvider = {
    val extractor = new ID3FrameExtractor(header)
    extractor.addData(data)
    extractor.createTagProvider().get
  }
}

/**
  * Test class for ''ID3v2ProcessingStage''.
  */
class ID3v2ProcessingStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {

  import ID3v2ProcessingStageSpec._

  def this() = this(ActorSystem("ID3v2ProcessingStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An ID3v2ProcessingStage" should "extract ID3 data using a normal chunk size" in {
    val helper = new ID3StageTestHelper

    helper.checkProcessingResult(helper.simulateProcessing(chunkedSource(1024)))
  }

  it should "extract ID3 data using a huge chunk size" in {
    val helper = new ID3StageTestHelper
    val source = Source.single(readTestFile())

    helper.checkProcessingResult(helper.simulateProcessing(source))
  }

  it should "extract ID3 data using a small chunk size" in {
    val helper = new ID3StageTestHelper

    helper.checkProcessingResult(helper.simulateProcessing(chunkedSource(8)))
  }

  it should "handle an undefined processor actor" in {
    val stage = new ID3v2ProcessingStage(None)
    val futStream = chunkedSource(2048).via(stage).runFold(ByteString())(_ ++ _)
    val streamResult = Await.result(futStream, TimeoutDuration)
    streamResult.utf8String should be(TestData)
  }

  it should "handle an empty stream" in {
    val helper = new ID3StageTestHelper

    val (frames, bs) = helper.simulateProcessing(Source.single(ByteString.empty))
    frames.frameMap should have size 0
    bs.length should be(0)
  }

  it should "send an additional message for incomplete ID3 data" in {
    val src = Source.single(readTestFile() take 512)
    val helper = new ID3StageTestHelper

    helper.runStream(src)
    helper.expectMessage[IncompleteID3Frame].frameHeader.version should be(3)
  }

  /**
    * Test helper class managing a test instance and some dependencies.
    */
  private class ID3StageTestHelper {
    /** Queue for communication with a test actor. */
    private val queue = new LinkedBlockingQueue[AnyRef]

    /**
      * Runs a stream with a test stage and returns the data passed to the
      * sink. Data sent to the simulated processor actor is not yet
      * fetched.
      *
      * @param src the source to be executed
      * @return the data passed to the sink
      */
    def runStream(src: Source[ByteString, Any]): ByteString = {
      val procActor = system.actorOf(Props(classOf[FrameProcessingActor], queue))
      val stage = new ID3v2ProcessingStage(Some(procActor))
      val futStream = src.via(stage).runFold(ByteString())(_ ++ _)
      val streamResult = Await.result(futStream, TimeoutDuration)
      procActor ! FetchFrameData
      streamResult
    }

    /**
      * Runs a stream with a test stage and collects the data it sends to the
      * test processor actor.
      *
      * @param src the source to be executed
      * @return a tuple with the produced frame data and remaining data passed
      *         to the stream
      */
    def simulateProcessing(src: Source[ByteString, Any]): (ProcessedFrames, ByteString) = {
      val streamResult = runStream(src)
      (expectMessage[ProcessedFrames], streamResult)
    }

    /**
      * Expects that a message of the specified type has been send from the
      * test processor actor.
      *
      * @param c the class tag
      * @tparam T the type of the message
      * @return the message
      */
    def expectMessage[T](implicit c: ClassTag[T]): T = {
      val msg = queue.poll(TimeoutDuration.toMillis, TimeUnit.MILLISECONDS)
      msg should not be null
      val cls = c.runtimeClass.asInstanceOf[Class[T]]
      cls.cast(msg)
    }

    /**
      * Checks the data that has been extracted from the ID3 frames.
      *
      * @param frames the data object with extracted frames
      */
    def checkProcessedFrames(frames: ProcessedFrames): Unit = {
      frames.frameMap should have size 2
      frames.frameMap.foreach { e =>
        val provider = createTagProvider(e._1, e._2)
        e._1.version match {
          case 3 =>
            provider.title should be(Some("Testtitle"))
            provider.artist should be(Some("Testinterpret"))
            provider.inceptionYear should be(Some(2006))

          case 4 =>
            provider.title should be(Some("Test Title"))
            provider.album should be(Some("Test Album"))
            provider.artist should be(Some("Test Artist"))
        }
      }
    }

    /**
      * Checks the full result of processing operation. This method checks
      * both extracted frames and the content passed through the stream.
      *
      * @param result the result of the processing operation
      */
    def checkProcessingResult(result: (ProcessedFrames, ByteString)): Unit = {
      checkProcessedFrames(result._1)
      result._2.utf8String should be(TestData)
    }
  }

}

/**
  * Internally used case class defining the results produced by the frame
  * processing actor. An instance collects the single frames that have been
  * processed and their content.
  *
  * @param frameMap a map with information about frames and their content
  */
case class ProcessedFrames(frameMap: Map[ID3Header, ByteString])

/**
  * An actor class that accepts the process messages generated by the test
  * stage.
  */
class FrameProcessingActor(queue: LinkedBlockingQueue[Any])
  extends Actor {
  /** A map with data about frames that has been received. */
  private var frameMap = Map.empty[ID3Header, ByteString]

  /** The header of the currently processed frame. */
  private var currentHeader: Option[ID3Header] = None

  /* The content for the currently processed frame.*/
  private var currentData = ByteString()

  override def receive: Receive = {
    case ProcessID3FrameData(header, data, last) =>
      val actHeader = currentHeader getOrElse header
      assert(actHeader.version == header.version,
        s"Invalid header! Expected $actHeader, was $header.")
      currentHeader = Some(header)
      currentData ++= data
      if (last) {
        assert(!frameMap.contains(actHeader), "Duplicated header!")
        frameMap += actHeader -> currentData
        currentData = ByteString.empty
        currentHeader = None
      }

    case FetchFrameData =>
      queue offer ProcessedFrames(frameMap)

    case ifr: IncompleteID3Frame =>
      queue offer ifr
  }
}
