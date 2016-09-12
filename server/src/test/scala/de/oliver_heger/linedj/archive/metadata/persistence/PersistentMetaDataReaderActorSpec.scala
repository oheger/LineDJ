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
import java.nio.file.Paths

import akka.actor.{Terminated, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.io.{ChannelHandler, FileReaderActor}
import de.oliver_heger.linedj.archive.media.MediumID
import de.oliver_heger.linedj.archive.metadata.{MediaMetaData, MetaDataProcessingResult}
import de.oliver_heger.linedj.archive.metadata.persistence.parser.{MetaDataParser, ParseError, ParserTypes}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object PersistentMetaDataReaderActorSpec {
  /** A path to a test file. */
  private val MetaDataPath = Paths get "someTestMetaDataFile.json"

  /** A test medium ID. */
  private val TestMedium = MediumID("someMedium", None)

  /** The read chunk size. */
  private val ReadChunkSize = 2048

  /** The message to initialize the test actor. */
  private val InitMessage = PersistentMetaDataReaderActor.ReadMetaDataFile(MetaDataPath, TestMedium)

  /**
    * Generates text for a chunk of the test file.
    *
    * @param index the index of the chunk
    * @return the generated text
    */
  private def generateChunkText(index: Int): String =
    s"$index. chunk of test file $index"

  /**
    * Generates a ''ReadResult'' that contains the specified chunk of data.
    * Note that this result contains a bigger array than needed to test
    * whether the length parameter is evaluated.
    *
    * @param index the index of the chunk
    * @return the read result
    */
  private def generateReadResult(index: Int): FileReaderActor.ReadResult = {
    val chunkBytes = generateChunkText(index).getBytes(StandardCharsets.ISO_8859_1)
    val data = new Array[Byte](chunkBytes.length + 42)
    System.arraycopy(chunkBytes, 0, data, 0, chunkBytes.length)
    FileReaderActor.ReadResult(data, chunkBytes.length)
  }

  /**
    * Generates a failure object based on the specified index.
    *
    * @param index the index to make the failure unique
    * @return the failure
    */
  private def generateFailure(index: Int): ParserTypes.Failure =
    ParserTypes.Failure(ParseError(), isCommitted = true, List(index))

  /**
    * Generates a sequence of processing results to be returned by the mock
    * parser. The index is used to make the result unique.
    *
    * @param index the index
    * @return the sequence of processing result objects
    */
  private def generateProcessingResults(index: Int): Seq[MetaDataProcessingResult] =
    List(MetaDataProcessingResult(Paths.get("someResult" + index), TestMedium, "someUri" + index,
      MediaMetaData(title = Some("Song " + index))))
}

/**
  * Test class for ''PersistentMetaDataReaderActor''.
  */
class PersistentMetaDataReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import PersistentMetaDataReaderActorSpec._

  def this() = this(ActorSystem("PersistentMetaDataReaderActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A PersistentMetaDataReaderActor" should "start reading the file when triggered" in {
    val helper = new PersistentMetaDataReaderTestHelper

    helper.metaReaderActor ! InitMessage
    helper.reader.expectMsg(ChannelHandler.InitFile(MetaDataPath))
    helper.reader.expectMsg(FileReaderActor.ReadData(ReadChunkSize))
  }

  it should "process the first chunk of data" in {
    val helper = new PersistentMetaDataReaderTestHelper

    helper send generateReadResult(1)
    helper.reader.expectMsg(FileReaderActor.ReadData(ReadChunkSize))
    verifyZeroInteractions(helper.parser)
  }

  it should "process multiple chunks of data" in {
    val helper = new PersistentMetaDataReaderTestHelper
    val results1 = generateProcessingResults(1)
    val results2 = generateProcessingResults(2)
    helper.prepareParser(1, lastChunk = false, lastFailure = None, result = (results1, Some
    (generateFailure(1))))
    helper.prepareParser(2, lastChunk = false, lastFailure = Some(generateFailure(1)),
      result = (results2, Some(generateFailure(2))))

    helper.send(InitMessage).send(generateReadResult(1)).send(generateReadResult(2))
    helper.parent.expectMsg(PersistentMetaDataReaderActor.ProcessingResults(results1))
    helper send generateReadResult(3)
    helper.parent.expectMsg(PersistentMetaDataReaderActor.ProcessingResults(results2))
  }

  it should "not send processing results if none were extracted in a chunk" in {
    val helper = new PersistentMetaDataReaderTestHelper
    helper.prepareParser(1, lastChunk = false, lastFailure = None, result = (Nil, Some
    (generateFailure(1))))

    helper.send(InitMessage).send(generateReadResult(1)).send(generateReadResult(2))
    helper.expectNoMessage(helper.parent)
  }

  it should "handle the last chunk correctly" in {
    val helper = new PersistentMetaDataReaderTestHelper
    val results = generateProcessingResults(1)
    helper.prepareParser(1, lastChunk = true, lastFailure = None, result = (results, None))

    helper.send(InitMessage).send(generateReadResult(1))
      .send(FileReaderActor.EndOfFile(MetaDataPath.toString))
    helper.parent.expectMsg(PersistentMetaDataReaderActor.ProcessingResults(results))
  }

  it should "stop itself when the last chunk is reached" in {
    val probe = TestProbe()
    val helper = new PersistentMetaDataReaderTestHelper
    probe watch helper.metaReaderActor
    helper.prepareParser(1, lastChunk = true, lastFailure = None, result = (Nil, None))

    helper.send(InitMessage).send(generateReadResult(1))
      .send(FileReaderActor.EndOfFile(MetaDataPath.toString))
    probe.expectMsgType[Terminated].actor should be(helper.metaReaderActor)
  }

  it should "stop itself if the underlying reader crashes" in {
    val helper = new PersistentMetaDataReaderTestHelper
    val actor = system.actorOf(PersistentMetaDataReaderActor(helper.parent.ref, helper.parser,
      ReadChunkSize))
    val probe = TestProbe()
    probe watch actor

    actor ! InitMessage // will crash because the path is invalid
    probe.expectMsgType[Terminated].actor should be(actor)
  }

  /**
    * A test helper class which manages the required dependencies.
    */
  private class PersistentMetaDataReaderTestHelper {
    /** A test probe for the parent actor. */
    val parent = TestProbe()

    /** A test probe for the reader actor. */
    val reader = TestProbe()

    /** A mock for the parser. */
    val parser = mock[MetaDataParser]

    /** The test actor. */
    val metaReaderActor = TestActorRef[PersistentMetaDataReaderActor](createProps())

    /**
      * Passes the specified message directly to the test actor's receive()
      * method.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): PersistentMetaDataReaderTestHelper = {
      metaReaderActor receive msg
      this
    }

    /**
      * Prepares the mock parser to process a chunk of data.
      *
      * @param chunkIndex the index of the chunk of data
      * @param result     the result to be returned
      * @param lastChunk  a flag if this is the last chunk
      * @return this test helper
      */
    def prepareParser(chunkIndex: Int, lastChunk: Boolean, lastFailure: Option[ParserTypes.Failure],
                      result: (Seq[MetaDataProcessingResult], Option[ParserTypes.Failure])):
    PersistentMetaDataReaderTestHelper = {
      when(parser.processChunk(generateChunkText(chunkIndex), TestMedium, lastChunk, lastFailure)
      ).thenReturn(result)
      this
    }

    /**
      * Checks that the specified actor did not receive any message.
      *
      * @param actor the actor in question
      */
    def expectNoMessage(actor: TestProbe): Unit = {
      val Message = "Ping"
      actor.ref ! Message
      actor.expectMsg(Message)
    }

    /**
      * Creates the properties for the test actor.
      *
      * @return creation properties
      */
    private def createProps(): Props =
      Props(new PersistentMetaDataReaderActor(parent.ref, parser, ReadChunkSize) with
        ChildActorFactory {
        /**
          * @inheritdoc Checks that a correct reader actor is created and returns
          *             the corresponding test reference.
          */
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() should be(classOf[FileReaderActor])
          p.args shouldBe 'empty
          reader.ref
        }
      })
  }

}
