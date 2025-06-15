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

import de.oliver_heger.linedj.FileTestHelper
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import scala.concurrent.Future

object ID3SkipStageSpec:
  /** The name of the test file. */
  private val TestFile = "/testID3v2Data.bin"

  /** The actual data content of the test file. */
  private val TestData = "Lorem ipsum dolor sit amet, consetetur sadipscing " +
    "elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore " +
    "magna aliquyam erat, sed diam voluptua. At vero eos et " +
    "accusam et justo duo"
end ID3SkipStageSpec

/**
  * Test class for [[ID3SkipStage]].
  */
class ID3SkipStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("ID3SkipStageSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ID3SkipStageSpec.*

  /**
    * Runs a stream with the given source and a test stage to skip ID3 data.
    * Returns a [[Future]] with the bytes that were received.
    *
    * @param source the [[Source]] of the stream
    * @return a [[Future]] with the data passed downstream
    */
  private def runStream(source: Source[ByteString, Any]): Future[ByteString] =
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val skipStage = new ID3SkipStage
    source.via(skipStage).runWith(sink)

  "An ID3SkipStage" should "pass only non-ID3 frames downstream" in :
    val testFileUri = getClass.getResource(TestFile).toURI
    val source = FileIO.fromPath(Paths.get(testFileUri))

    runStream(source) map { result =>
      result.utf8String should be(TestData)
    }

  it should "handle a file containing no ID3 frames" in :
    val source = Source(FileTestHelper.testBytes().grouped(23).map(ByteString.apply).toList)

    runStream(source) map { result =>
      result should be(ByteString(FileTestHelper.testBytes()))
    }
