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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object ArchiveContentActorSpec:
  /** Definitions of some test albums. */
  private val direStraitsSongs = List(
    "Down to the waterline",
    "Water of love",
    "Setting me up",
    "Six blade knife",
    "Southbound again",
    "Sultans of swing"
  )

  private val tubularBellsSongs = List("Part I", "Part II")

  private val crisisSongs = List(
    "Crisis",
    "Moonlight Shadow",
    "In high places",
    "Foreign affair",
    "Taurus",
    "Shadow on the wall"
  )

  private val imaginaerumSongs = List(
    "Taikatalvi",
    "Storytime",
    "Ghost river",
    "Slow, love, slow",
    "I want my tears back",
    "Scaretale"
  )

  /** Assignment of albums to songs. */
  private val albums = Map(
    "Dire Straits" -> direStraitsSongs,
    "Tubular Bells" -> tubularBellsSongs,
    "Crisis" -> crisisSongs,
    "Imaginaerum" -> imaginaerumSongs
  )

  /** Assignment of artists to their albums. */
  private val artistAlbums = Map(
    "Dire Straits" -> List("Dire Straits"),
    "Mike Oldfield" -> List("Tubular Bells", "Crisis"),
    "Nightwish" -> List("Imaginaerum")
  )

  /** A song without an artist and album. */
  private val unassignedSong = MediaMetadata(
    title = Some("unassigned"),
    size = 1,
    checksum = "unassigned-check-sum"
  )

  /**
    * Creates a list with [[MediaMetadata]] objects for the test songs declared
    * in this object. This is used as content for a test medium.
    *
    * @return the list of the [[MediaMetadata]] objects
    */
  private def createSongData(): Iterable[MediaMetadata] =
    artistAlbums.flatMap: (artist, artistAlbums) =>
      artistAlbums.flatMap: album =>
        createSongsForAlbum(artist, album, albums(album))

  /**
    * Creates [[MediaMetadata]] objects representing the songs of a specific
    * album.
    *
    * @param artist the artist
    * @param album  the name of the album
    * @param songs  a list with the songs contained on the album
    * @return a list with metadata about the single songs
    */
  private def createSongsForAlbum(artist: String, album: String, songs: List[String]): List[MediaMetadata] =
    songs.zipWithIndex.map: (song, index) =>
      createMetadata(artist, album, song, index + 1)

  /**
    * Creates a test [[MediaMetadata]] instance for a test song.
    *
    * @param artist the artist
    * @param album  the album
    * @param title  the song title
    * @param track  the track number
    * @return the test [[MediaMetadata]] instance
    */
  private def createMetadata(artist: String, album: String, title: String, track: Int): MediaMetadata =
    MediaMetadata(
      title = Some(title),
      artist = Some(artist),
      album = Some(album),
      trackNumber = Some(track),
      size = title.length * 100,
      checksum = s"$artist:$album:$title"
    )

  /**
    * Creates a test medium details object based in the given index.
    *
    * @param idx the index of the test medium
    * @return a test medium object for this index
    */
  private def createMedium(idx: Int): ArchiveModel.MediumDetails =
    ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(
        id = Checksums.MediumChecksum("id-" + idx),
        title = "Test medium " + idx
      ),
      description = "Description for test medium " + idx,
      orderMode = Some(ArchiveModel.OrderMode.fromOrdinal(idx % ArchiveModel.OrderMode.values.length))
    )

  /**
    * Sends messages to the provided actor to add a test medium with the
    * content defined by the test songs.
    *
    * @param contentActor the content actor
    * @return the ID of the test medium
    */
  private def propagateTestMedium(contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand]):
  Checksums.MediumChecksum =
    val testMedium = createMedium(1)
    contentActor ! ArchiveCommands.UpdateArchiveContentCommand.AddMedium(testMedium)

    def propagateSong(song: MediaMetadata): Unit =
      val addFileCommand = ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
        mediumID = testMedium.id,
        metadata = song
      )
      contentActor ! addFileCommand

    createSongData().foreach(propagateSong)
    propagateSong(unassignedSong)
    testMedium.id
end ArchiveContentActorSpec

/**
  * Test class for [[ArchiveContentActor]].
  */
class ArchiveContentActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with OptionValues:

  import ArchiveContentActorSpec.*

  "ArchiveContentActor" should "return an empty list of media initially" in :
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediaResponse]()
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedia(probe.ref)

    probe.expectMessage(ArchiveCommands.GetMediaResponse(Nil))

  it should "return overview information of the managed media" in :
    val media = (1 to 8) map createMedium
    val expectedOverviews = media.map(_.overview)
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    media.foreach: medium =>
      contentActor ! ArchiveCommands.UpdateArchiveContentCommand.AddMedium(medium)

    val probe = testKit.createTestProbe[ArchiveCommands.GetMediaResponse]()
    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedia(probe.ref)
    val response = probe.expectMessageType[ArchiveCommands.GetMediaResponse]
    response.media should contain theSameElementsAs expectedOverviews

  it should "return detail information about a specific medium" in :
    val medium = createMedium(1)
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    contentActor ! ArchiveCommands.UpdateArchiveContentCommand.AddMedium(medium)

    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumResponse]()
    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedium(medium.id, probe.ref)
    val details = probe.expectMessageType[ArchiveCommands.GetMediumResponse]

    details should be(ArchiveCommands.GetMediumResponse(medium.id, Some(medium)))

  it should "handle a request for the details of a non-existing medium" in :
    val mediumID = Checksums.MediumChecksum("a-non-existing-medium-id")
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumResponse]()
    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedium(mediumID, probe.ref)
    val details = probe.expectMessageType[ArchiveCommands.GetMediumResponse]

    details should be(ArchiveCommands.GetMediumResponse(mediumID, None))

  it should "return an undefined result when querying artists of a non-existing medium" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()

    val artistsRequest = ArchiveCommands.ReadMediumContentCommand.GetArtists(
      Checksums.MediumChecksum("non-existing"),
      probe.ref
    )
    contentActor ! artistsRequest

    val expectedResult = ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo](artistsRequest, None)
    probe.expectMessage(expectedResult)

  it should "return information about the artists of a medium" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()

    val artistsRequest = ArchiveCommands.ReadMediumContentCommand.GetArtists(mediumID, probe.ref)
    contentActor ! artistsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]
    response.request should be(artistsRequest)
    val result = response.optResult.value
    val expectedArtists = (
      artistAlbums.keySet.map: artistName =>
        ArchiveModel.ArtistInfo(MediumContentManager.idFor(Some(artistName), "art"), artistName)
      ) + ArchiveModel.ArtistInfo("art0", "")
    result.map(_.artistName) should contain theSameElementsAs (artistAlbums.keySet + "")

  it should "return information about the albums of a medium" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()

    val artistsRequest = ArchiveCommands.ReadMediumContentCommand.GetAlbums(mediumID, probe.ref)
    contentActor ! artistsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]
    response.request should be(artistsRequest)
    val result = response.optResult.value
    val expectedAlbums = (
      albums.keySet.map: albumName =>
        ArchiveModel.AlbumInfo(MediumContentManager.idFor(Some(albumName), "alb"), albumName)
      ) + ArchiveModel.AlbumInfo("alb0", "")
    result should contain theSameElementsAs expectedAlbums

  it should "return the songs of a specific artist" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()

    val ArtistName = "Mike Oldfield"
    val artistID = MediumContentManager.idFor(Some(ArtistName), "art")
    val songsRequest = ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
      mediumID,
      artistID,
      probe.ref
    )
    contentActor ! songsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]
    response.request should be(songsRequest)
    val result = response.optResult.value

    val albums = artistAlbums(ArtistName)
    val expectedSongs = createSongsForAlbum(ArtistName, albums(1), crisisSongs) ++
      createSongsForAlbum(ArtistName, albums.head, tubularBellsSongs)
    result should contain theSameElementsInOrderAs expectedSongs

  it should "return an undefined result when querying songs of an artist for a non-existing medium" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()

    val songsRequest = ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
      Checksums.MediumChecksum("non-existing"),
      "art_dire_straits",
      probe.ref
    )
    contentActor ! songsRequest

    val expectedResult = ArchiveCommands.GetMediumDataResponse[MediaMetadata](songsRequest, None)
    probe.expectMessage(expectedResult)

  it should "return an undefined result when querying songs of a non-existing artist" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()

    val songsRequest = ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
      mediumID,
      "non-existing-artist",
      probe.ref
    )
    contentActor ! songsRequest

    val expectedResult = ArchiveCommands.GetMediumDataResponse[MediaMetadata](songsRequest, None)
    probe.expectMessage(expectedResult)
