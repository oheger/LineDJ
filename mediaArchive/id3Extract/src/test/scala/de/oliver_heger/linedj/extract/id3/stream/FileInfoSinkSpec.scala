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
  * Test class for [[FileInfoSink]].
  */
class FileInfoSinkSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("FileInfoSinkSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Returns a source with the content of the test file with the given 
    * (resource) name.
    *
    * @param name the name of the test file
    * @return the source for the content of this file
    */
  private def testFileSource(name: String): Source[ByteString, Any] =
    val testFileUri = getClass.getResource(s"/$name").toURI
    FileIO.fromPath(Paths.get(testFileUri))

  "A FileInfoSink" should "correctly calculate the file size" in :
    val source = testFileSource("test.mp3")
    val sink = new FileInfoSink

    source.runWith(sink) map { result =>
      result.size should be(34576L)
    }

  it should "calculate a correct SHA-1 hash value" in :
    val source = testFileSource("test2.mp3")
    val sink = new FileInfoSink("SHA-1")

    source.runWith(sink) map { result =>
      result.hashAlgorithm should be("SHA-1")
      result.hashValue should be("dd39374a42907e58b645361371716436a3e10c6c")
    }

  it should "calculate a correct SHA-256 hash value" in :
    val source = testFileSource("test2.mp3")
    val sink = new FileInfoSink

    source.runWith(sink) map { result =>
      result.hashAlgorithm should be("SHA-256")
      result.hashValue should be("b284f1269586741aead24b2e38fdc4b0067a9f113cc48ad6afbe8684b0fe3310")
    }

  it should "handle a failure from upstream gracefully" in :
    val testException = new IllegalStateException("Test exception: stream processing failed.")
    val source = Source.failed(testException)
    val sink = new FileInfoSink

    recoverToExceptionIf[IllegalStateException] {
      source.runWith(sink)
    } map { exception =>
      exception should be(testException)
    }
    