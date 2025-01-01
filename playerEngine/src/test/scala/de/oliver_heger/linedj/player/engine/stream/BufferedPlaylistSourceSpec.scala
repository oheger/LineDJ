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
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed, actor as classic}
import org.scalatest.Inspectors.{forAll, forEvery}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, BeforeAndAfterEach, Succeeded}

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.collection.immutable.IndexedSeq
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Random

object BufferedPlaylistSourceSpec:
  /** The default chunk size for chunked sources. */
  private val DefaultChunkSize = 256

  /**
    * Type alias for a function that allows manipulating a stream source
    * obtained from a [[BufferedPlaylistSource]] when running a test case. This
    * is used to simulate behavior like skipping parts of sources or errors.
    */
  private type StreamSourceMapper =
    PartialFunction[AudioStreamPlayerStage.AudioStreamSource, AudioStreamPlayerStage.AudioStreamSource]

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

  /**
    * Generates the URL for a test source based on its index.
    *
    * @param index the index of this test source
    * @return the URL for this test source
    */
  private def sourceUrl(index: Int): String = s"source$index.wav"

  /**
    * Returns a function to resolve audio sources that is based on a sequence
    * with the data of the sources. The source type is ''Int'', so the data of
    * a source can easily be determined as index in the data sequence.
    *
    * @param sourceData the sequence with the data of the sources
    * @return the function to resolve sources
    */
  private def seqBasedResolverFunc(sourceData: IndexedSeq[ByteString]):
  AudioStreamPlayerStage.SourceResolverFunc[Int] = idx =>
    Future.successful(AudioStreamPlayerStage.AudioStreamSource(sourceUrl(idx),
      chunkedSource(sourceData(idx - 1), DefaultChunkSize)))
end BufferedPlaylistSourceSpec

/**
  * Test class for [[BufferedPlaylistSource]].
  */
class BufferedPlaylistSourceSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with Eventually with FileTestHelper:
  def this() = this(classic.ActorSystem("BufferedPlaylistSourceSpec"))

  /** The test kit for dealing with typed actors. */
  private val testKit = ActorTestKit()

  /** Provides a typed actor system in implicit scope. */
  given typedSystem: ActorSystem[_] = testKit.system

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
      optPauseActor = None
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
    Files.size(bufferFile) should be(expectedContent.size)
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

      val (_, expectedBufferedSources) = sourceData.zipWithIndex
        .foldLeft((0L, List.empty[BufferedSource[Int]])) { (agg, e) =>
          val nextOffset = agg._1 + e._1.size
          val bufSrc = BufferedSource(e._2 + 1, sourceUrl(e._2 + 1), agg._1, nextOffset)
          (nextOffset, bufSrc :: agg._2)
        }
      fillResults should contain only BufferFileWritten(expectedBufferedSources.reverse)
    }

  it should "skip a source if it cannot be resolved" in :
    val bufferDir = createPathInDirectory("buffer")
    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = {
      case 1 => Future.failed(new IOException("Test exception: Could not read audio source."))
      case idx => Future.successful(AudioStreamPlayerStage.AudioStreamSource(sourceUrl(idx),
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
        BufferedSource(2, sourceUrl(2), 0, FileTestHelper.testBytes().length)
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
      Future.successful(AudioStreamPlayerStage.AudioStreamSource(sourceUrl(index), sourceForIndex))
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
        BufferedSource(1, sourceUrl(1), 0, source1Size),
        BufferedSource(2, sourceUrl(2), source1Size, source1Size + sourceData2.size)
      )
      fillResults should contain only BufferFileWritten(expectedBufferedSources)
    }

  it should "split sources over multiple buffer files when the limit is exceeded" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240714220511L)
    val sourceIndices = 1 to 5
    val sourceData = sourceIndices map { _ => ByteString(random.nextBytes(4096)) }
    val resolverFunc = seqBasedResolverFunc(sourceData)

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

      val expectedWrittenMessages = List(
        BufferFileWritten(
          List(
            BufferedSource(1, sourceUrl(1), 0, 4096),
            BufferedSource(2, sourceUrl(2), 4096, 8192),
            BufferedSource(3, sourceUrl(3), 8192, 12288),
            BufferedSource(4, sourceUrl(4), 12288, -1)
          )
        ),
        BufferFileWritten(
          List(
            BufferedSource(4, sourceUrl(4), 12288, 16384),
            BufferedSource(5, sourceUrl(5), 16384, 20480)
          )
        )
      )
      val orderedFillResults = fillResults.reverse
      orderedFillResults should contain theSameElementsInOrderAs expectedWrittenMessages
    }

  it should "handle arbitrary buffer sizes correctly when splitting sources over multiple buffer files" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240719113329L)
    val sourceIndices = 1 to 5
    val sourceData = sourceIndices map { _ => ByteString(random.nextBytes(4096)) }
    val resolverFunc = seqBasedResolverFunc(sourceData)

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 16000)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source(sourceIndices)
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { fillResults =>
      val (split1, split2) = sourceData(3).splitAt(16000 - 3 * 4096)
      val expectedData1 = sourceData.take(3).reduce(_ ++ _) ++ split1
      val expectedData2 = split2 ++ sourceData(4)
      checkBufferFile(bufferDir, 1, expectedData1)
      checkBufferFile(bufferDir, 2, expectedData2)

      val expectedWrittenMessages = List(
        BufferFileWritten(
          List(
            BufferedSource(1, sourceUrl(1), 0, 4096),
            BufferedSource(2, sourceUrl(2), 4096, 8192),
            BufferedSource(3, sourceUrl(3), 8192, 12288),
            BufferedSource(4, sourceUrl(4), 12288, -1)
          )
        ),
        BufferFileWritten(
          List(
            BufferedSource(4, sourceUrl(4), 12288, 16384),
            BufferedSource(5, sourceUrl(5), 16384, 20480)
          )
        )
      )
      fillResults.reverse should contain theSameElementsInOrderAs expectedWrittenMessages
    }

  it should "handle sources correctly that spawn multiple buffer files" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240731213147L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(1024)),
      ByteString(random.nextBytes(8192)),
      ByteString(random.nextBytes(1000))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 2048)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source(List(1, 2, 3))
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { fillResults =>
      checkBufferFile(bufferDir, 1, sourceData.head ++ sourceData(1).take(1024))
      checkBufferFile(bufferDir, 2, sourceData(1).drop(1024).take(2048))
      checkBufferFile(bufferDir, 3, sourceData(1).drop(3072).take(2048))
      checkBufferFile(bufferDir, 4, sourceData(1).drop(5120).take(2048))
      checkBufferFile(bufferDir, 5, sourceData(1).drop(7168) ++ sourceData(2))

      val expectedWrittenMessages = List(
        BufferFileWritten(
          List(
            BufferedSource(1, sourceUrl(1), 0, 1024),
            BufferedSource(2, sourceUrl(2), 1024, -1)
          )
        ),
        BufferFileWritten(List(BufferedSource(2, sourceUrl(2), 1024, -1))),
        BufferFileWritten(List(BufferedSource(2, sourceUrl(2), 1024, -1))),
        BufferFileWritten(List(BufferedSource(2, sourceUrl(2), 1024, -1))),
        BufferFileWritten(
          List(
            BufferedSource(2, sourceUrl(2), 1024, 9216),
            BufferedSource(3, sourceUrl(3), 9216, 10216)
          )
        ),
      )
      val orderedFillResults = fillResults.reverse
      orderedFillResults should contain theSameElementsInOrderAs expectedWrittenMessages
    }

  it should "cancel the stream on failures to write to the buffer" in :
    val exception = new IllegalStateException("Test exception: Could not write to buffer file.")
    val chunkCount = new AtomicInteger
    val errorSink = Sink.ignore.contramap[ByteString] { chunk =>
      if chunkCount.incrementAndGet() > 4 then {
        throw exception
      }
    }

    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240719212014L)
    val sourceData = IndexedSeq(ByteString(random.nextBytes(4096)))
    val resolverFunc = seqBasedResolverFunc(sourceData)

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 8192,
      bufferSinkFunc = _ => errorSink)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source.single(1)
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    recoverToExceptionIf[BufferedPlaylistSource.BridgeSourceFailure] {
      source.via(stage).runWith(sink)
    } map { actualException =>
      actualException.getCause should be(exception)
    }

  it should "not create more than two active files in the buffer" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240719215004L)
    val sourceIndices = 1 to 6
    val sourceData = sourceIndices map { _ => ByteString(random.nextBytes(4096)) }
    val resolverFunc = seqBasedResolverFunc(sourceData)

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 8000)
    val resultQueue = new LinkedBlockingQueue[BufferFileWritten[Int]]
    val sink = Sink.foreach[BufferFileWritten[Int]](resultQueue.offer)
    val source = Source(sourceIndices)
    val pauseActor =
      testKit.spawn(PausePlaybackStage.pausePlaybackActor(PausePlaybackStage.PlaybackState.PlaybackPaused))
    val pauseStage = PausePlaybackStage.pausePlaybackStage[BufferFileWritten[Int]](Some(pauseActor))
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    val futStream = source.via(stage).via(pauseStage).runWith(sink)
    resultQueue.poll(500, TimeUnit.MILLISECONDS) should be(null)

    Files.isRegularFile(bufferDir.resolve("buffer01.dat")) shouldBe true
    Files.isRegularFile(bufferDir.resolve("buffer02.dat")) shouldBe true
    Files.exists(bufferDir.resolve("buffer03.dat")) shouldBe false

    pauseActor ! PausePlaybackStage.StartPlayback
    (1 to 4).foreach { _ =>
      resultQueue.poll(1000, TimeUnit.MILLISECONDS) should not be null
    }
    Files.size(bufferDir.resolve("buffer04.dat")) should be(6 * 4096 - 3 * 8000)
    resultQueue.poll(100, TimeUnit.MILLISECONDS) should be(null)

  it should "correctly fill the buffer with a fast consumer" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240719215004L)
    val sourceIndices = 1 to 6
    val sourceData = sourceIndices map { _ => ByteString(random.nextBytes(4096)) }
    val resolverFunc = seqBasedResolverFunc(sourceData)

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 8000)
    val sink = Sink.ignore
    val source = Source(sourceIndices)
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { _ =>
      val bufferFile3 = bufferDir.resolve("buffer04.dat")
      Files.size(bufferFile3) should be(6 * 4096 - 3 * 8000)
    }

  it should "take the source name into account" in :
    val bufferDir1 = createPathInDirectory("buffer")
    val bufferDir2 = createPathInDirectory("buffer2")
    val random = new Random(20240720220751L)
    val sourceIndices = 1 to 4
    val sourceData = sourceIndices map { _ => ByteString(random.nextBytes(4096)) }
    val resolverFunc = seqBasedResolverFunc(sourceData)

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig1 = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir1,
      bufferFileSize = 8000)
    val bufferConfig2 = bufferConfig1.copy(sourceName = "bufferSource2", bufferFolder = bufferDir2)
    val sink = createFoldSink[BufferFileWritten[Int]]()
    val source = Source(sourceIndices)
    val stage1 = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig1)
    val stage2 = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig2)

    val futStream1 = source.via(stage1).runWith(sink)
    val futStream2 = source.via(stage2).runWith(sink)
    for
      l1 <- futStream1
      l2 <- futStream2
    yield l1 should be(l2)

  "applySkipUntil" should "handle an undefined skip position" in :
    val chunks: List[BufferedPlaylistSource.DataChunkResponse.DataChunk] = List(
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("This is a chunk")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("This is another chunk"))
    )

    val modifiedChunks = BufferedPlaylistSource.applySkipUntil(chunks, 11, -1)

    modifiedChunks should be(chunks)

  it should "handle a skip position after the offset" in :
    val chunks: List[BufferedPlaylistSource.DataChunkResponse.DataChunk] = List(
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("This is a chunk")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("This is another chunk"))
    )

    val modifiedChunks = BufferedPlaylistSource.applySkipUntil(chunks, 100, 100)

    modifiedChunks shouldBe empty

  it should "handle a skip position before the beginning of the buffer" in :
    val chunks: List[BufferedPlaylistSource.DataChunkResponse.DataChunk] = List(
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("This is a chunk")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("This is another chunk"))
    )

    val modifiedChunks = BufferedPlaylistSource.applySkipUntil(chunks, 100, 10)

    modifiedChunks shouldBe chunks

  it should "correctly skip the chunks in the buffer" in :
    val chunks: List[BufferedPlaylistSource.DataChunkResponse.DataChunk] = List(
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("The length of this chunk is 31.")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("Chunk with a length of 26.")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("A final chunk with a length of 40 chars."))
    )
    val expectedResult: List[BufferedPlaylistSource.DataChunkResponse.DataChunk] = List(
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("h a length of 26.")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("A final chunk with a length of 40 chars."))
    )

    val modifiedChunks = BufferedPlaylistSource.applySkipUntil(chunks, 1000, 943)

    modifiedChunks should contain theSameElementsInOrderAs expectedResult

  it should "handle a skip position at the beginning of one chunk" in :
    val chunks: List[BufferedPlaylistSource.DataChunkResponse.DataChunk] = List(
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("The length of this chunk is 31.")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("Chunk with a length of 26.")),
      BufferedPlaylistSource.DataChunkResponse.DataChunk(ByteString("A final chunk with a length of 40 chars."))
    )

    val modifiedChunks = BufferedPlaylistSource.applySkipUntil(chunks, 1000, 934)

    modifiedChunks should contain theSameElementsInOrderAs chunks.tail

  it should "handle an empty buffer" in :
    val modifiedChunks1 = BufferedPlaylistSource.applySkipUntil(Nil, 1000, 1000)
    val modifiedChunks2 = BufferedPlaylistSource.applySkipUntil(Nil, 1001, 1000)
    val modifiedChunks3 = BufferedPlaylistSource.applySkipUntil(Nil, 1000, 1001)

    forEvery(List(modifiedChunks1, modifiedChunks2, modifiedChunks3)) {
      _ shouldBe empty
    }
    Succeeded

  "mapConfig" should "correctly map the source resolver function" in :
    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = src =>
      throw new UnsupportedOperationException("Unexpected call")
    val config = createStreamPlayerConfig(resolverFunc)

    val mappedConfig = BufferedPlaylistSource.mapConfig(config)

    val resolvedSource = AudioStreamPlayerStage.AudioStreamSource("someURL",
      Source.single(ByteString.fromArray(FileTestHelper.testBytes())))
    val sourceInBuffer = new BufferedPlaylistSource.SourceInBuffer[Int]:
      override def originalSource: Int = throw new UnsupportedOperationException("Unexpected call")

      override def resolveSource(): Future[AudioStreamPlayerStage.AudioStreamSource] =
        Future.successful(resolvedSource)

    mappedConfig.sourceResolverFunc(sourceInBuffer).map { result =>
      result should be(resolvedSource)
    }

  it should "correctly map the sink provider function" in :
    val TestSource = 42
    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = src =>
      throw new UnsupportedOperationException("Unexpected call to resolver func.")
    val orgSink = Sink.ignore
    val orgSinkFunc: AudioStreamPlayerStage.SinkProviderFunc[Int, Done] = src =>
      src should be(TestSource)
      orgSink
    val config = createStreamPlayerConfig(resolverFunc).copy(sinkProviderFunc = orgSinkFunc)

    val mappedConfig = BufferedPlaylistSource.mapConfig(config)

    val sourceInBuffer = new BufferedPlaylistSource.SourceInBuffer[Int]:
      override val originalSource: Int = TestSource

      override def resolveSource(): Future[AudioStreamPlayerStage.AudioStreamSource] =
        throw new UnsupportedOperationException("Unexpected call to resolveSource()")

    val providedSink = mappedConfig.sinkProviderFunc(sourceInBuffer)
    providedSink should be(orgSink)

  /**
    * Runs a stream with a buffered source over a source of playlist elements.
    * The materialized value of the source and the result from the stream - the
    * collected data of the playlist items - is returned.
    *
    * @param config             the configuration for the buffered source
    * @param playlistSource     the source with playlist items
    * @param skipAfter          the number of sources after which to skip the
    *                           stream
    * @param streamSourceMapper a function allowing to modify single sources;
    *                           this is useful for instance to force errors
    * @return a tuple with the materialized value of the source and a
    *         ''Future'' with the data of the sources that were read from the
    *         buffered source
    * @tparam MAT the type of the materialized value of the source
    */
  private def runBufferedStreamWithSource[MAT](config: BufferedPlaylistSource.BufferedPlaylistSourceConfig[Int, Any],
                                               playlistSource: Source[Int, MAT],
                                               skipAfter: Int = 100)
                                              (streamSourceMapper: StreamSourceMapper = PartialFunction.empty):
  (MAT, Future[List[ByteString]]) =
    val bufferedSource = BufferedPlaylistSource(config, playlistSource)
    val mappedConfig = BufferedPlaylistSource.mapConfig(config.streamPlayerConfig)
    val sink = createFoldSink[ByteString]()
    val sourceIndex = new AtomicInteger
    val optStreamSourceMapper = streamSourceMapper.lift
    val graph = bufferedSource.mapAsync(1) { elem =>
        mappedConfig.sourceResolverFunc(elem)
      }.mapAsync(1) { source =>
        source.url should be(sourceUrl(sourceIndex.incrementAndGet()))
        val byteStrSink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
        optStreamSourceMapper(source).getOrElse(source).source.runWith(byteStrSink)
      }.take(skipAfter)
      .toMat(sink)(Keep.both)
    val (mat, res) = graph.run()
    (mat, res.map(_.reverse))

  /**
    * Runs a stream with a buffered playlist source over a given number of test
    * sources as defined by the resolver function in the given configuration.
    * The result from the stream - the collected data of the original sources -
    * is returned.
    *
    * @param config             the configuration for the buffered source
    * @param sourceCount        the number of test sources
    * @param skipAfter          the number of sources after which to skip the
    *                           stream
    * @param streamSourceMapper a function allowing to modify single sources;
    *                           this is useful for instance to force errors
    * @return a ''Future'' with the data of the sources that were read from the
    *         buffered source
    */
  private def runBufferedStream(config: BufferedPlaylistSource.BufferedPlaylistSourceConfig[Int, Any],
                                sourceCount: Int,
                                skipAfter: Int = 100)
                               (streamSourceMapper: StreamSourceMapper = PartialFunction.empty):
  Future[List[ByteString]] =
    val playlistSource = Source((1 to sourceCount).toList)
    runBufferedStreamWithSource(config, playlistSource, skipAfter)(streamSourceMapper)._2

  /**
    * Runs a stream with a buffered playlist source over the given sources and
    * checks whether the expected data is received.
    *
    * @param config     the configuration for the buffered source
    * @param sourceData the data of the audio sources in the playlist
    * @return a ''Future'' with the result of the test
    */
  private def runBufferedStreamAndCheckResult(config: BufferedPlaylistSource.BufferedPlaylistSourceConfig[Int, Any],
                                              sourceData: IndexedSeq[ByteString]): Future[Assertion] =
    runBufferedStream(config, sourceData.size)() map { results =>
      results should contain theSameElementsInOrderAs sourceData
    }

  "BufferedPlaylistSource" should "process a single source from upstream" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240802221019L)
    val sourceData = IndexedSeq(ByteString(random.nextBytes(4096)))
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 8192)

    runBufferedStreamAndCheckResult(bufferConfig, sourceData)

  it should "route the data through buffer files" in :
    val bufferDir = createPathInDirectory("fileTestBuffer")
    val random = new Random(20240803155802L)
    val sourceData = ByteString(random.nextBytes(4096))
    val resolverFunc = seqBasedResolverFunc(IndexedSeq(sourceData))
    val filesQueue = new LinkedBlockingQueue[(Path, Long)]
    val sourceCheckFunc: BufferedPlaylistSource.BufferSourceFunc = { path =>
      filesQueue.offer((path, Files.size(path)))
      BufferedPlaylistSource.defaultBufferSource(path)
    }

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 8192,
      bufferSourceFunc = sourceCheckFunc)

    runBufferedStream(bufferConfig, 1)() map { _ =>
      val (path, size) = filesQueue.poll(3, TimeUnit.SECONDS)
      size should be(sourceData.size)
      path.toString should endWith("buffer01.dat")
    }

  it should "process multiple sources that fit in a single buffer file" in :
    val bufferDir = createPathInDirectory("singleBuffer")
    val random = new Random(2024080311347L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(1024)),
      ByteString(random.nextBytes(4096)),
      ByteString(random.nextBytes(2048))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 100000)

    runBufferedStreamAndCheckResult(bufferConfig, sourceData)

  it should "process sources that start in one buffer file and end in the next one" in :
    val bufferDir = createPathInDirectory("multiBuffer")
    val random = new Random(20240804215159L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(8192)),
      ByteString(random.nextBytes(20000)),
      ByteString(random.nextBytes(2048))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 16384)

    runBufferedStreamAndCheckResult(bufferConfig, sourceData)

  it should "process large sources spanning multiple buffer files" in :
    val bufferDir = createPathInDirectory("multiBufferLargeSource")
    val random = new Random(20240808211934L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(9000)),
      ByteString(random.nextBytes(40000)),
      ByteString(random.nextBytes(500))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 10000)

    runBufferedStreamAndCheckResult(bufferConfig, sourceData)

  it should "handle sources that end at the beginning of a new buffer file" in :
    val bufferDir = createPathInDirectory("multiBufferEndEarly")
    val random = new Random(20240808215942L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(8192)),
      ByteString(random.nextBytes(10000)),
      ByteString(random.nextBytes(2048))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 16384)

    runBufferedStreamAndCheckResult(bufferConfig, sourceData)

  it should "handle a source that fits exactly into a buffer file" in :
    val bufferDir = createPathInDirectory("bufferFit")
    val random = new Random(20240809214820L)
    val sourceIndices = 1 to 5
    val sourceData = sourceIndices map { _ => ByteString(random.nextBytes(4096)) }
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 16384)

    runBufferedStreamAndCheckResult(bufferConfig, sourceData)

  /**
    * Executes a test with 3 sources, where the source in the middle is
    * canceled before it was fully read. Some parameters for the test can be
    * specified.
    *
    * @param bufferSize the size of the buffer files
    * @param sourceSize the size of the source that is canceled
    * @return the future with the test assertion
    */
  private def testPartiallySkippedSource(bufferSize: Int, sourceSize: Int): Future[Assertion] =
    val bufferDir = createPathInDirectory("skipInFile")
    val random = new Random(20240814244111L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(10000)),
      ByteString(random.nextBytes(sourceSize)),
      ByteString(random.nextBytes(4000))
    )
    val streamPlayerConfig = createStreamPlayerConfig(seqBasedResolverFunc(sourceData))
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = bufferSize)

    val chunkSizeFromSource = new AtomicInteger
    val sourceSkipFn: Source[ByteString, Any] => Source[ByteString, Any] = source =>
      source.map { chunk =>
        chunkSizeFromSource.set(chunk.size)
        chunk
      }.take(1)

    runBufferedStream(bufferConfig, sourceData.size) {
      case AudioStreamPlayerStage.AudioStreamSource(url, source) if url == sourceUrl(2) =>
        AudioStreamPlayerStage.AudioStreamSource(url, sourceSkipFn(source))
    } map { results =>
      chunkSizeFromSource.get() should be > 0
      val expectedResults = IndexedSeq(
        sourceData.head,
        sourceData(1).take(chunkSizeFromSource.get()),
        sourceData(2)
      )
      results should contain theSameElementsInOrderAs expectedResults
    }

  it should "handle a partly skipped source in a single buffer file" in :
    testPartiallySkippedSource(bufferSize = 32768, sourceSize = 16384)

  it should "handle a partly skipped source that ends in the next buffer file" in :
    testPartiallySkippedSource(bufferSize = 16384, sourceSize = 16384)

  it should "handle a partly skipped source that spans multiple buffer files" in :
    testPartiallySkippedSource(bufferSize = 16384, sourceSize = 65536)

  /**
    * Executes a test with a source that is partially skipped at the end of
    * the playlist.
    *
    * @param bufferSize the size of the buffer files
    * @param sourceSize the size of the source that is canceled
    * @return the future with the test assertion
    */
  private def testPartiallySkippedLastSource(bufferSize: Int, sourceSize: Int): Future[Assertion] =
    val bufferDir = createPathInDirectory("skipAtEnd")
    val random = new Random(20240824183642L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(10000)),
      ByteString(random.nextBytes(sourceSize))
    )
    val streamPlayerConfig = createStreamPlayerConfig(seqBasedResolverFunc(sourceData))
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = bufferSize)

    val chunkSizeFromSource = new AtomicInteger
    val sourceSkipFn: Source[ByteString, Any] => Source[ByteString, Any] = source =>
      source.map { chunk =>
        chunkSizeFromSource.set(chunk.size)
        chunk
      }.take(1)

    runBufferedStream(bufferConfig, sourceData.size) {
      case AudioStreamPlayerStage.AudioStreamSource(url, source) if url == sourceUrl(2) =>
        AudioStreamPlayerStage.AudioStreamSource(url, sourceSkipFn(source))
    } map { results =>
      chunkSizeFromSource.get() should be > 0
      val expectedResults = IndexedSeq(
        sourceData.head,
        sourceData(1).take(chunkSizeFromSource.get())
      )
      results should contain theSameElementsInOrderAs expectedResults
    }

  it should "handle a partly skipped source at the end of the playlist in one buffer file" in :
    testPartiallySkippedLastSource(bufferSize = 65536, sourceSize = 32768)

  it should "handle a partly skipped source at the end of the playlist over multiple buffer files" in :
    testPartiallySkippedLastSource(bufferSize = 16384, sourceSize = 65536)

  it should "handle a partly skipped source before a source spanning multiple buffer files" in :
    val bufferDir = createPathInDirectory("partiallySkippedBeforeLargeSource")
    val random = new Random(20240902220229L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(8192)),
      ByteString(random.nextBytes(70000)),
      ByteString(random.nextBytes(15000))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 10000)
    val chunkSizeFromSource = new AtomicInteger
    val sourceSkipFn: Source[ByteString, Any] => Source[ByteString, Any] = source =>
      source.map { chunk =>
        chunkSizeFromSource.set(chunk.size)
        chunk
      }.take(1)

    runBufferedStream(bufferConfig, sourceData.size) {
      case AudioStreamPlayerStage.AudioStreamSource(url, source) if url == sourceUrl(2) =>
        AudioStreamPlayerStage.AudioStreamSource(url, sourceSkipFn(source))
    } map { results =>
      chunkSizeFromSource.get() should be > 0
      val expectedResults = IndexedSeq(
        sourceData.head,
        sourceData(1).take(chunkSizeFromSource.get()),
        sourceData(2)
      )
      results should contain theSameElementsInOrderAs expectedResults
    }

  it should "take the source name into account" in :
    val bufferDir1 = createPathInDirectory("buffer1")
    val bufferDir2 = createPathInDirectory("buffer2")
    val random = new Random(20240824185038L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(8192)),
      ByteString(random.nextBytes(32768)),
      ByteString(random.nextBytes(16384))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig1 = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir1,
      bufferFileSize = 16384)
    val bufferConfig2 = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir2,
      bufferFileSize = 32768,
      sourceName = "anotherTestSource")

    val fut1 = runBufferedStreamAndCheckResult(bufferConfig1, sourceData)
    val fut2 = runBufferedStreamAndCheckResult(bufferConfig2, sourceData)
    for
      _ <- fut1
      res <- fut2
    yield res

  it should "cancel the stream when reading from a buffer file fails" in :
    val exception = new IllegalStateException("Test exception: Could not read from buffer file.")
    val chunkCount = new AtomicInteger
    val errorSourceFunc: BufferedPlaylistSource.BufferSourceFunc = path =>
      BufferedPlaylistSource.defaultBufferSource(path).map { chunk =>
        if chunkCount.incrementAndGet() > 4 then throw exception
        chunk
      }

    val bufferDir = createPathInDirectory("errorBuffer")
    val random = new Random(20240824214508L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(30000)),
      ByteString(random.nextBytes(25000))
    )
    val streamPlayerConfig = createStreamPlayerConfig(seqBasedResolverFunc(sourceData))
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 60000,
      bufferSourceFunc = errorSourceFunc)

    recoverToExceptionIf[IllegalStateException] {
      runBufferedStream(bufferConfig, sourceData.size)()
    } map { actualException =>
      actualException should be(exception)
    }

  it should "delete buffer files after they have been processed" in :
    val bufferDir = createPathInDirectory("bufferCleanup")
    val random = new Random(20240825185816L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(32768)),
      ByteString(random.nextBytes(25321)),
      ByteString(random.nextBytes(65000)),
      ByteString(random.nextBytes(40000)),
      ByteString(random.nextBytes(54545))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 16384)

    runBufferedStreamAndCheckResult(bufferConfig, sourceData) map { _ =>
      bufferDir.toFile.list() shouldBe empty
    }

  it should "delete buffer files also if the playlist stream is canceled" in :
    val bufferDir = createPathInDirectory("bufferCleanupCanceled")
    val random = new Random(20240825221143L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(32768)),
      ByteString(random.nextBytes(25321)),
      ByteString(random.nextBytes(65000)),
      ByteString(random.nextBytes(40000)),
      ByteString(random.nextBytes(54545))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 16384)

    runBufferedStream(bufferConfig, sourceData.size, skipAfter = 4)() map { _ =>
      eventually:
        bufferDir.toFile.list() shouldBe empty
    }

  it should "delete skipped buffer files immediately" in :
    val bufferDir = createPathInDirectory("bufferDeleteSkippedFiles")
    val random = new Random(20240902204705L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(8192)),
      ByteString(random.nextBytes(70000)),
      ByteString(random.nextBytes(15000))
    )
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val deletedFiles = new LinkedBlockingQueue[String]
    val deleteFunc: BufferedPlaylistSource.BufferDeleteFunc = path =>
      deletedFiles.offer(path.toString)
      BufferedPlaylistSource.defaultBufferDelete(path)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 10000,
      bufferDeleteFunc = deleteFunc)
    val sourceSkipFn: Source[ByteString, Any] => Source[ByteString, Any] = source =>
      source.take(1)

    runBufferedStream(bufferConfig, sourceData.size) {
      case AudioStreamPlayerStage.AudioStreamSource(url, source) if url == sourceUrl(2) =>
        AudioStreamPlayerStage.AudioStreamSource(url, sourceSkipFn(source))
    } map { _ =>
      forAll((1 to 9).toList) { idx =>
        val expectedDeleteFile = s"buffer0$idx.dat"
        deletedFiles.poll(3, TimeUnit.SECONDS) should endWith(expectedDeleteFile)
      }
    }

  it should "support closing the playlist source at any time" in :
    val bufferDir = createPathInDirectory("sourceClosedLater")
    val random = new Random(20240903113527L)
    val sourceData = IndexedSeq(
      ByteString(random.nextBytes(8192)),
      ByteString(random.nextBytes(4096))
    )
    val source = Source.queue[Int](5)
    val resolverFunc = seqBasedResolverFunc(sourceData)
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 20000)

    val (queue, futResult) = runBufferedStreamWithSource(bufferConfig, source)()
    queue.offer(1)
    queue.offer(2)
    val bufferFile = bufferDir.resolve("buffer01.dat")
    val expectedFileSize = sourceData.map(_.size).sum

    given pc: PatienceConfig = PatienceConfig(timeout = scaled(1.second))

    eventually:
      Files.size(bufferFile) should be(expectedFileSize)

    queue.complete()
    futResult.map { results =>
      results should contain theSameElementsInOrderAs sourceData
    }

  it should "handle an empty playlist" in :
    val bufferDir = createPathInDirectory("empty")
    val source = Source.queue[Int](1)
    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = _ =>
      throw new UnsupportedOperationException("Unexpected invocation.")
    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 8192)

    val (queue, futResult) = runBufferedStreamWithSource(bufferConfig, source)()
    queue.complete()
    futResult.map { results =>
      results shouldBe empty
    }
