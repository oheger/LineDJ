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

package de.oliver_heger.linedj.player.engine.radio.stream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ClosedShape
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.AsyncTestHelper
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

object MetadataExtractionStageSpec {
  /** The chunk size of audio blocks. */
  private val AudioChunkSize = 256

  /**
    * Generates a metadata string based on the given index. The resulting
    * string has a length that is a multitude of 16.
    *
    * @param index the index
    * @return the metadata string with this index
    */
  private def generateMetadata(index: Int): String = {
    val content = "Metadata block " + index
    content + " " * ((16 - content.length % 16) % 16)
  }

  /**
    * Returns a sink that aggregates all input into a single ''ByteString''.
    *
    * @return the aggregating sink
    */
  private def aggregateSink(): Sink[ByteString, Future[ByteString]] = Sink.fold(ByteString.empty)(_ ++ _)
}

/**
  * Test class for [[MetadataExtractionStage]].
  */
class MetadataExtractionStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("MetadataExtractionStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import MetadataExtractionStageSpec._

  /**
    * Runs a stream with the given source and an extraction stage. Returns the
    * aggregated data from the two output channels of the extraction stage.
    *
    * @param source       the source of the stream
    * @param optChunkSize the optional size of audio chunks
    * @return a tuple with the audio and metadata
    */
  private def runStream(optChunkSize: Option[Int], source: Source[ByteString, NotUsed]): (ByteString, ByteString) = {
    val graph = RunnableGraph.fromGraph(GraphDSL.createGraph(aggregateSink(), aggregateSink())((_, _)) {
      implicit builder =>
        (sinkAudio, sinkMeta) =>
          import GraphDSL.Implicits._

          val extractionStage = builder.add(MetadataExtractionStage(optChunkSize))

          source ~> extractionStage.in
          extractionStage.out0 ~> sinkAudio
          extractionStage.out1 ~> sinkMeta
          ClosedShape
    })

    val (futAudio, futMeta) = graph.run()
    (futureResult(futAudio), futureResult(futMeta))
  }

  /**
    * Tests whether a stream with metadata can be processed by
    * [[MetadataExtractionStage]] based on the chunk size in which audio data
    * is delivered.
    *
    * @param streamChunkSize the chunk size of the stream
    */
  private def checkStreamWithMetadata(streamChunkSize: Int): Unit = {
    val ChunkCount = 16
    val audioData = (0 until ChunkCount).map(RadioStreamTestHelper.dataBlock(AudioChunkSize, _))
    val metadata = (1 to ChunkCount).map { idx =>
      RadioStreamTestHelper.metadataBlock(ByteString(generateMetadata(idx)))
    }
    val dataChunks = audioData.zip(metadata)
      .foldLeft(ByteString.empty) { (d, t) => d ++ t._1 ++ t._2 }
      .grouped(streamChunkSize)
    val expectedAudioData = ByteString(RadioStreamTestHelper.refData(ChunkCount * AudioChunkSize))
    val expectedMetadata = (1 to ChunkCount).map(generateMetadata)
      .foldLeft(ByteString.empty) { (aggregate, chunk) => aggregate ++ ByteString(chunk) }

    val (extractedAudioData, extractedMetadata) = runStream(Some(AudioChunkSize), Source(dataChunks.toList))

    extractedAudioData should be(expectedAudioData)
    extractedMetadata should be(expectedMetadata)
  }

  "MetadataExtractionStage" should "handle a radio stream that supports metadata" in {
    checkStreamWithMetadata(100)
  }

  it should "handle a chunks containing only metadata" in {
    checkStreamWithMetadata(10)
  }

  it should "handle a radio stream that does not support metadata" in {
    val ChunkCount = 10
    val audioData = ByteString(RadioStreamTestHelper.refData(ChunkCount * AudioChunkSize))
    val source = Source(audioData.grouped(333).toList)

    val (extractedAudioData, extractedMetadata) = runStream(None, source)

    extractedAudioData should be(audioData)
    extractedMetadata shouldBe empty
  }
}
