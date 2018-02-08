/*
 * Copyright 2015-2018 The Developers Team.
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
package de.oliver_heger.linedj.extract.id3.processor

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.extract.id3.model._
import de.oliver_heger.linedj.extract.metadata.MetaDataProvider
import org.mockito.Matchers.{any, anyBoolean}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.util.Random

object ID3FrameProcessorActorSpec {
  /** Constant for a test ID3 frame header. */
  private val Header = ID3Header(2, 64)

  /** An object for generating random chunks of data. */
  private val random = new Random

  /**
    * Generates a chunk of test data.
    *
    * @return the test data
    */
  private def generateChunk(): ByteString = {
    val size = random.nextInt(1023) + 1
    val buf = new Array[Byte](size)
    random.nextBytes(buf)
    ByteString(buf)
  }
}

/**
  * Test class for ''ID3FrameProcessorActor''.
  */
class ID3FrameProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import ID3FrameProcessorActorSpec._

  def this() = this(ActorSystem("ID3FrameProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An ID3FrameProcessorActor" should "handle the first processing message" in {
    val data = generateChunk()
    val msg = ProcessID3FrameData(Header, data, lastChunk = false)
    val helper = new ID3FrameProcessorTestHelper

    helper.sendMessage(msg)
      .verifyExtractorFilled(data, complete = false)
      .expectNoMetaDataMessage()
  }

  it should "handle the last processing message" in {
    val data = generateChunk()
    val metaData = mock[MetaDataProvider]
    val msg = ProcessID3FrameData(Header, data, lastChunk = true)
    val helper = new ID3FrameProcessorTestHelper

    helper.prepareProviderCreation(metaData)
      .sendMessage(msg)
      .verifyExtractorFilled(data, complete = true)
      .expectMetaDataMessage(metaData)
  }

  it should "handle an incomplete ID3 frame message" in {
    val metaData = mock[MetaDataProvider]
    val helper = new ID3FrameProcessorTestHelper

    helper.prepareProviderCreation(metaData)
      .sendMessage(IncompleteID3Frame(Header))
      .expectMetaDataMessage(metaData)
  }

  it should "ignore frame data with an unexpected header" in {
    val OtherHeader = ID3Header(Header.version + 1, 111)
    val helper = new ID3FrameProcessorTestHelper

    helper.sendMessage(ProcessID3FrameData(OtherHeader, generateChunk(), lastChunk = false))
      .verifyExtractorNotTouched()
  }

  it should "ignore incomplete frame messages with an unexpected header" in {
    val OtherHeader = ID3Header(Header.version + 1, 111)
    val helper = new ID3FrameProcessorTestHelper

    helper.sendMessage(IncompleteID3Frame(OtherHeader))
      .verifyExtractorNotTouched()
      .expectNoMetaDataMessage()
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class ID3FrameProcessorTestHelper {
    /** The mock extractor for ID3 frame data. */
    private val extractor = createExtractor()

    /** A test probe for the collector actor. */
    private val collector = TestProbe()

    /** The processor actor to be tested. */
    private val processor = createTestActor()

    /**
      * Sends the specified message to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def sendMessage(msg: Any): ID3FrameProcessorTestHelper = {
      processor receive msg
      this
    }

    /**
      * Checks whether the extractor has been called to add the specified data.
      *
      * @param data     the expected chunk of data
      * @param complete the completion flag
      * @return this test helper
      */
    def verifyExtractorFilled(data: ByteString, complete: Boolean): ID3FrameProcessorTestHelper = {
      verify(extractor).addData(data, complete)
      this
    }

    /**
      * Verifies that no interaction took place with the extractor.
      *
      * @return this test helper
      */
    def verifyExtractorNotTouched(): ID3FrameProcessorTestHelper = {
      verify(extractor, never()).addData(any(classOf[ByteString]), anyBoolean())
      verify(extractor, never()).createTagProvider()
      this
    }

    /**
      * Prepares the mock extractor to return the specified meta data provider.
      *
      * @param provider the provider
      * @return this test helper
      */
    def prepareProviderCreation(provider: MetaDataProvider): ID3FrameProcessorTestHelper = {
      when(extractor.createTagProvider()).thenReturn(Some(provider))
      this
    }

    /**
      * Expects that a meta data message has been passed to the collector
      * actor.
      *
      * @param provider the expected meta data provider
      * @return this test helper
      */
    def expectMetaDataMessage(provider: MetaDataProvider): ID3FrameProcessorTestHelper = {
      collector.expectMsg(ID3FrameMetaData(Header, Some(provider)))
      this
    }

    /**
      * Checks that no message with meta data has been sent to the collector
      * actor.
      *
      * @return this test helper
      */
    def expectNoMetaDataMessage(): ID3FrameProcessorTestHelper = {
      val msg = new Object
      collector.ref ! msg
      collector.expectMsg(msg)
      this
    }

    /**
      * Creates a mock for an ID3 frame extractor.
      *
      * @return the extractor mock
      */
    private def createExtractor(): ID3FrameExtractor = {
      val extr = mock[ID3FrameExtractor]
      when(extr.header).thenReturn(Header)
      extr
    }

    /**
      * Creates a test reference to the test actor.
      *
      * @return the test reference
      */
    private def createTestActor(): TestActorRef[ID3FrameProcessorActor] =
      TestActorRef[ID3FrameProcessorActor](Props(classOf[ID3FrameProcessorActor],
        collector.ref, extractor))
  }

}
