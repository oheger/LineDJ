/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamTestHelper.{AudioChunkSize, aggregateSink}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{Future, Promise}

/**
  * Test class for [[MetadataExtractionStage]].
  */
class MetadataExtractionStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper:
  def this() = this(ActorSystem("MetadataExtractionStageSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  /**
    * Constructs a stream with the given source and an extraction stage. The
    * stream is started, and the [[Future]]s for the results of the sinks are
    * returned. This function can be used to have more control over the
    * components integrated into the stream.
    *
    * @param optChunkSize the optional size of audio chunks
    * @param source       the source of the stream
    * @param metaSink     a sink for processing metadata
    * @return a tuple with the futures for audio and metadata
    */
  private def setupStream(optChunkSize: Option[Int],
                          source: Source[ByteString, NotUsed],
                          metaSink: Sink[ByteString, Future[ByteString]]): (Future[ByteString], Future[ByteString]) =
    val graph = RunnableGraph.fromGraph(GraphDSL.createGraph(aggregateSink(), metaSink)((_, _)) {
      implicit builder =>
        (sinkAudio, sinkMeta) =>
          import GraphDSL.Implicits._

          val extractionStage = builder.add(MetadataExtractionStage(optChunkSize))

          source ~> extractionStage.in
          extractionStage.out0 ~> sinkAudio
          extractionStage.out1 ~> sinkMeta
          ClosedShape
    })
    graph.run()

  /**
    * Runs a stream with the given source and an extraction stage. Returns the
    * aggregated data from the two output channels of the extraction stage.
    *
    * @param optChunkSize the optional size of audio chunks
    * @param source       the source of the stream
    * @param metaSink     a sink for processing metadata
    * @return a tuple with the audio and metadata
    */
  private def runStream(optChunkSize: Option[Int],
                        source: Source[ByteString, NotUsed],
                        metaSink: Sink[ByteString, Future[ByteString]] = aggregateSink()): (ByteString, ByteString) =
    val (futAudio, futMeta) = setupStream(optChunkSize, source, metaSink)
    (futureResult(futAudio), futureResult(futMeta))

  /**
    * Tests whether a stream with metadata can be processed by
    * [[MetadataExtractionStage]] based on the chunk size in which audio data
    * is delivered.
    *
    * @param streamChunkSize the chunk size of the stream
    */
  private def checkStreamWithMetadata(streamChunkSize: Int): Unit =
    val ChunkCount = 16
    val expectedAudioData = ByteString(RadioStreamTestHelper.refData(ChunkCount * AudioChunkSize))
    val expectedMetadata = (1 to ChunkCount).map(RadioStreamTestHelper.generateMetadata)
      .foldLeft(ByteString.empty) { (aggregate, chunk) => aggregate ++ ByteString(chunk) }

    val (extractedAudioData, extractedMetadata) =
      runStream(Some(AudioChunkSize), RadioStreamTestHelper.generateRadioStreamSource(ChunkCount, streamChunkSize))

    extractedAudioData should be(expectedAudioData)
    extractedMetadata should be(expectedMetadata)

  "MetadataExtractionStage" should "handle a radio stream that supports metadata" in:
    checkStreamWithMetadata(100)

  it should "handle a chunk containing only metadata" in:
    checkStreamWithMetadata(10)

  it should "filter out duplicate metadata" in:
    val ChunkCount = 16
    val expectedMetadata = (0 to ChunkCount / 2).map(RadioStreamTestHelper.generateMetadata)
      .foldLeft(ByteString.empty) { (aggregate, chunk) => aggregate ++ ByteString(chunk) }

    val (_, extractedMetadata) =
      runStream(Some(AudioChunkSize), RadioStreamTestHelper.generateRadioStreamSource(ChunkCount, 64,
        metaGen = idx => RadioStreamTestHelper.generateMetadata(idx / 2)))

    extractedMetadata should be(expectedMetadata)

  it should "handle a radio stream that does not support metadata" in:
    val ChunkCount = 10
    val audioData = ByteString(RadioStreamTestHelper.refData(ChunkCount * AudioChunkSize))
    val source = Source(audioData.grouped(333).toList)

    val (extractedAudioData, extractedMetadata) = runStream(None, source)

    extractedAudioData should be(audioData)
    extractedMetadata shouldBe empty

  it should "correctly pass metadata downstream" in:
    val ChunkCount = 16
    val expectedAudioData = ByteString(RadioStreamTestHelper.refData(ChunkCount * AudioChunkSize))
    val expectedMetadata = (1 to ChunkCount).map(RadioStreamTestHelper.generateMetadata)
      .foldLeft(ByteString.empty) { (aggregate, chunk) => aggregate ++ ByteString(chunk) }
    val promiseMetaSink = Promise[Sink[ByteString, Future[ByteString]]]()
    val metaSink = Sink.futureSink(promiseMetaSink.future).mapMaterializedValue(_.flatten)

    val (futExtractedAudioData, futExtractedMetadata) = setupStream(
      Some(AudioChunkSize),
      RadioStreamTestHelper.generateRadioStreamSource(ChunkCount),
      metaSink
    )
    promiseMetaSink.success(aggregateSink())

    futureResult(futExtractedAudioData) should be(expectedAudioData)
    futureResult(futExtractedMetadata) should be(expectedMetadata)
