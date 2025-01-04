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

package de.oliver_heger.linedj.archivecommon.parser

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import scala.concurrent.Future

object MetadataParserSpec:
  /** A test medium ID. */
  private val TestMedium = MediumID("someTestMedium", Some("test"))

  /** Name of the metadata file with test data. */
  private val TestMetadataFile = "test_metadata.mdt"
end MetadataParserSpec

/**
  * Test class for ''MetaDataParser''.
  */
class MetadataParserSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("MetaDataParserSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import MetadataParser.*
  import MetadataParserSpec.*
  import spray.json.*

  /**
    * Reads a metadata file from the classpath with the given resource name.
    *
    * @param name the resource name of the file to be read (without trailing 
    *             slash)
    * @return a [[Future]] with the extracted data
    */
  private def readMetadataFile(name: String): Future[List[MetadataProcessingSuccess]] =
    val filePath = Paths.get(getClass.getResource("/" + name).toURI)
    val source = FileIO.fromPath(filePath)
    val sink = Sink.fold[List[MetadataProcessingSuccess], MetadataProcessingSuccess](List.empty) { (lst, data) =>
      data :: lst
    }
    MetadataParser.parseMetadata(source, TestMedium).runWith(sink).map(_.reverse)

  "MetadataParser" should "set the correct medium ID" in :
    readMetadataFile(TestMetadataFile) map { results =>
      forEvery(results) { result => result.mediumID should be(TestMedium) }
    }

  it should "set the correct file URI" in :
    val expectedUris = List(MediaFileUri("testUri"), MediaFileUri("undefinedUri"))

    readMetadataFile(TestMetadataFile) map { results =>
      results.map(_.uri) should contain theSameElementsInOrderAs expectedUris
    }

  it should "handle undefined metadata" in :
    readMetadataFile(TestMetadataFile) map { results =>
      results(1).metadata should be(MediaMetadata())
    }

  it should "correctly parse metadata" in :
    val expectedMetadata = MediaMetadata(
      album = Some("testAlbum"),
      artist = Some("testArtist"),
      title = Some("testTitle"),
      formatDescription = Some("testFormat"),
      size = Some(4581376L),
      inceptionYear = Some(2024),
      trackNumber = Some(1),
      duration = Some(100000)
    )

    readMetadataFile(TestMetadataFile) map { results =>
      results.head.metadata should be(expectedMetadata)
    }

  it should "throw an exception for input without a URI" in :
    recoverToSucceededIf[DeserializationException] {
      readMetadataFile("metadata_without_uri.mdt")
    }

  it should "throw an exception for input with invalid property values" in :
    recoverToSucceededIf[DeserializationException] {
      readMetadataFile("metadata_invalid.mdt")
    }

  it should "process a real-life metadata file" in :
    readMetadataFile("metadata.mdt") map { results =>
      results should have size 67
    }

  it should "produce correct JSON output for metadata with an URI" in :
    val data = MetadataParser.MetadataWithUri(
      uri = "https://metadata.example.com/test",
      metadata = MediaMetadata(
        title = Some("a test song"),
        artist = Some("a test artist"),
        album = Some("a test album"),
        duration = Some(250123),
        trackNumber = Some(5),
        inceptionYear = Some(1980),
        formatDescription = Some("high quality"),
        size = Some(10000)
      )
    )

    val jsonAst = data.toJson
    val jsonStr = jsonAst.prettyPrint

    val dataDeserialized = jsonStr.parseJson.convertTo[MetadataParser.MetadataWithUri]
    dataDeserialized should be(data)
