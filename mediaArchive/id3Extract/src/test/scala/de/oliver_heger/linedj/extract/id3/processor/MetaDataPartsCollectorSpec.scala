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
package de.oliver_heger.linedj.extract.id3.processor

import java.nio.file.Paths

import de.oliver_heger.linedj.extract.id3.model.ID3Header
import de.oliver_heger.linedj.extract.metadata.MetaDataProvider
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object MetaDataPartsCollectorSpec:
  /** Stub for an ID3 tag provider. */
  private val ID3MetaData: MetaDataProvider = new MetaDataProvider:
    override val inceptionYearString: Option[String] = Some("1969")

    override val album: Option[String] = Some("Led Zeppelin II")

    override val trackNoString: Option[String] = Some("3")

    override val artist: Option[String] = Some("Led Zeppelin")

    override val title: Option[String] = Some("The Lemon Song")

  /** A test MP3 meta data object. */
  private val Mp3Data = Mp3MetaData(version = 3, layer = 0, sampleRate = 0,
    minimumBitRat = 0, maximumBitRate = 128000, duration = 60000)

  /** A test ID3v2 frame header object. */
  private val ID3FrameHeader = ID3Header(version = 2, size = 42)

  /** A test media file. */
  private val File = FileData(Paths.get("somePath"), 20150912211021L)

  /** The expected final meta data. */
  private val MetaData = MediaMetaData(title = ID3MetaData.title, artist = ID3MetaData.artist,
    album = ID3MetaData.album, inceptionYear = ID3MetaData.inceptionYear, trackNumber =
      ID3MetaData.trackNo, duration = Some(Mp3Data.duration), formatDescription = Some("128 kbps"),
    size = File.size)

/**
  * Test class for ''MetaDataPartsCollector''.
  */
class MetaDataPartsCollectorSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import MetaDataPartsCollectorSpec._

  /**
    * Checks whether the correct media data has been generated.
    *
    * @param metaData the option with meta data returned by the collector
    * @return the verified meta data
    */
  private def checkMetaData(metaData: Option[MediaMetaData]): MediaMetaData =
    metaData should be(Some(MetaData))
    MetaData

  "A MetaDataPartsCollector" should "create a default ID3 collector" in:
    val collector = new MetaDataPartsCollector(File)
    collector.id3Collector shouldBe a[MetaDataID3Collector]

  it should "allow adding undefined meta data" in:
    val collector = new MetaDataPartsCollector(File)

    collector setMp3MetaData Mp3Data shouldBe empty

  it should "allow adding undefined ID3v1 meta data" in:
    val id3Collector = mock[MetaDataID3Collector]
    val collector = new MetaDataPartsCollector(File, id3Collector)

    collector setID3v1MetaData None shouldBe empty
    verifyNoInteractions(id3Collector)

  it should "pass defined ID3v1 meta data to the ID3 collector" in:
    val id3Collector = mock[MetaDataID3Collector]
    val collector = new MetaDataPartsCollector(File, id3Collector)

    collector setID3v1MetaData Some(ID3MetaData) shouldBe empty
    verify(id3Collector).addProvider(1, ID3MetaData)

  /**
    * Creates a mock ID3 collector and prepares it to return the test ID3 meta
    * data.
    *
    * @return the mock collector
    */
  private def prepareID3Collector(): MetaDataID3Collector =
    val id3Collector = mock[MetaDataID3Collector]
    when(id3Collector.createCombinedID3TagProvider()).thenReturn(ID3MetaData)
    id3Collector

  it should "generate meta data when MP3 data is available and ID3v1 data is added" in:
    val id3Collector = prepareID3Collector()
    val collector = new MetaDataPartsCollector(File, id3Collector)
    collector setMp3MetaData Mp3Data

    checkMetaData(collector setID3v1MetaData Some(ID3MetaData))

  it should "generate meta data when ID3v1 data is available and MP3 data is added" in:
    val id3Collector = prepareID3Collector()
    val collector = new MetaDataPartsCollector(File, id3Collector)
    collector setID3v1MetaData Some(ID3MetaData)


    checkMetaData(collector setMp3MetaData Mp3Data)

  it should "detect missing ID3v2 information" in:
    val collector = new MetaDataPartsCollector(File)

    collector.expectID3Data(ID3FrameHeader.version)
    collector setID3v1MetaData Some(ID3MetaData) shouldBe empty
    collector setMp3MetaData Mp3Data shouldBe empty

  it should "accept ID3 data of other versions" in:
    val id3Collector = prepareID3Collector()
    val collector = new MetaDataPartsCollector(File, id3Collector)
    collector setID3v1MetaData None
    collector.expectID3Data(ID3FrameHeader.version)
    collector setMp3MetaData Mp3Data

    val id3Data = ID3FrameMetaData(header = ID3FrameHeader, Some(ID3MetaData))
    checkMetaData(collector addID3Data id3Data)
    verify(id3Collector).addProvider(id3Data.header.version, ID3MetaData)

  it should "handle multiple expects of the same ID3v2 version" in:
    val collector = new MetaDataPartsCollector(File, prepareID3Collector())
    collector.expectID3Data(ID3FrameHeader.version)
    collector setID3v1MetaData None
    collector.expectID3Data(ID3FrameHeader.version)
    collector setMp3MetaData Mp3Data
    collector.expectID3Data(ID3FrameHeader.version)

    val id3Data = ID3FrameMetaData(header = ID3FrameHeader, Some(ID3MetaData))
    checkMetaData(collector addID3Data id3Data)
