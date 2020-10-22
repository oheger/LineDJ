/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3.processor

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import akka.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for ''ID3v1ProcessingStage''.
  */
class ID3v1ProcessingStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("ID3v1ProcessingStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Reads the content of a file as several chunks and returns it as a
    * list.
    *
    * @param file the file to be read
    * @return a list with the chunks read from the file
    */
  private def readFile(file: String): List[ByteString] = {
    val buf = new Array[Byte](1023)
    val in = getClass.getResourceAsStream("/" + file)

    def readChunk(lst: List[ByteString]): List[ByteString] = {
      val len = in.read(buf)
      if (len > 0) ByteString(buf.slice(0, len)) :: readChunk(lst)
      else lst
    }

    readChunk(Nil)
  }

  /**
    * Processes the specified test file via the test stage and returns the
    * result message that was sent to the processor actor.
    *
    * @param file the file to be processed
    * @return the extracted ID3v1 meta data
    */
  private def processFile(file: String): ID3v1MetaData = {
    val probeProcessor = TestProbe()
    val fileContent = readFile(file)
    val source = Source(fileContent)
    val stage = new ID3v1ProcessingStage(probeProcessor.ref)

    val futStream = source.via(stage).runFold(ByteString.empty)(_ ++ _)
    val data = Await.result(futStream, 3.seconds)
    val content = fileContent.foldLeft(ByteString.empty)(_ ++ _)
    data should be(content)
    probeProcessor.expectMsgType[ID3v1MetaData]
  }

  "An ID3v1ProcessingStage" should "handle a file without ID3v1 data" in {
    val result = processFile("testID3v2Data.bin")

    result.metaData shouldBe 'empty
  }

  it should "extract valid ID3v1 data" in {
    val meta = processFile("testMP3id3v1.mp3").metaData.get

    meta.title should be(Some("Test Title"))
    meta.artist should be(Some("Test Artist"))
    meta.album should be(Some("Test Album"))
  }

  it should "handle an empty stream" in {
    val probeProcessor = TestProbe()
    val source = Source.single(ByteString.empty)
    val stage = new ID3v1ProcessingStage(probeProcessor.ref)

    val futStream = source.via(stage).runWith(Sink.ignore)
    Await.result(futStream, 3.seconds)
    probeProcessor.expectMsgType[ID3v1MetaData].metaData shouldBe 'empty
  }
}
