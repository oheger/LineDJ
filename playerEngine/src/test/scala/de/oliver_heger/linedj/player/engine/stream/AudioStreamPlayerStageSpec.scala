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

import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import javax.sound.sampled.{AudioInputStream, SourceDataLine}
import scala.concurrent.Future
import scala.util.Random

object AudioStreamPlayerStageSpec:
  /** The limit for calling the audio stream factory. */
  private val StreamFactoryLimit = 128

  /** The chunk size used by audio sources. */
  private val SourceChunkSize = 64

  /** A counter for generating unique actor names. */
  private var actorNameCounter = 0

  /**
    * Generates a unique name for an actor to pause playback.
    *
    * @return the name for the actor
    */
  private def pauseActorName(): String =
    actorNameCounter += 1
    s"pauseActor_$actorNameCounter"

  /**
    * Creates an [[AudioInputStream]] for a given input stream. This function
    * is used as stream creator for the test audio stream factory. It returns a 
    * [[AudioStreamTestHelper.DummyEncoderStream]] object.
    *
    * @param in the underlying input stream
    * @return the audio stream
    */
  private def createAudioStream(in: InputStream): AudioInputStream =
    new AudioStreamTestHelper.DummyEncoderStream(in, new LinkedBlockingQueue)
end AudioStreamPlayerStageSpec

/**
  * Test class for [[AudioStreamPlayerStage]].
  */
class AudioStreamPlayerStageSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues with MockitoSugar:
  def this() = this(classic.ActorSystem("AudioStreamPlayerStageSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import AudioStreamPlayerStageSpec.*

  /**
    * A data class for collecting information about an audio source that is 
    * processed by a test stream.
    *
    * @param content  the content of the source
    * @param line     the mock data line
    * @param lineData the data that was passed to the line
    */
  private case class AudioSourceData(content: Array[Byte],
                                     line: SourceDataLine,
                                     lineData: AtomicReference[ByteString]):
    /**
      * Checks whether the line for this audio source received the expected
      * data.
      */
    def verifyLine(): Unit =
      lineData.get().toArray should be(AudioStreamTestHelper.encodeBytes(content))
  end AudioSourceData

  /**
    * A data class used to aggregate over the played audio chunks for a single
    * audio source.
    *
    * @param sourceName the name of the source
    * @param totalSize  the total size in bytes of this source
    */
  private case class PlayedChunks(sourceName: String,
                                  totalSize: Int):
    /**
      * Returns an updated instance that incorporates the given chunk.
      *
      * @param chunk a chunk played for this source
      * @return the updated instance
      */
    def addChunk(chunk: LineWriterStage.PlayedAudioChunk): PlayedChunks =
      copy(totalSize = totalSize + chunk.size)
  end PlayedChunks

  "AudioStreamPlayerStage" should "set up a correct line writer stage" in :
    val helper = new StreamPlayerStageTestHelper

    helper.addAudioSource("source1", 512)
      .addAudioSource("source2", 1024)
      .runPlaylistStream(List("source1", "source2"))

    helper.nextResult()
    helper.sourceData("source1").verifyLine()
    helper.nextResult()
    helper.sourceData("source2").verifyLine()

  it should "return correct results" in :
    val helper = new StreamPlayerStageTestHelper

    helper.addAudioSource("source1", 768)
      .addAudioSource("anotherSource", 512)
      .runPlaylistStream(List("source1", "anotherSource"))
      .expectResult("source1")
      .expectResult("anotherSource")

  it should "correctly configure the encoding stage" in :
    val helper = new StreamPlayerStageTestHelper

    helper.addAudioSource("testSource", 1024)
      // Set a too low memory size, this should stall playback.
      .runPlaylistStream(List("testSource"), memorySize = 128)
      .expectNoResult()

  /**
    * A test helper class for running a playlist stream against a test stage.
    */
  private class StreamPlayerStageTestHelper:
    /** A map to store the data for test audio sources. */
    private var audioSourceData = Map.empty[String, AudioSourceData]

    /** An object for generating random content for audio sources. */
    private val random = Random()

    /** The actor for pausing playback. */
    private val pauseActor = createPauseActor()

    /** A queue for collecting stream results. */
    private val resultQueue = new LinkedBlockingQueue[PlayedChunks]

    /**
      * Adds a test audio source that can be part of a playlist stream.
      *
      * @param name the name of the audio source
      * @param size the size of this source
      * @return this test helper
      */
    def addAudioSource(name: String, size: Int): StreamPlayerStageTestHelper =
      val content = random.nextBytes(size)
      val (line, lineData) = createLine()
      audioSourceData += name -> AudioSourceData(content, line, lineData)
      this

    /**
      * Returns the [[AudioSourceData]] object for the given name.
      *
      * @param name the name
      * @return the [[AudioSourceData]] registered for this name
      */
    def sourceData(name: String): AudioSourceData = audioSourceData(name)

    /**
      * Executes a playlist stream with the given sources. The results are
      * stored in a queue and can be queried using ''nextResult()''.
      *
      * @param sources    the sources for the playlist
      * @param memorySize the size of the in-memory buffer
      * @return this test helper
      */
    def runPlaylistStream(sources: List[String],
                          memorySize: Int = AudioEncodingStage.DefaultInMemoryBufferSize):
    StreamPlayerStageTestHelper =
      val source = Source(sources)
      val sink = Sink.foreach[PlayedChunks](resultQueue.offer)
      source.via(AudioStreamPlayerStage(createStageConfig(sources, memorySize))).runWith(sink)
      this

    /**
      * Returns the next result from the playlist stream sink or ''None'' if 
      * there is none in the given timeout.
      *
      * @param timeoutMillis the timeout
      * @return an ''Option'' with the next result
      */
    def nextResult(timeoutMillis: Long = 3000): Option[PlayedChunks] =
      Option(resultQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS))

    /**
      * Obtains the next result from the playlist stream sink and compares it
      * to the given expectations.
      *
      * @param name the expected source name
      * @return this test helper
      */
    def expectResult(name: String): StreamPlayerStageTestHelper =
      val playedChunks = nextResult().value
      playedChunks.sourceName should be(name)

      val expectedSize = AudioStreamTestHelper.encodeBytes(audioSourceData(name).content).length
      playedChunks.totalSize should be(expectedSize)
      this

    /**
      * Checks that no result is received from the playlist stream, which means
      * that playback is not possible for whatever reason.
      *
      * @return this test helper
      */
    def expectNoResult(): StreamPlayerStageTestHelper =
      resultQueue.poll(500, TimeUnit.MILLISECONDS) should be(null)
      this

    /**
      * Creates a mock for a line that records the data that is written to it.
      *
      * @return a tuple with the mock for the line and the object to collect
      *         the write operations
      */
    private def createLine(): (SourceDataLine, AtomicReference[ByteString]) =
      val line = mock[SourceDataLine]
      val lineData = new AtomicReference(ByteString.empty)
      when(line.write(any(), any(), any())).thenAnswer((invocation: InvocationOnMock) =>
        val data = invocation.getArgument[Array[Byte]](0)
        val offset = invocation.getArgument[Int](1)
        val len = invocation.getArgument[Int](2)
        val str = ByteString.fromArray(data, offset, len)
        lineData.set(lineData.get() ++ str)
        len)
      line -> lineData

    /**
      * Creates the actor for pausing playback.
      *
      * @return the pause playback actor
      */
    private def createPauseActor(): ActorRef[PausePlaybackStage.PausePlaybackCommand] =
      system.spawn(PausePlaybackStage.pausePlaybackActor(PausePlaybackStage.PlaybackState.PlaybackPossible),
        pauseActorName())

    /**
      * Creates an [[AudioStreamFactory]] to be used for tests. The factory 
      * returns a dummy encoding stream for the audio sources that have been
      * registered. For other source names, it returns ''None''.
      *
      * @return the [[AudioStreamFactory]] for tests
      */
    private def createAudioStreamFactory(): AudioStreamFactory =
      (uri: String) => audioSourceData.get(uri).map { _ =>
        AudioStreamFactory.AudioStreamPlaybackData(createAudioStream, StreamFactoryLimit)
      }

    /**
      * Resolves an audio source by its name. This function creates a source
      * with the content of a registered source. If the name cannot be 
      * resolved, a dummy source is returned.
      *
      * @param name the name of the audio source
      * @return a ''Future'' with the resolved source
      */
    private def resolveSource(name: String): Future[AudioStreamPlayerStage.AudioStreamSource] =
      val data = audioSourceData.get(name).map(src => ByteString(src.content)).getOrElse(ByteString("DummyData"))
      val source = Source(data.grouped(SourceChunkSize).toList)
      Future.successful(AudioStreamPlayerStage.AudioStreamSource(name, source))

    /**
      * Creates a [[Sink]] for a given audio source. This is used as the sink
      * provider function.
      *
      * @param name the name of the audio source
      * @return the sink for this source
      */
    private def createSink(name: String): Sink[LineWriterStage.PlayedAudioChunk, Future[PlayedChunks]] =
      Sink.fold(PlayedChunks(name, 0)) { (agg, chunk) => agg.addChunk(chunk) }

    /**
      * Returns a function to create the next source data line.
      *
      * @param sources the list of expected sources
      * @return the function to obtain the next line
      */
    private def createLineCreator(sources: List[String]): LineWriterStage.LineCreatorFunc =
      val refSources = new AtomicReference(sources)
      header =>
        header.format should be(AudioStreamTestHelper.Format)
        val currentSources = refSources.get()
        val nextSource = currentSources.head
        refSources.set(currentSources.tail)
        audioSourceData(nextSource).line

    /**
      * Creates the configuration for the player stage to be tested.
      *
      * @param sources    the list of expected sources
      * @param memorySize the size of the in-memory buffer
      * @return the configuration for the stage
      */
    private def createStageConfig(sources: List[String], memorySize: Int):
    AudioStreamPlayerStage.AudioStreamPlayerConfig[String, PlayedChunks] =
      AudioStreamPlayerStage.AudioStreamPlayerConfig(
        sourceResolverFunc = resolveSource,
        audioStreamFactory = createAudioStreamFactory(),
        pauseActor = pauseActor,
        sinkProviderFunc = createSink,
        lineCreatorFunc = createLineCreator(sources),
        inMemoryBufferSize = memorySize
      )
