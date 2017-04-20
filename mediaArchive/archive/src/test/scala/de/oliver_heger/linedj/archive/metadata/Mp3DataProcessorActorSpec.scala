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

import java.nio.file.Paths

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.archive.mp3.Mp3DataExtractor
import de.oliver_heger.linedj.io.ChannelHandler.ArraySource
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object Mp3DataProcessorActorSpec {
  /** Constant for a test path. */
  private val TestPath = Paths get "Mp3DataProcessorActorSpec.mp3"
}

/**
 * Test class for ''Mp3DataProcessorActor''.
 */
class Mp3DataProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import Mp3DataProcessorActorSpec._

  def this() = this(ActorSystem("Mp3DataProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An Mp3DataProcessorActor" should "pass a chunk of data to the extractor" in {
    val source = mock[ArraySource]
    val helper = new Mp3DataProcessorTestHelper

    helper sendDirect ProcessMp3Data(TestPath, source)
    verify(helper.extractor).addData(source)
  }

  it should "pass results to the collector when the file has been fully read" in {
    val helper = new Mp3DataProcessorTestHelper
    val metaData = Mp3MetaData(path = TestPath, version = 1, layer = 3, sampleRate = 111,
      minimumBitRat = 96000, maximumBitRate = 128000, duration = 60000)
    when(helper.extractor.getVersion).thenReturn(metaData.version)
    when(helper.extractor.getLayer).thenReturn(metaData.layer)
    when(helper.extractor.getSampleRate).thenReturn(metaData.sampleRate)
    when(helper.extractor.getMinBitRate).thenReturn(metaData.minimumBitRat)
    when(helper.extractor.getMaxBitRate).thenReturn(metaData.maximumBitRate)
    when(helper.extractor.getDuration).thenReturn(metaData.duration)

    helper sendDirect MediaFileRead(TestPath)
    helper.probeCollector.expectMsg(metaData)
  }

  /**
   * A helper class managing some objects needed by test cases.
   */
  private class Mp3DataProcessorTestHelper {
    /** A probe for the central collector actor. */
    val probeCollector = TestProbe()

    /** A mock for the data extractor. */
    val extractor = mock[Mp3DataExtractor]

    /** A mock for the extraction context. */
    val extractionContext = createExtractionContext()

    /** The test actor reference. */
    val actor = createTestActor()

    /**
     * Convenience method for sending a message directly to the test actor.
     * The receive method is directly invoked.
     * @param msg the message to be sent
     */
    def sendDirect(msg: Any): Unit = {
      actor receive msg
    }

    /**
     * Creates the mock for the extraction context. The mock is prepared to
     * return the test objects relevant for this test.
     * @return the mock extraction context
     */
    private def createExtractionContext(): MetaDataExtractionContext = {
      val ctx = mock[MetaDataExtractionContext]
      when(ctx.collectorActor).thenReturn(probeCollector.ref)
      when(ctx.createMp3DataExtractor()).thenReturn(extractor)
      ctx
    }

    private def createTestActor(): TestActorRef[Mp3DataProcessorActor] =
      TestActorRef[Mp3DataProcessorActor](Props(classOf[Mp3DataProcessorActor], extractionContext))
  }

}
