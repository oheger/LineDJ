/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.extract.id3.model._
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.apache.pekko.util.ByteString
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.util.Random

object Mp3DataProcessorActorSpec {
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
  * Test class for ''Mp3DataProcessorActor''.
  */
class Mp3DataProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import Mp3DataProcessorActorSpec._

  def this() = this(ActorSystem("Mp3DataProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An Mp3DataProcessorActor" should "create a default extractor" in {
    val ref = TestActorRef[Mp3DataProcessorActor](Props[Mp3DataProcessorActor]())

    ref.underlyingActor.extractor should not be null
    ref.underlyingActor.extractor.getFrameCount should be(0)
  }

  it should "pass a chunk of data to the extractor" in {
    val data = generateChunk()
    val helper = new Mp3DataProcessorTestHelper

    helper.postMessage(ProcessMp3Data(data))
    expectMsg(Mp3DataProcessed)
    helper.verifyExtractorFilled(data)
  }

  it should "pass results to the collector when the file has been fully read" in {
    val metaData = Mp3MetaData(version = 1, layer = 3, sampleRate = 111,
      minimumBitRat = 96000, maximumBitRate = 128000, duration = 60000)
    val helper = new Mp3DataProcessorTestHelper

    helper.prepareMetaDataQuery(metaData)
      .postMessage(Mp3MetaDataRequest)
    expectMsg(metaData)
  }

  /**
    * A helper class managing some objects needed by test cases.
    */
  private class Mp3DataProcessorTestHelper {
    /** A mock for the data extractor. */
    private val extractor: Mp3DataExtractor = mock[Mp3DataExtractor]

    /** The test actor reference. */
    private val actor: TestActorRef[Mp3DataProcessorActor] = createTestActor()

    /**
      * Sends the specified message to the test actor using the ''tell''
      * method.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def postMessage(msg: Any): Mp3DataProcessorTestHelper = {
      actor ! msg
      this
    }

    /**
      * Verifies that the specified chunk of data has been passed to the
      * extractor.
      *
      * @param data the expected data
      * @return this test helper
      */
    def verifyExtractorFilled(data: ByteString): Mp3DataProcessorTestHelper = {
      verify(extractor).addData(data)
      this
    }

    /**
      * Prepares the mock extractor to return the data specified by the given
      * meta data object.
      *
      * @param metaData the meta data
      * @return this test helper
      */
    def prepareMetaDataQuery(metaData: Mp3MetaData): Mp3DataProcessorTestHelper = {
      when(extractor.getVersion).thenReturn(metaData.version)
      when(extractor.getLayer).thenReturn(metaData.layer)
      when(extractor.getSampleRate).thenReturn(metaData.sampleRate)
      when(extractor.getMinBitRate).thenReturn(metaData.minimumBitRat)
      when(extractor.getMaxBitRate).thenReturn(metaData.maximumBitRate)
      when(extractor.getDuration).thenReturn(metaData.duration.toFloat)
      this
    }

    /**
      * Creates the test actor reference.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[Mp3DataProcessorActor] =
      TestActorRef[Mp3DataProcessorActor](Props(classOf[Mp3DataProcessorActor], extractor))
  }

}
