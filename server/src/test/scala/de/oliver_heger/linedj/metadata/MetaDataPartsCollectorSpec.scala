/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.linedj.metadata

import de.oliver_heger.linedj.mp3.{ID3Header, ID3TagProvider}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object MetaDataPartsCollectorSpec {
  /** Stub for an ID3 tag provider. */
  private val ID3MetaData = new ID3TagProvider {
    override val inceptionYearString = Some("1969")

    override val album = Some("Led Zeppelin II")

    override val trackNoString = Some("3")

    override val artist = Some("Led Zeppelin")

    override val title = Some("The Lemon Song")
  }

  /** A test MP3 meta data object. */
  private val Mp3Data = Mp3MetaData(path = null, version = 0, layer = 0, sampleRate = 0,
    minimumBitRat = 0, maximumBitRate = 128000, duration = 60000)

  /** The expected final meta data. */
  private val MetaData = MediaMetaData(title = ID3MetaData.title, artist = ID3MetaData.artist,
    album = ID3MetaData.album, inceptionYear = ID3MetaData.inceptionYear, trackNumber =
      ID3MetaData.trackNo, duration = Some(Mp3Data.duration), formatDescription = Some("128 kbps"))
}

/**
 * Test class for ''MetaDataPartsCollector''.
 */
class MetaDataPartsCollectorSpec extends FlatSpec with Matchers with MockitoSugar {

  import MetaDataPartsCollectorSpec._

  /**
   * Checks whether the correct media data has been generated.
   * @param metaData the option with meta data returned by the collector
   * @return the verified meta data
   */
  private def checkMetaData(metaData: Option[MediaMetaData]): MediaMetaData = {
    metaData should be(Some(MetaData))
    MetaData
  }

  "A MetaDataPartsCollector" should "create a default ID3 collector" in {
    val collector = new MetaDataPartsCollector
    collector.id3Collector shouldBe a[MetaDataID3Collector]
  }

  it should "allow adding undefined meta data" in {
    val collector = new MetaDataPartsCollector

    collector setMp3MetaData Mp3Data shouldBe 'empty
  }

  it should "allow adding undefined ID3v1 meta data" in {
    val id3Collector = mock[MetaDataID3Collector]
    val collector = new MetaDataPartsCollector(id3Collector)

    collector setID3v1MetaData None shouldBe 'empty
    verifyZeroInteractions(id3Collector)
  }

  it should "pass defined ID3v1 meta data to the ID3 collector" in {
    val id3Collector = mock[MetaDataID3Collector]
    val collector = new MetaDataPartsCollector(id3Collector)

    collector setID3v1MetaData Some(ID3MetaData) shouldBe 'empty
    verify(id3Collector).addProvider(1, ID3MetaData)
  }

  /**
   * Creates a mock ID3 collector and prepares it to return the test ID3 meta
   * data.
   * @return the mock collector
   */
  private def prepareID3Collector(): MetaDataID3Collector = {
    val id3Collector = mock[MetaDataID3Collector]
    when(id3Collector.createCombinedID3TagProvider()).thenReturn(ID3MetaData)
    id3Collector
  }

  it should "generate meta data when MP3 data is available and ID3v1 data is added" in {
    val id3Collector = prepareID3Collector()
    val collector = new MetaDataPartsCollector(id3Collector)
    collector setMp3MetaData Mp3Data

    checkMetaData(collector setID3v1MetaData Some(ID3MetaData))
  }

  it should "generate meta data when ID3v1 data is available and MP3 data is added" in {
    val id3Collector = prepareID3Collector()
    val collector = new MetaDataPartsCollector(id3Collector)
    collector setID3v1MetaData Some(ID3MetaData)


    checkMetaData(collector setMp3MetaData Mp3Data)
  }

  it should "detect missing ID3v2 information" in {
    val collector = new MetaDataPartsCollector

    collector.expectID3Data()
    collector setID3v1MetaData Some(ID3MetaData) shouldBe 'empty
    collector setMp3MetaData Mp3Data shouldBe 'empty
  }

  it should "accept ID3 data of other versions" in {
    val id3Collector = prepareID3Collector()
    val collector = new MetaDataPartsCollector(id3Collector)
    collector setID3v1MetaData None
    collector.expectID3Data()
    collector setMp3MetaData Mp3Data

    val id3Data = ID3FrameMetaData(path = null, frameHeader = ID3Header(3, 42), Some(ID3MetaData))
    checkMetaData(collector addID3Data id3Data)
    verify(id3Collector).addProvider(id3Data.frameHeader.version, ID3MetaData)
  }
}
