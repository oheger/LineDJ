/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.io.Source

/**
  * Test class for ''StreamReference''.
  */
class StreamReferenceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike with BeforeAndAfter
  with BeforeAndAfterAll with Matchers with FileTestHelper with AsyncTestHelper {

  import system.dispatcher

  def this() = this(ActorSystem("StreamReferenceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  after {
    tearDownTestFile()
  }

  /**
    * Creates a [[StreamReference]] from a file with test data.
    *
    * @return the reference
    */
  private def createStreamReferenceFromFile(): StreamReference = {
    val path = createDataFile()
    StreamReference(path.toUri.toString)
  }

  /**
    * Creates a test reference, obtains a source from it, and runs it against
    * the given sink. The result is returned.
    *
    * @param sink      the sink of the stream
    * @param chunkSize the chunk size to use for the stream
    * @tparam Mat the materialized type of the sink
    * @return the materialized value of the sink
    */
  private def runStreamWith[Mat](sink: Sink[ByteString, Future[Mat]], chunkSize: Int = 128): Mat = {
    val ref = createStreamReferenceFromFile()

    futureResult(for {
      source <- ref.createSource(chunkSize)
      content <- source.runWith(sink)
    } yield content)
  }

  "A StreamReference" should "open the referenced stream" in {
    val ref = createStreamReferenceFromFile()

    val stream = ref.openStream()
    val source = Source.fromInputStream(stream, StandardCharsets.UTF_8.toString)
    val data = source.mkString
    source.close()

    data should be(FileTestHelper.TestData)
  }

  it should "create a Source for the referenced stream" in {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)

    val data = runStreamWith(sink)

    data.utf8String should be(FileTestHelper.TestData)
  }

  it should "take the chunk size into account when creating a Source" in {
    val chunkSize = 17
    val sink = Sink.fold[List[ByteString], ByteString](List.empty) { (lst, data) => data :: lst }

    val elements = runStreamWith(sink, chunkSize)

    elements.size should be > 2
    elements.tail.forall(_.length == chunkSize) shouldBe true
  }
}
