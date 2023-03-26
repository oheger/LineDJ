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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Test class for [[RadioStreamBuilder]].
  */
class RadioStreamBuilderSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper with RadioStreamTestHelper.StubServerSupport {
  def this() = this(ActorSystem("RadioStreamBuilderSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  /**
    * Creates a test builder and invokes it for the given stream URI.
    *
    * @param uri the URI of the test radio stream
    * @return the result produced by the builder
    */
  private def invokeBuilder(uri: String): RadioStreamBuilder.BuilderResult[Future[ByteString], Future[ByteString]] = {
    implicit val ec: ExecutionContext = system.dispatcher
    val config = PlayerConfig(mediaManagerActor = null, actorCreator = null)
    val builder = RadioStreamBuilder()
    val sinkAudio = RadioStreamTestHelper.aggregateSink()
    val sinkMeta = RadioStreamTestHelper.aggregateSink()
    futureResult(builder.buildRadioStream(config, uri, sinkAudio, sinkMeta))
  }

  "RadioStreamBuilder" should "create a correct graph to process the radio stream" in {
    val ChunkCount = 8
    val RadioStreamPath = "radio"
    val route = get {
      pathPrefix(RadioStreamPath) {
        val entity = HttpEntity(ContentTypes.`application/octet-stream`,
          RadioStreamTestHelper.generateRadioStreamSource(ChunkCount))
        val response = HttpResponse(headers = Seq(RawHeader("icy-metaint",
          RadioStreamTestHelper.AudioChunkSize.toString)), entity = entity)
        complete(response)
      }
    }
    val expectedAudioData = ByteString(RadioStreamTestHelper.refData(ChunkCount * RadioStreamTestHelper.AudioChunkSize))
    val expectedMetadata = (1 to ChunkCount).map(RadioStreamTestHelper.generateMetadata)
      .foldLeft(ByteString.empty) { (aggregate, chunk) => aggregate ++ ByteString(chunk) }

    runWithServer(route) { uri =>
      val streamUri = s"$uri/$RadioStreamPath/stream.mp3"
      val result = invokeBuilder(streamUri)

      result.resolvedUri should be(streamUri)
      result.metadataSupported shouldBe true
      val (futAudio, futMeta) = result.graph.run()
      futureResult(futAudio) should be(expectedAudioData)
      futureResult(futMeta) should be(expectedMetadata)
    }
  }

  it should "create a correct graph if no metadata is supported" in {
    val RadioStreamPath = "radio"
    val route = get {
      pathPrefix(RadioStreamPath) {
        RadioStreamTestHelper.completeTestData()
      }
    }

    runWithServer(route) { uri =>
      val result = invokeBuilder(s"$uri/$RadioStreamPath/stream-without-metadata.mp3")

      result.metadataSupported shouldBe false
      val (futAudio, futMeta) = result.graph.run()
      futureResult(futAudio).utf8String should be(FileTestHelper.TestData)
      futureResult(futMeta) should be(ByteString.empty)
    }
  }

  it should "return the correctly resolved stream URI" in {
    val RadioStreamPath = "radio"
    val ResolvedStreamPath = "data"
    val ResolvedStreamUri = ResolvedStreamPath + "/stream.mp3"
    val serverUri = new AtomicReference[String]
    val route = get {
      concat(
        pathPrefix(RadioStreamPath) {
          complete(s"${serverUri.get()}/$ResolvedStreamUri")
        },
        pathPrefix(ResolvedStreamPath) {
          RadioStreamTestHelper.completeTestData()
        }
      )
    }

    runWithServer(route) { uri =>
      serverUri.set(uri)
      val result = invokeBuilder(s"$uri/$RadioStreamPath/playlist.m3u")

      result.resolvedUri should be(s"$uri/$ResolvedStreamUri")
    }
  }

  it should "provide a KillSwitch to cancel the radio stream" in {
    val source = Source(ByteString(FileTestHelper.TestData).grouped(16).toList).delay(100.millis)
    val RadioStreamPath = "radio"
    val route = get {
      pathPrefix(RadioStreamPath) {
        val entity = HttpEntity(ContentTypes.`application/octet-stream`, source)
        complete(entity)
      }
    }

    runWithServer(route) { uri =>
      val result = invokeBuilder(s"$uri/$RadioStreamPath/stream.mp3")
      val (futAudio, _) = result.graph.run()

      result.killSwitch.shutdown()

      futureResult(futAudio).length should be < FileTestHelper.TestData.length
    }
  }
}
