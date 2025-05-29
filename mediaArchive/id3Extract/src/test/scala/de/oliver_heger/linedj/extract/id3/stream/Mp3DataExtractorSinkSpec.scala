/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3.stream

import de.oliver_heger.linedj.extract.id3.model.Mp3Metadata
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import scala.concurrent.Future

/**
  * Test class for [[Mp3DataExtractorSink]].
  */
class Mp3DataExtractorSinkSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("Mp3DataExtractorSinkSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Runs a stream with the given source to the test sink.
    *
    * @param source the source of the stream
    * @return the result produced by the sink
    */
  private def runStream(source: Source[ByteString, Any]): Future[Mp3Metadata] =
    val sink = new Mp3DataExtractorSink
    source.runWith(sink)

  "A Mp3DataExtractorSink" should "extract metadata from an MP3 file" in :
    val testFileUri = getClass.getResource("/test.mp3").toURI
    val expectedMetadata = Mp3Metadata(
      version = 2,
      layer = 1,
      sampleRate = 16000,
      minimumBitRate = 24000,
      maximumBitRate = 24000,
      duration = 10800
    )
    val source = FileIO.fromPath(Paths.get(testFileUri))

    runStream(source) map { metadata =>
      metadata should be(expectedMetadata)
    }

  it should "handle a failure on upstream gracefully" in :
    val testException = new IllegalStateException("Test exception: stream processing failed.")
    val source = Source.failed(testException)

    recoverToExceptionIf[IllegalStateException] {
      runStream(source)
    } map { exception =>
      exception should be(testException)
    }
