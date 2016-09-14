/*
 * Copyright 2015-2016 The Developers Team.
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
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.mp3.{ID3FrameExtractor, ID3Header, ID3TagProvider}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object ID3FrameProcessorActorSpec {
  /** Constant for a test file path. */
  private val TestPath = Paths.get("test.mp3")

  /** Constant for a test ID3 frame header. */
  private val Header = ID3Header(2, 64)
}

/**
 * Test class for ''ID3FrameProcessorActor''.
 */
class ID3FrameProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import ID3FrameProcessorActorSpec._

  def this() = this(ActorSystem("ID3FrameProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Creates a test reference to the test actor.
   * @param context the context to be passed to the actor
   * @return the test reference
   */
  private def createTestActor(context: MetaDataExtractionContext):
  TestActorRef[ID3FrameProcessorActor] = {
    TestActorRef[ID3FrameProcessorActor](Props(classOf[ID3FrameProcessorActor], context))
  }

  "An ID3FrameProcessorActor" should "handle the first processing message" in {
    val context = mock[MetaDataExtractionContext]
    val extractor = mock[ID3FrameExtractor]
    when(context.createID3FrameExtractor(Header)).thenReturn(extractor)
    val data = FileTestHelper.testBytes()
    val actor = createTestActor(context)

    actor receive ProcessID3FrameData(TestPath, Header, data, false)
    verify(extractor).addData(data, false)
  }

  it should "handle the last processing message" in {
    val context = mock[MetaDataExtractionContext]
    val extractor = mock[ID3FrameExtractor]
    val provider = mock[ID3TagProvider]
    when(context.createID3FrameExtractor(Header)).thenReturn(extractor)
    when(context.collectorActor).thenReturn(testActor)
    when(extractor.createTagProvider()).thenReturn(Some(provider))
    val data = FileTestHelper.testBytes()
    val chunk1 = data take 128
    val chunk2 = data takeRight 128
    val actor = createTestActor(context)

    actor receive ProcessID3FrameData(TestPath, Header, chunk1, lastChunk = false)
    actor receive ProcessID3FrameData(TestPath, Header, chunk2, lastChunk = true)
    val verifyOrder = Mockito inOrder extractor
    verifyOrder.verify(extractor).addData(chunk1, false)
    verifyOrder.verify(extractor).addData(chunk2, true)
    verifyOrder.verify(extractor).createTagProvider()
    expectMsg(ID3FrameMetaData(TestPath, Header, Some(provider)))
  }
}
