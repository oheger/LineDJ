/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.stream

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.player.engine.DefaultAudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.BufferedPlaylistSource.{BufferFileWritten, BufferedSource}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.util.Random

object BufferedPlaylistSourceSpec:
  /**
    * Creates a [[Sink]] that collects all received elements in a list (in
    * reversed order).
    *
    * @tparam A the element type of the stream
    * @return the sink that collects stream elements
    */
  private def createFoldSink[A](): Sink[A, Future[List[A]]] =
    Sink.fold[List[A], A](List.empty)((list, data) => data :: list)

  /**
    * Returns a [[Source]] that emits the given data in chunks of the specified
    * size.
    *
    * @param data      the data of the source
    * @param chunkSize the chunk size
    * @return the corresponding source
    */
  private def chunkedSource(data: ByteString, chunkSize: Int): Source[ByteString, NotUsed] =
    Source(data.grouped(chunkSize).toList)
end BufferedPlaylistSourceSpec

/**
  * Test class for [[BufferedPlaylistSource]].
  */
class BufferedPlaylistSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper:
  def this() = this(ActorSystem("BufferedPlaylistSourceSpec"))

  /** The test kit for dealing with typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import BufferedPlaylistSourceSpec.*

  /**
    * Creates an [[AudioStreamPlayerStage.AudioStreamPlayerConfig]] for the 
    * buffered source to be tested.
    *
    * @param sourceResolverFunc the function to resolve sources
    * @tparam SRC the type of the audio sources
    * @return the configuration to be used by tests
    */
  private def createStreamPlayerConfig[SRC](sourceResolverFunc: AudioStreamPlayerStage.SourceResolverFunc[SRC]):
  AudioStreamPlayerStage.AudioStreamPlayerConfig[SRC, Any] =
    AudioStreamPlayerStage.AudioStreamPlayerConfig(sourceResolverFunc = sourceResolverFunc,
      sinkProviderFunc = _ => Sink.ignore,
      audioStreamFactory = DefaultAudioStreamFactory,
      pauseActor = testKit.createTestProbe().ref
    )

  /**
    * Checks whether a file in the buffer contains the expected data.
    *
    * @param bufferDir       the buffer directory
    * @param fileIndex       the index of the desired buffer file
    * @param expectedContent the expected content in this file
    */
  private def checkBufferFile(bufferDir: Path, fileIndex: Int, expectedContent: ByteString): Unit =
    val bufferFile = bufferDir.resolve(s"buffer0$fileIndex.dat")
    ByteString(Files.readAllBytes(bufferFile)) should be(expectedContent)

  "FillBufferFlowStage" should "load all sources into the buffer" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240706212512L)
    val sourceIndices = 1 to 4
    val sourceData = sourceIndices map { idx => ByteString(random.nextBytes(idx * 1024)) }

    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = idx =>
      Future.successful(AudioStreamPlayerStage.AudioStreamSource(s"source$idx.wav",
        chunkedSource(sourceData(idx - 1), 256)))

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 65536)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source(sourceIndices)
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { fillResults =>
      val expectedData = sourceData.reduce(_ ++ _)
      checkBufferFile(bufferDir, 1, expectedData)

      val expectedBufferedSources = sourceData.zipWithIndex.map { (data, index) =>
        BufferedSource(index + 1, data.size)
      }
      fillResults should contain only BufferFileWritten(expectedBufferedSources.toList)
    }

  it should "handle errors when processing a source" in :
    val bufferDir = createPathInDirectory("buffer")
    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = {
      case 1 => Future.failed(new IOException("Test exception: Could not read audio source."))
      case _ => Future.successful(AudioStreamPlayerStage.AudioStreamSource("foo.src",
        chunkedSource(ByteString(FileTestHelper.testBytes()), 32)))
    }

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 65536)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source(List(1, 2))
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { fillResults =>
      checkBufferFile(bufferDir, 1, ByteString(FileTestHelper.testBytes()))

      val expectedBufferedSources = List(
        BufferedSource(1, 0),
        BufferedSource(2, FileTestHelper.testBytes().length)
      )
      fillResults should contain only BufferFileWritten(expectedBufferedSources)
    }

  it should "handle an error from a source after data has been processed" in :
    val random = new Random(20240714191043L)
    val sourceData1 = ByteString(random.nextBytes(1024))
    val sourceData2 = ByteString(random.nextBytes(1024))
    val chunkCount = new AtomicInteger
    val SuccessfulChunks = 2
    val ChunkSize = 32
    val errorSource = chunkedSource(sourceData1, ChunkSize)
      .map { data =>
        // Throw an exception after processing some chunks.
        if chunkCount.incrementAndGet() > SuccessfulChunks then
          throw new IllegalStateException("Test exception: Source produced a failure.")
        data
      }
    val successSource = chunkedSource(sourceData2, ChunkSize)
    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = { index =>
      val sourceForIndex = index match
        case 1 => errorSource
        case 2 => successSource
        case i => fail("Unexpected source index: " + i)
      Future.successful(AudioStreamPlayerStage.AudioStreamSource(s"source$index.mp3", sourceForIndex))
    }

    val bufferDir = createPathInDirectory("buffer")
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 65536)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source(List(1, 2))
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { fillResults =>
      val source1Size = SuccessfulChunks * ChunkSize
      val expectedData = sourceData1.take(source1Size) ++ sourceData2
      checkBufferFile(bufferDir, 1, expectedData)

      val expectedBufferedSources = List(
        BufferedSource(1, source1Size),
        BufferedSource(2, sourceData2.size)
      )
      fillResults should contain only BufferFileWritten(expectedBufferedSources)
    }

  it should "split sources over multiple buffer files when the limit is exceeded" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240714220511L)
    val sourceIndices = 1 to 5
    val sourceData = sourceIndices map { _ => ByteString(random.nextBytes(4096)) }

    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = idx =>
      Future.successful(AudioStreamPlayerStage.AudioStreamSource(s"source$idx.wav",
        chunkedSource(sourceData(idx - 1), 256)))

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 16384)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source(sourceIndices)
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { fillResults =>
      val expectedData1 = sourceData.take(4).reduce(_ ++ _)
      checkBufferFile(bufferDir, 1, expectedData1)
      checkBufferFile(bufferDir, 2, sourceData(4))

      // This is an interesting corner case which does not yield deterministic results.
      // Two events are occurring in parallel: Source 4 completes, and the buffer file is full.
      // The produced results depend on the order in which these events are processed. Both variants
      // are semantically equivalent, but they nevertheless differ.
      val expectedWrittenMessages1 = List(
        BufferFileWritten(
          List(
            BufferedSource(1, 4096),
            BufferedSource(2, 4096),
            BufferedSource(3, 4096),
            BufferedSource(4, -1)
          )
        ),
        BufferFileWritten(
          List(
            BufferedSource(4, 0),
            BufferedSource(5, 4096)
          )
        )
      )
      val expectedWrittenMessages2 = List(
        BufferFileWritten(
          List(
            BufferedSource(1, 4096),
            BufferedSource(2, 4096),
            BufferedSource(3, 4096),
            BufferedSource(4, 4096),
            BufferedSource(5, -1)
          )
        ),
        BufferFileWritten(
          List(BufferedSource(5, 4096))
        )
      )
      val orderedFillResults = fillResults.reverse
      if orderedFillResults != expectedWrittenMessages1 && orderedFillResults != expectedWrittenMessages2 then
        fail(s"Unexpected result: $orderedFillResults\nExpected $expectedWrittenMessages1\n " +
          s"or $expectedWrittenMessages2")
      succeed
    }
