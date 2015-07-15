/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.splaya.metadata

import java.nio.file.Paths

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.splaya.io.ChannelHandler
import de.oliver_heger.splaya.mp3.{ID3TagProvider, ID3v1Extractor}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object ID3v1FrameProcessorActorSpec {
  /** The test path . */
  private val TestPath = Paths get "ID3v1FrameProcessorActor.mp3"
}

/**
 * Test class for ''ID3v1FrameProcessorActor''.
 */
class ID3v1FrameProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import ID3v1FrameProcessorActorSpec._

  def this() = this(ActorSystem("ID3v1FrameProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "An ID3v1FrameProcessorActor" should "pass a chunk of data to the extractor" in {
    val helper = new ID3v1FrameProcessorTestHelper
    val source = mock[ChannelHandler.ArraySource]

    helper sendDirect ProcessMp3Data(TestPath, source)
    verify(helper.extractor).addData(source)
  }

  it should "send results to the collector actor" in {
    val helper = new ID3v1FrameProcessorTestHelper
    val provider = mock[ID3TagProvider]
    when(helper.extractor.createTagProvider()).thenReturn(Some(provider))

    helper sendDirect MediaFileRead(TestPath)
    helper.probeCollector.expectMsg(ID3v1MetaData(TestPath, Some(provider)))
  }

  /**
   * A helper class collecting mocks or test probes for the dependencies of the
   * test actor.
   */
  private class ID3v1FrameProcessorTestHelper {
    /** Test probe for the meta data collector actor. */
    val probeCollector = TestProbe()

    /** A mock for the ID3v1 extractor. */
    val extractor = mock[ID3v1Extractor]

    /** The mock for the extractor context. */
    val extractionContext = createExtractionContext()

    /** The test actor. */
    val testActor = createTestActor()

    /**
     * Directly passes the specified message to the receive() method of the
     * test actor.
     * @param msg the message
     */
    def sendDirect(msg: Any): Unit = {
      testActor receive msg
    }

    /**
     * Creates a mock for the extraction context.
     * @return the mock context
     */
    private def createExtractionContext(): MetaDataExtractionContext = {
      val context = mock[MetaDataExtractionContext]
      when(context.collectorActor).thenReturn(probeCollector.ref)
      when(context.createID3v1Extractor()).thenReturn(extractor)
      context
    }

    /**
     * Creates the test actor.
     * @return the reference to the test actor
     */
    private def createTestActor(): TestActorRef[ID3v1FrameProcessorActor] = {
      TestActorRef[ID3v1FrameProcessorActor](Props(classOf[ID3v1FrameProcessorActor],
        extractionContext))
    }
  }

}
