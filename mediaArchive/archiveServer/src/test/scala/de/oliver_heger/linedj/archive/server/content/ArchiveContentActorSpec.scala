/*
 * Copyright 2015-2026 The Developers Team.
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
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.Inspectors.forEvery
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.Locale

object ArchiveContentActorSpec:
  /** The name of the archive used by tests. */
  private val ArchiveName = "TestArchive"

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
    * Generates an ID for a media file with the given metadata.
    *
    * @param artist the artist
    * @param album  the album
    * @param title  the title
    * @return the ID of this file
    */
  private def createFileID(artist: String, album: String, title: String): String = s"$artist:$album:$title"

  /**
    * Generates the URI of a media file based on the ID of the file.
    *
    * @param fileID the file ID
    * @return the URI of this file
    */
  private def createFileUri(fileID: String): MediaFileUri = MediaFileUri(s"https://$fileID")

  /**
    * Generates the URI of a media file based on its metadata.
    *
    * @param song the metadata of the file
    * @return the URI of this file
    */
  private def createFileUri(song: MediaMetadata): MediaFileUri = createFileUri(song.checksum)

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
      checksum = createFileID(artist, album, title)
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
      orderMode = Some(ArchiveModel.OrderMode.fromOrdinal(idx % ArchiveModel.OrderMode.values.length)),
      archiveName = ArchiveName
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
        fileUri = createFileUri(song),
        metadata = song
      )
      contentActor ! addFileCommand

    createSongData().foreach(propagateSong)
    propagateSong(unassignedSong)
    testMedium.id

  /**
    * Calculates the ID of an entity in the same way as this is done by the
    * corresponding ID manager.
    *
    * @param prefix the ID prefix of this entity type
    * @param name   the name of the entity
    * @return the ID of this entity
    */
  private def calcID(prefix: String, name: String): String =
    prefix + "_" + IdManagerActor.HashIdCalculatorFunc(name.toLowerCase(Locale.ROOT))

  /**
    * Calculates the ID of an artist in the same way as this is done by the ID
    * manager for artists.
    *
    * @param artist the artist name
    * @return the ID of this artist
    */
  private def calcArtistID(artist: String): String = calcID("art", artist)

  /**
    * Calculates the ID of an album in the same way as this is done by the ID
    * manager for albums.
    *
    * @param album the album name
    * @return the ID of this album
    */
  private def calcAlbumID(album: String): String = calcID("alb", album)

  /**
    * Transforms the given list of artist names to [[ArchiveModel.ArtistInfo]]
    * objects by calculating the hashed IDs.
    *
    * @param names the artist names
    * @return the corresponding info objects
    */
  private def createArtistInfos(names: Iterable[String]): Iterable[ArchiveModel.ArtistInfo] =
    names.map: artist =>
      ArchiveModel.ArtistInfo(calcArtistID(artist), artist)

  /**
    * Transforms the given list of album names to [[ArchiveModel.AlbumInfo]]
    * objects by calculating the hashed IDs.
    *
    * @param names the album names
    * @return the corresponding info objects
    */
  private def createAlbumInfos(names: Iterable[String]): Iterable[ArchiveModel.AlbumInfo] =
    names.map: album =>
      ArchiveModel.AlbumInfo(calcAlbumID(album), album)

  /**
    * Returns a factory for a file actor that propagates incoming messages to
    * the given probe actor reference.
    *
    * @param probe the probe
    * @return the factory for the media file actor
    */
  private def fileActorFactory(probe: ActorRef[MediaFileActor.MediaFileCommand]): MediaFileActor.Factory =
    () => Behaviors.monitor(probe, Behaviors.ignore)
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
    val expectedArtists = ArchiveModel.ArtistInfo("art0", "") :: createArtistInfos(artistAlbums.keySet).toList
    result should contain theSameElementsAs expectedArtists

  it should "deduplicate artist information of a medium" in :
    val altArtistName = "dire straits"
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val song = createMetadata(
      artist = altArtistName,
      album = "Dire Straits",
      title = "In the gallery",
      track = 5
    )
    val direStraitsID = calcArtistID(altArtistName)
    val fileUri = createFileUri(song)
    val addFileCommand = ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
      mediumID = mediumID,
      fileUri = fileUri,
      metadata = song
    )
    contentActor ! addFileCommand
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()

    val artistsRequest = ArchiveCommands.ReadMediumContentCommand.GetArtists(mediumID, probe.ref)
    contentActor ! artistsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]
    response.request should be(artistsRequest)
    val result = response.optResult.value
    result should have size artistAlbums.size + 1
    result.find(_.id == direStraitsID).value.artistName.toLowerCase(Locale.ROOT) should be(altArtistName)

  it should "return information about the albums of a medium" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()

    val artistsRequest = ArchiveCommands.ReadMediumContentCommand.GetAlbums(mediumID, probe.ref)
    contentActor ! artistsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]
    response.request should be(artistsRequest)
    val result = response.optResult.value
    val expectedAlbums = ArchiveModel.AlbumInfo("alb0", "") :: createAlbumInfos(albums.keySet).toList
    result should contain theSameElementsAs expectedAlbums

  it should "deduplicate album information of a medium" in :
    val altAlbumName = "dire straits"
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val song = createMetadata(
      artist = "Dire Straits",
      album = altAlbumName,
      title = "In the gallery",
      track = 5
    )
    val direStraitsID = calcAlbumID(altAlbumName)
    val fileUri = createFileUri(song)
    val addFileCommand = ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
      mediumID = mediumID,
      fileUri = fileUri,
      metadata = song
    )
    contentActor ! addFileCommand
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()

    val artistsRequest = ArchiveCommands.ReadMediumContentCommand.GetAlbums(mediumID, probe.ref)
    contentActor ! artistsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]
    response.request should be(artistsRequest)
    val result = response.optResult.value
    result should have size albums.size + 1
    result.find(_.id == direStraitsID).value.albumName.toLowerCase(Locale.ROOT) should be(altAlbumName)

  it should "return the songs of a specific artist" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()

    val ArtistName = "Mike Oldfield"
    val artistID = calcArtistID(ArtistName)
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

  it should "return the songs of a specific album" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()

    val ArtistName = "Nightwish"
    val albumName = artistAlbums(ArtistName).head
    val albumID = calcAlbumID(albumName)
    val songsRequest = ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(
      mediumID,
      albumID,
      probe.ref
    )
    contentActor ! songsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]
    response.request should be(songsRequest)
    val result = response.optResult.value

    val expectedSongs = createSongsForAlbum(ArtistName, albumName, imaginaerumSongs)
    result should contain theSameElementsInOrderAs expectedSongs

  it should "return an undefined result when querying songs of an album for a non-existing medium" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()

    val songsRequest = ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(
      Checksums.MediumChecksum("non-existing"),
      "alb_dire_straits",
      probe.ref
    )
    contentActor ! songsRequest

    val expectedResult = ArchiveCommands.GetMediumDataResponse[MediaMetadata](songsRequest, None)
    probe.expectMessage(expectedResult)

  it should "return an undefined result when querying songs of a non-existing album" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()

    val songsRequest = ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(
      mediumID,
      "non-existing-album",
      probe.ref
    )
    contentActor ! songsRequest

    val expectedResult = ArchiveCommands.GetMediumDataResponse[MediaMetadata](songsRequest, None)
    probe.expectMessage(expectedResult)

  it should "return the albums of a specific artist" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()

    forEvery(artistAlbums): (artist, albums) =>
      val albumsRequest = ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(
        mediumID,
        calcArtistID(artist),
        probe.ref
      )
      contentActor ! albumsRequest

      val expectedResult = createAlbumInfos(albums.sorted)
      val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]
      response.request should be(albumsRequest)
      response.optResult.value should contain theSameElementsInOrderAs expectedResult

  it should "deduplicate the albums of a specific artist" in :
    val altAlbumName = "dire straits"
    val ArtistName = "Dire Straits"
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val song = createMetadata(
      artist = ArtistName,
      album = altAlbumName,
      title = "In the gallery",
      track = 5
    )
    val direStraitsID = calcAlbumID(altAlbumName)
    val fileUri = createFileUri(song)
    val addFileCommand = ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
      mediumID = mediumID,
      fileUri = fileUri,
      metadata = song
    )
    contentActor ! addFileCommand
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()

    val albumsRequest = ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(
      mediumID,
      calcArtistID(ArtistName),
      probe.ref
    )
    contentActor ! albumsRequest

    val response = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]
    val expectedAlbums = createAlbumInfos(artistAlbums(ArtistName).sorted)
    response.optResult.value should contain theSameElementsInOrderAs expectedAlbums

  it should "return an undefined result when querying the albums of an artist of a non-existing medium" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()

    val albumRequest = ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(
      Checksums.MediumChecksum("non-existing"),
      "some_artist",
      probe.ref
    )
    contentActor ! albumRequest

    val expectedResult = ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo](albumRequest, None)
    probe.expectMessage(expectedResult)

  it should "return an undefined result when querying the albums of a non-existing artist" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    val mediumID = propagateTestMedium(contentActor)
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()

    val albumRequest = ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(
      mediumID,
      "non-existing-artist",
      probe.ref
    )
    contentActor ! albumRequest

    val expectedResult = ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo](albumRequest, None)
    probe.expectMessage(expectedResult)

  it should "forward incoming messages about media files to the file actor" in :
    val testMedium = createMedium(17)
    val song = createMetadata("someArtist", "someAlbum", "someTitle", 3)
    val probeFileActor = testKit.createTestProbe[MediaFileActor.MediaFileCommand]()
    val fileUri = MediaFileUri(s"${testMedium.title}/some/song.mp3")
    val addFileCommand = ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
      mediumID = testMedium.id,
      fileUri = fileUri,
      metadata = song
    )

    val contentActor = testKit.spawn(ArchiveContentActor.behavior(fileActorFactory(probeFileActor.ref)))
    contentActor ! addFileCommand

    val expectedFileCommand = MediaFileActor.MediaFileCommand.AddFile(
      mediumID = testMedium.id,
      fileUri = fileUri,
      metadata = song
    )
    probeFileActor.expectMessage(expectedFileCommand)

  it should "forward a request for a media file to the file actor" in :
    val fileID = "some-media-file-id"
    val probeFileActor = testKit.createTestProbe[MediaFileActor.MediaFileCommand]()
    val probeClient = testKit.createTestProbe[ArchiveCommands.GetFileResponse[ArchiveModel.MediaFileInfo]]()

    val contentActor = testKit.spawn(ArchiveContentActor.behavior(fileActorFactory(probeFileActor.ref)))
    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetFileInfo(fileID, probeClient.ref)

    val expectedCommand = MediaFileActor.MediaFileCommand.GetFileInfo(fileID, probeClient.ref)
    probeFileActor.expectMessage(expectedCommand)

  it should "handle a command to obtain download information for a file" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    propagateTestMedium(contentActor)
    val fileID = "Mike Oldfield:Crisis:Moonlight Shadow"
    val probeClient = testKit.createTestProbe[ArchiveCommands.GetFileResponse[ArchiveModel.MediaFileDownloadInfo]]()

    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetFileDownloadInfo(fileID, probeClient.ref)

    val response = probeClient.expectMessageType[ArchiveCommands.GetFileResponse[ArchiveModel.MediaFileDownloadInfo]]
    response.fileID should be(fileID)
    val info = response.optResult.value
    info.fileUri should be(createFileUri(fileID))
    info.archiveName should be(ArchiveName)

  it should "return an undefined file download result if querying a non-existing file ID" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    propagateTestMedium(contentActor)
    val fileID = "non-existing-file"
    val probeClient = testKit.createTestProbe[ArchiveCommands.GetFileResponse[ArchiveModel.MediaFileDownloadInfo]]()

    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetFileDownloadInfo(fileID, probeClient.ref)

    val response = probeClient.expectMessageType[ArchiveCommands.GetFileResponse[ArchiveModel.MediaFileDownloadInfo]]
    response.fileID should be(fileID)
    response.optResult shouldBe empty

  it should "return an undefined file download result if the ID cannot be assigned to a medium" in :
    val song = createMetadata("testArtist", "testAlbum", "testTile", 1)
    val addFileCommand = ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
      mediumID = Checksums.MediumChecksum("unknown-medium-id"),
      fileUri = createFileUri(song),
      metadata = song
    )
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    contentActor ! addFileCommand
    val probeClient = testKit.createTestProbe[ArchiveCommands.GetFileResponse[ArchiveModel.MediaFileDownloadInfo]]()

    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetFileDownloadInfo(song.checksum, probeClient.ref)

    val response = probeClient.expectMessageType[ArchiveCommands.GetFileResponse[ArchiveModel.MediaFileDownloadInfo]]
    response.fileID should be(song.checksum)
    response.optResult shouldBe empty
