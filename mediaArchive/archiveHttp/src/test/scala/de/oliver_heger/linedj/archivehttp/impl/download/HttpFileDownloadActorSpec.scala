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

package de.oliver_heger.linedj.archivehttp.impl.download

import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor.DownloadTransformFunc
import de.oliver_heger.linedj.shared.archive.media.{DownloadComplete, DownloadData, DownloadDataResult}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object HttpFileDownloadActorSpec:
  /** Test data to be sent during a simulated download operation. */
  private val Data = List(ByteString("This is"), ByteString(" test data "),
    ByteString("for an example"), ByteString(" download operation."))

  /** A test URI prefix for a download operation. */
  private val TestUriPrefix = "https://test.archive.org/music/Song."

  /** A file extension that is detected by the test transformation function. */
  private val TransformedExtension = "mp3"

  /**
    * Generates a download URI for a test file with the given extension.
    *
    * @param ext the file extension
    * @return the generated download URI
    */
  private def downloadUriFor(ext: String): Uri =
    Uri(s"$TestUriPrefix$ext")

  /**
    * Returns a source with test data.
    *
    * @return the test data source
    */
  private def testDataSource: Source[ByteString, NotUsed] = Source(Data)

  /**
    * Returns a transformation function to be used in tests. The function
    * removes all whitespace from the strings to be processed.
    *
    * @return the test transformation function
    */
  private def testTransform: DownloadTransformFunc =
    case TransformedExtension =>
      Flow.fromFunction[ByteString, ByteString](_.filterNot(_ == ' '))

/**
  * Test class for ''HttpFileDownloadActor''.
  */
class HttpFileDownloadActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("HttpFileDownloadActorSpec"))

  import HttpFileDownloadActorSpec._

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A HttpFileDownloadActor" should "provide data from a response" in:
    val actor = system.actorOf(HttpFileDownloadActor(testDataSource, downloadUriFor("txt"), testTransform))

    Data foreach { chunk =>
      actor ! DownloadData(8192)
      val result = expectMsgType[DownloadDataResult]
      result.data should be(chunk)
    }
    actor ! DownloadData(4096)
    expectMsg(DownloadComplete)

  it should "pass a correct path object to the super class" in:
    val actor = system.actorOf(HttpFileDownloadActor(testDataSource, downloadUriFor(TransformedExtension),
      testTransform))

    actor ! DownloadData(8192)
    val result = expectMsgType[DownloadDataResult]
    result.data.utf8String should not include " "
