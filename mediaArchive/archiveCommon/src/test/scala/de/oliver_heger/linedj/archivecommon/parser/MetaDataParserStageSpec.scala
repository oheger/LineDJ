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

package de.oliver_heger.linedj.archivecommon.parser

import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

object MetaDataParserStageSpec {
  /** A test medium ID. */
  private val TestMedium = MediumID("testMedium", Some("test.settings"), "foo")
}

/**
  * Test class for ''MetaDataParserStage''. This class only tests basic
  * functionality. Tests for classes that use this stage contain more
  * testing logic.
  */
class MetaDataParserStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("MetaDataParserStateSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import MetaDataParserStageSpec._

  /**
    * Executes a stream with the parser stage using the given source.
    *
    * @param src the source
    * @return the resulting list of processing results
    */
  private def parse(src: Source[ByteString, Any]): List[MetaDataProcessingSuccess] = {
    val stage = new MetaDataParserStage(TestMedium)

    val futureStream = src.via(stage)
      .runFold(List.empty[MetaDataProcessingSuccess])((lst, r) => r :: lst)
    Await.result(futureStream, 3.seconds)
  }

  "A MetaDataParserStage" should "process a source with JSON data" in {
    val json =
      s"""[{
         |"title":"Fire Water Burn",
         |"size":"1024",
         |"uri":"song://song1.mp3",
         |"path":"music/song1.mp3"
         |},
         |{
         |"title":"When the Night Comes",
         |"size":"2048",
         |"uri":"song://song2.mp3",
         |"path":"music/song2.mp3"
         |}]
   """.stripMargin
    val data = ByteString(json, StandardCharsets.UTF_8.name())

    val lstMetaData = parse(Source.single(data))
    lstMetaData should have size 2
    val titles = lstMetaData map (_.metaData.title.get)
    titles should contain only("Fire Water Burn", "When the Night Comes")
    lstMetaData.map(_.mediumID).toSet should contain only TestMedium
  }

  it should "process a real-life meta data file" in {
    val filePath = Paths.get(getClass.getResource("/metadata.mdt").toURI)

    val lstMetaData = parse(FileIO.fromPath(filePath, chunkSize = 512))
    lstMetaData should have size 67
  }
}
