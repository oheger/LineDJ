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
import de.oliver_heger.linedj.player.engine.stream.BufferedPlaylistSource.FillBufferResult
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.{Files, Path}
import scala.concurrent.Future
import scala.util.Random

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

  "FillBufferFlowStage" should "load all sources into the buffer" in :
    val bufferDir = createPathInDirectory("buffer")
    val random = new Random(20240706212512L)
    val sourceIndices = 1 to 4
    val sourceData = sourceIndices map { idx => ByteString(random.nextBytes(idx * 1024)) }

    val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Int] = idx =>
      Future.successful(AudioStreamPlayerStage.AudioStreamSource(s"source$idx.wav",
        Source(sourceData(idx - 1).grouped(256).toList)))

    val streamPlayerConfig = createStreamPlayerConfig(resolverFunc)
    val bufferConfig = BufferedPlaylistSource.BufferedPlaylistSourceConfig(streamPlayerConfig = streamPlayerConfig,
      bufferFolder = bufferDir,
      bufferFileSize = 65536)
    val sink = Sink.fold[List[FillBufferResult], FillBufferResult](List.empty) { (lst, res) => res :: lst }
    val source = Source(sourceIndices)
    val stage = new BufferedPlaylistSource.FillBufferFlowStage(bufferConfig)

    source.via(stage).runWith(sink) map { fillResults =>
      val expectedData = sourceData.reduce(_ ++ _)
      val bufferFile = bufferDir.resolve("buffer01.dat")
      ByteString(Files.readAllBytes(bufferFile)) should be(expectedData)
      fillResults should have size sourceIndices.size
    }
    