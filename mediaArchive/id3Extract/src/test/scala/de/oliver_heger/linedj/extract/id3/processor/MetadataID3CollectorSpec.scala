/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object MetadataID3CollectorSpec:
  /** A test title. */
  private val Title = "Bat Out Of Hell"

  /** A test artist. */
  private val Artist = "Meat Loaf"

  /** A test album. */
  private val Album = "Bat Out Of Hell I"

  /** Tet inception year. */
  private val InceptionYear = 1977

  /** Test rack number. */
  private val Track = 1

  /**
    * Returns a test tile with the ID3 version encoded.
    *
    * @param version the version
    * @return the test title
    */
  private def title(version: Int): Option[String] = Some(Title + version)

  /**
    * Returns a test artist with the ID3 version encoded.
    *
    * @param version the version
    * @return the test artist
    */
  private def artist(version: Int): Option[String] = Some(Artist + version)

  /**
    * Returns a test album with the ID3 version encoded.
    *
    * @param version the version
    * @return the test album
    */
  private def album(version: Int): Option[String] = Some(Album + version)

  /**
    * Returns a test inception year with the ID3 version encoded.
    *
    * @param version the version
    * @return the test inception year
    */
  private def year(version: Int): Option[Int] = Some(InceptionYear + version)

  /**
    * Returns a test track number with the ID3 version encoded.
    *
    * @param version the version
    * @return the test track number
    */
  private def track(version: Int): Option[Int] = Some(Track + version)

/**
  * Test class for ''MetaDataID3Collector''.
  */
class MetadataID3CollectorSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import MetadataID3CollectorSpec._

  /**
    * Creates a mock tag provider that returns the specified values.
    *
    * @param title  the title
    * @param artist the artist
    * @param album  the album
    * @param year   the inception year
    * @param track  the track number
    * @return the mock provider
    */
  private def createMockProvider(title: Option[String] = None, artist: Option[String] = None,
                                 album: Option[String] = None, year: Option[Int] = None, track:
                                 Option[Int] = None): MetadataProvider =
    val provider = mock[MetadataProvider]
    when(provider.album).thenReturn(album)
    when(provider.artist).thenReturn(artist)
    when(provider.inceptionYearString).thenReturn(year map (_.toString))
    when(provider.title).thenReturn(title)
    when(provider.trackNoString).thenReturn(track map (_.toString))
    provider

  "A MetadataID3Collector" should "return a dummy provider if not data is defined" in:
    val collector = new MetadataID3Collector
    val provider = collector.createCombinedID3TagProvider()

    provider.album shouldBe empty
    provider.artist shouldBe empty
    provider.inceptionYearString shouldBe empty
    provider.title shouldBe empty
    provider.trackNoString shouldBe empty

  it should "return the data from an added provider" in:
    val provider = createMockProvider(title = title(1), artist = artist(1), album = album(1),
      year = year(1), track = track(1))
    val collector = new MetadataID3Collector

    collector.addProvider(1, provider) should be(collector)
    val combinedProvider = collector.createCombinedID3TagProvider()
    combinedProvider.album should be(album(1))
    combinedProvider.artist should be(artist(1))
    combinedProvider.inceptionYear should be(year(1))
    combinedProvider.title should be(title(1))
    combinedProvider.trackNo should be(track(1))

  it should "combine data from multiple providers" in:
    val collector = new MetadataID3Collector
    collector.addProvider(1, createMockProvider(title = title(1), artist = artist(1)))
    collector.addProvider(2, createMockProvider(album = album(2), year = year(2)))
    collector.addProvider(3, createMockProvider(track = track(3)))

    val combinedProvider = collector.createCombinedID3TagProvider()
    combinedProvider.album should be(album(2))
    combinedProvider.artist should be(artist(1))
    combinedProvider.inceptionYear should be(year(2))
    combinedProvider.title should be(title(1))
    combinedProvider.trackNo should be(track(3))

  it should "take ID3 version into account when combining providers" in:
    val collector = new MetadataID3Collector
    collector.addProvider(2, createMockProvider(album = album(2), year = year(2), title = title(2)))
    collector.addProvider(1, createMockProvider(title = title(1), artist = artist(1), year = year
    (1)))
    collector.addProvider(3, createMockProvider(track = track(3), title = title(3)))

    val combinedProvider = collector.createCombinedID3TagProvider()
    combinedProvider.album should be(album(2))
    combinedProvider.artist should be(artist(1))
    combinedProvider.inceptionYear should be(year(2))
    combinedProvider.title should be(title(3))
    combinedProvider.trackNo should be(track(3))
