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
import org.apache.pekko.{Done, actor as classic}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.{KillSwitches, SharedKillSwitch}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.Inspectors.forAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import javax.sound.sampled.{AudioInputStream, SourceDataLine}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Random

object AudioStreamPlayerStageSpec:
  /** The limit for calling the audio stream factory. */
  private val StreamFactoryLimit = 128

  /** The chunk size used by audio sources. */
  private val SourceChunkSize = 64

  /**
    * The name of a source that triggers an error when creating a line. This is
    * used for testing error handling.
    */
  private val ErrorSource = "willFail"

  /**
    * A special instance of [[PlayedChunks]] to indicate the end of the
    * playlist stream. The test helper writes this value into the results
    * queue when the stream ends.
    */
  private val PlaylistEnd = AudioStreamPlayerStage.AudioStreamEnd(PlayedChunks("", -1))

  /**
    * The timeout (in milliseconds) when polling the result queue and no result
    * is expected.
    */
  private val TimeoutNoResultMs = 500

  /**
    * The timeout (in milliseconds) when polling he result queue for an
    * expected result.
    */
  private val TimeoutResultMs = 3000

  /** A counter for generating unique actor names. */
  private var actorNameCounter = 0

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
      .expectNoResult(skip = 1)

  it should "correctly integrate the pause actor" in :
    val sourceName = "pausedSource"
    val helper = new StreamPlayerStageTestHelper

    helper.sendPausePlaybackCommand(PausePlaybackStage.StopPlayback)
      .addAudioSource(sourceName, 2048)
      .runPlaylistStream(List(sourceName))
      .expectNoResult(skip = 1)
      .sendPausePlaybackCommand(PausePlaybackStage.StartPlayback)
      .expectResult(sourceName)

  it should "skip elements that cannot be handled by the AudioStreamFactory" in :
    val firstSource = "theFirstSource"
    val lastSource = "theLastSource"
    val helper = new StreamPlayerStageTestHelper

    helper.addAudioSource(firstSource, 512)
      .addAudioSource(lastSource, 256)
      .runPlaylistStream(List(firstSource, "ignoredSource", lastSource))
      .expectResult(firstSource)
      .expectResult(lastSource)

  it should "support a kill switch to cancel the audio stream" in :
    val source = "SourceToBeCanceled"
    val killSwitch = KillSwitches.shared("testKillSwitch")
    val helper = new StreamPlayerStageTestHelper
    helper.addAudioSource(source, 65536)
      .runPlaylistStream(List(source), optKillSwitch = Some(killSwitch), reportSourceStart = true, withDelay = true)
    val sourceStartResult = helper.nextResult().value
    sourceStartResult should be(PlayedChunks(source, 0))

    killSwitch.shutdown()

    val encodedSourceContent = AudioStreamTestHelper.encodeBytes(helper.sourceData(source).content)
    val result = helper.nextResult().value
    result.totalSize should be < encodedSourceContent.length

  "runPlaylistStream" should "cancel the playlist stream when the kill switch is triggered" in :
    val killSwitch = KillSwitches.shared("playlistKiller")
    val SourceCount = 16
    val helper = new StreamPlayerStageTestHelper
    val sources = (1 to SourceCount).map(idx => s"AudioSource$idx")
    sources.foreach { src =>
      helper.addAudioSource(src, 1024)
    }

    helper.runPlaylistStream(sources.toList, optKillSwitch = Some(killSwitch))
    helper.nextResult()
    killSwitch.shutdown()

    @tailrec def fetchResults(count: Int): Int =
      helper.nextResult() match
        case Some(PlaylistEnd.result) => count
        case _ => fetchResults(count + 1)

    val resultCount = fetchResults(0)
    resultCount should be < SourceCount - 1

  it should "continue with the next audio source when playback crashes" in :
    val sources = List("firstSource", ErrorSource, "sourceAfterError")
    val helper = new StreamPlayerStageTestHelper

    helper.addAudioSource(sources.head, 128)
      .addAudioSource(ErrorSource, 42)
      .addAudioSource(sources.last, 256)
      .runPlaylistStream(sources)
      .expectResult(sources.head)
      .expectResult(sources.last)

  it should "return the materialized value of the source" in :
    val sources = List("song1", "song2", "song3")
    val playlistSource = Source.queue[String](5)
    val helper = new StreamPlayerStageTestHelper
    helper.addAudioSource("song1", 1024)
      .addAudioSource("song2", 768)
      .addAudioSource("song3", 2048)

    val (queue, _) = helper.runPlaylistStreamWithSource(playlistSource, sources)
    sources.foreach(queue.offer)

    forAll(sources)(helper.expectResult)

  it should "include elements indicating the start of an audio source" in :
    val SourceName = "testAudioSource"
    val helper = new StreamPlayerStageTestHelper
    helper.addAudioSource(SourceName, 4096)
      .sendPausePlaybackCommand(PausePlaybackStage.StopPlayback)
      .runPlaylistStream(List(SourceName))

    val startEvent = helper.nextPlaylistResult().value

    startEvent should be(AudioStreamPlayerStage.AudioStreamStart(SourceName))

    helper.expectNoResult()
      .sendPausePlaybackCommand(PausePlaybackStage.StartPlayback)
      .expectResult(SourceName)

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
    private val resultQueue =
      new LinkedBlockingQueue[AudioStreamPlayerStage.PlaylistStreamResult[String, PlayedChunks]]

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
      * @param sources           the sources for the playlist
      * @param memorySize        the size of the in-memory buffer
      * @param optKillSwitch     an optional kill switch to add to the stream
      * @param reportSourceStart flag whether a result for a newly started
      *                          audio source should be published
      * @param withDelay         flag whether audio sources should be delayed
      * @return this test helper
      */
    def runPlaylistStream(sources: List[String],
                          memorySize: Int = AudioEncodingStage.DefaultInMemoryBufferSize,
                          optKillSwitch: Option[SharedKillSwitch] = None,
                          reportSourceStart: Boolean = false,
                          withDelay: Boolean = false): StreamPlayerStageTestHelper =
      given ec: ExecutionContext = system.dispatcher

      val source = Source(sources)
      val (_, futSink) = runPlaylistStreamWithSource(
        source,
        sources,
        memorySize,
        optKillSwitch,
        reportSourceStart,
        withDelay
      )
      futSink.foreach { _ => resultQueue.offer(PlaylistEnd) }
      this

    /**
      * Executes a playlist with the given parameters. With this function, the
      * [[Source]] of the stream can be explicitly specified, and its
      * materialized value is returned.
      *
      * @param source            the source for the audio sources in the playlist
      * @param sources           the audio sources to be played
      * @param memorySize        the size of the in-memory buffer
      * @param optKillSwitch     an optional kill switch to add to the stream
      * @param reportSourceStart flag whether a result for a newly started
      *                          audio source should be published
      * @param withDelay         flag whether audio sources should be delayed
      * @tparam MAT the type of the data materialized by the source
      * @return the materialized data from the source
      */
    def runPlaylistStreamWithSource[MAT](source: Source[String, MAT],
                                         sources: List[String],
                                         memorySize: Int = AudioEncodingStage.DefaultInMemoryBufferSize,
                                         optKillSwitch: Option[SharedKillSwitch] = None,
                                         reportSourceStart: Boolean = false,
                                         withDelay: Boolean = false): (MAT, Future[Done]) =
      given ec: ExecutionContext = system.dispatcher

      val sink = Sink.foreach[AudioStreamPlayerStage.PlaylistStreamResult[String, PlayedChunks]](resultQueue.offer)
      val config = createStageConfig(sources, memorySize, optKillSwitch, reportSourceStart, withDelay)

      AudioStreamPlayerStage.runPlaylistStream(config, source, sink)

    /**
      * Returns the next result from the playlist stream sink or ''None'' if 
      * there is none in the given timeout. 
      *
      * @return an ''Option'' with the next result
      */
    def nextPlaylistResult():
    Option[AudioStreamPlayerStage.PlaylistStreamResult[String, PlayedChunks]] =
      pollResultQueue(TimeoutResultMs)

    /**
      * Returns the next [[PlayedChunks]] result from the playlist stream sink
      * or ''None'' if there is none in the given timeout. 
      *
      * @return an ''Option'' with the next [[PlayedChunks]]
      */
    def nextResult(): Option[PlayedChunks] =
      nextPlaylistResult() match
        case None => None
        case Some(AudioStreamPlayerStage.AudioStreamEnd(result)) => Some(result)
        case Some(_: AudioStreamPlayerStage.AudioStreamStart[String]) => nextResult()

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
      * that playback is not possible for whatever reason. Optionally, a number
      * of expected results can be skipped.
      *
      * @param skip the number of results to skip
      * @return this test helper
      */
    def expectNoResult(skip: Int = 0): StreamPlayerStageTestHelper =
      forAll((1 to skip).toList) { _ =>
        pollResultQueue(TimeoutResultMs).value
      }
      pollResultQueue(TimeoutNoResultMs) should be(None)
      this

    /**
      * Sends the given command to the managed pause actor.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendPausePlaybackCommand(command: PausePlaybackStage.PausePlaybackCommand): StreamPlayerStageTestHelper =
      pauseActor ! command
      this

    /**
      * Reads an item from the result queue waiting for the given timeout.
      * Result is an undefined option if no result was received within this
      * timeout.
      *
      * @param timeoutMs the timeout (in milliseconds)
      * @return an ''Option'' with the received result
      */
    private def pollResultQueue(timeoutMs: Long):
    Option[AudioStreamPlayerStage.PlaylistStreamResult[String, PlayedChunks]] =
      Option(resultQueue.poll(500, TimeUnit.MILLISECONDS))

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
      * @param withDelay flag whether the source should be delayed
      * @param name      the name of the audio source
      * @return a ''Future'' with the resolved source
      */
    private def resolveSource(withDelay: Boolean)(name: String): Future[AudioStreamPlayerStage.AudioStreamSource] =
      val data = audioSourceData.get(name).map(src => ByteString(src.content)).getOrElse(ByteString("DummyData"))
      val source = Source(data.grouped(SourceChunkSize).toList)
      val delayedSource = if withDelay then source.delay(50.millis) else source
      Future.successful(AudioStreamPlayerStage.AudioStreamSource(name, delayedSource))

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
      * @param sources        the list of expected sources
      * @param reportCreation flag whether the function should put a chunks
      *                       object in the result queue to indicate the start
      *                       of a new source; this is used by some test cases
      * @return the function to obtain the next line
      */
    private def createLineCreator(sources: List[String], reportCreation: Boolean): LineWriterStage.LineCreatorFunc =
      val refSources = new AtomicReference(sources.filter(audioSourceData.contains))
      header =>
        header.format should be(AudioStreamTestHelper.Format)
        val currentSources = refSources.get()
        val nextSource = currentSources.head
        refSources.set(currentSources.tail)

        if reportCreation then
          resultQueue.offer(AudioStreamPlayerStage.AudioStreamEnd(PlayedChunks(nextSource, 0)))
        if nextSource == ErrorSource then
          throw new IllegalStateException("Test exception: Unsupported audio source.")
        audioSourceData(nextSource).line

    /**
      * Creates the configuration for the player stage to be tested.
      *
      * @param sources           the list of expected sources
      * @param memorySize        the size of the in-memory buffer
      * @param optKillSwitch     the kill switch for the config
      * @param reportSourceStart flag whether a result for a newly started
      *                          audio source should be published
      * @param withDelay         flag whether the audio source should be
      *                          delayed
      * @return the configuration for the stage
      */
    private def createStageConfig(sources: List[String],
                                  memorySize: Int,
                                  optKillSwitch: Option[SharedKillSwitch],
                                  reportSourceStart: Boolean,
                                  withDelay: Boolean):
    AudioStreamPlayerStage.AudioStreamPlayerConfig[String, PlayedChunks] =
      AudioStreamPlayerStage.AudioStreamPlayerConfig(
        sourceResolverFunc = resolveSource(withDelay),
        audioStreamFactory = createAudioStreamFactory(),
        pauseActor = pauseActor,
        sinkProviderFunc = createSink,
        lineCreatorFunc = createLineCreator(sources, reportSourceStart),
        optKillSwitch = optKillSwitch,
        inMemoryBufferSize = memorySize
      )
