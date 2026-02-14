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

import de.oliver_heger.linedj.archive.server.model
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object MediumContentManagerActorSpec:
  /** ID for a test medium. */
  private val TestMediumID = Checksums.MediumChecksum("test-medium")

  /**
    * A counter that is incremented on every invocation of the key extractor
    * function. This is used for testing whether data is cached.
    */
  private val extractCount = new AtomicInteger

  /**
    * A test key extractor function that extracts the artist as key.
    */
  private val artistKeyExtractor: MediumContentManagerActor.KeyExtractor = data =>
    extractCount.incrementAndGet()
    data.artist

  /**
    * A data extractor function for artist information of a media file. This is
    * used to test basic data access.
    */
  private val artistExtractor: MediumContentManagerActor.DataExtractor[ArchiveModel.ArtistInfo] = (id, data) =>
    ArchiveModel.ArtistInfo(id, data.artist.getOrElse(""))

  /** The prefix for artist IDs. */
  private val ArtistIdPrefix = "art"

  /** The prefix for album IDs. */
  private val AlbumIdPrefix = "alb"

  /**
    * A default function to generate IDs that is passed to ID manager actors to
    * have a deterministic ID calculation.
    */
  private val idCalcFunc: IdManagerActor.IdCalculatorFunc = key =>
    key.toLowerCase(Locale.ROOT).replace(' ', '_')

  /**
    * Generates the ID for an artist given the name.
    *
    * @param name the artist name
    * @return the ID of this artist
    */
  private def calcArtistID(name: String): String = s"${ArtistIdPrefix}_${idCalcFunc(name)}"

  /**
    * Generates the ID for a given optional album name.
    *
    * @param optName the optional album name
    * @return the ID of this album
    */
  private def calcAlbumID(optName: Option[String]): String =
    optName.map(name => s"${AlbumIdPrefix}_${idCalcFunc(name)}").getOrElse(s"${AlbumIdPrefix}0")

  /**
    * Convenience function to create a [[MediaMetadata]] instance with an
    * optional artist and album.
    *
    * @param artist  the optional artist
    * @param album   the album
    * @param title   an optional song title
    * @param trackNo an optional track number
    * @return the [[MediaMetadata]] with this data
    */
  private def createMetadata(artist: Option[String],
                             album: String,
                             title: Option[String] = None,
                             trackNo: Option[Int] = None): MediaMetadata =
    MediaMetadata(
      artist = artist,
      album = Some(album),
      title = title,
      trackNumber = trackNo,
      size = 0,
      checksum = ""
    )

  /**
    * Appends an ID to a name using a specific separator character. This
    * transformation is used by the test data extractor function.
    *
    * @param id   the ID to be appended
    * @param name the original name
    * @return the name with the ID appended
    */
  private def concatID(id: String)(name: String): String = s"$name|$id"

  /**
    * Concatenates a given ID to all elements of a list.
    *
    * @param id   the ID to be appended
    * @param list the list
    * @return the transformed list
    */
  private def concatID(id: String, list: List[String]): List[String] =
    list.map(concatID(id))

  /**
    * Create a request to query artist information for the test medium.
    *
    * @param probe the test probe to receive the response
    * @return the request for artist information
    */
  private def createArtistsRequest(probe: TestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]):
  MediumContentManagerActor.MediumContentManagerCommand[ArchiveModel.ArtistInfo] =
    MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      MediumContentManagerActor.AggregateGroupingKey,
      ArchiveCommands.ReadMediumContentCommand.GetArtists(TestMediumID, probe.ref),
      probe.ref
    )
end MediumContentManagerActorSpec

class MediumContentManagerActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with OptionValues:

  import MediumContentManagerActor.given
  import MediumContentManagerActorSpec.*

  /**
    * Creates a default ID manager actor that can be used by test cases. The
    * actor is configured with default settings.
    *
    * @return the standard ID manager actor for tests
    */
  private def createIdManager(): ActorRef[IdManagerActor.QueryIdCommand] =
    testKit.spawn(IdManagerActor.newInstance(ArtistIdPrefix, idCalcFunc))

  /**
    * Creates a test actor instance with the given parameters.
    *
    * @param optIdManager an optional ID manager actor
    * @return the test actor instance
    */
  private def createTestActor(optIdManager: Option[ActorRef[IdManagerActor.QueryIdCommand]] = None):
  ActorRef[MediumContentManagerActor.MediumContentManagerCommand[ArchiveModel.ArtistInfo]] =
    val idManager = optIdManager.getOrElse(testKit.spawn(IdManagerActor.newInstance(ArtistIdPrefix, idCalcFunc)))
    testKit.spawn(
      MediumContentManagerActor.newInstance(
        artistKeyExtractor,
        artistExtractor,
        idManager
      )
    )

  "A MediumContentManagerActor" should "construct the managed data" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms", title = Some("So far away")),
      createMetadata(artist = Some("Dire Straits"), album = "Love over Gold", title = Some("Telegraph Road")),
      createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood"),
      createMetadata(artist = Some("Dire Straits"), album = "Communique", title = Some("Once upon a time in the west"))
    )
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = MediumContentManagerActor.MetadataExtractor,
        idManager = createIdManager()
      )
    )
    val artistID = calcArtistID("Dire Straits")
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()
    val request = ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
      TestMediumID,
      artistID,
      probe.ref
    )

    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      artistID,
      request,
      probe.ref
    )

    val songs = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]
    val expectedSongs = List(songData.head, songData(1), songData(3))
    songs.optResult.value should contain theSameElementsAs expectedSongs
    songs.request should be(request)

  it should "return an undefined result for an invalid ID" in :
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = MediumContentManagerActor.MetadataExtractor,
        idManager = createIdManager()
      )
    )
    val artistID = calcArtistID("Dire Straits")
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()
    val request = ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
      TestMediumID,
      artistID,
      probe.ref
    )

    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      artistID,
      request,
      probe.ref
    )

    val songs = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]
    songs.request should be(request)
    songs.optResult shouldBe empty

  it should "support an aggregate grouping function" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms", title = Some("So far away")),
      createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood"),
      createMetadata(
        artist = Some("Supertramp"),
        album = "Even in the quitest moments",
        title = Some("Fools overture")
      ),
      createMetadata(artist = Some("Dire Straits"), album = "Love over Gold", title = Some("Telegraph Road")),
      createMetadata(artist = None, "unknown", title = Some("Song without artist"))
    )
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = artistExtractor,
        idManager = createIdManager(),
        groupingFunc = MediumContentManagerActor.AggregateGroupingFunc
      )
    )
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()
    val request = ArchiveCommands.ReadMediumContentCommand.GetArtists(TestMediumID, probe.ref)

    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      MediumContentManagerActor.AggregateGroupingKey,
      request,
      probe.ref
    )

    val artistResponse = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]
    artistResponse.request should be(request)
    val unknownArtist = ArchiveModel.ArtistInfo(ArtistIdPrefix + "0", "")
    val expectedArtists = unknownArtist :: List("Dire Straits", "Marillion", "Supertramp").map: name =>
      ArchiveModel.ArtistInfo(calcArtistID(name), name)
    artistResponse.optResult.value should contain theSameElementsAs expectedArtists

  it should "cache the generated view" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms", title = Some("So far away")),
      createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood", title = Some("Childhood ends")),
      createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood", title = Some("Kayleigh")),
      createMetadata(artist = Some("Dire Straits"), album = "Communique", title = Some("Once upon a time in the west"))
    )
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = MediumContentManagerActor.MetadataExtractor,
        idManager = createIdManager()
      )
    )
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)

    val artist1ID = calcArtistID("Dire Straits")
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      artist1ID,
      ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
        TestMediumID,
        artist1ID,
        probe.ref
      ),
      probe.ref
    )
    probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]

    val extracts = extractCount.get()
    extracts should be > 0
    val artist2ID = calcArtistID("Marillion")
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      artist1ID,
      ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
        TestMediumID,
        artist2ID,
        probe.ref
      ),
      probe.ref
    )
    probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]

    extractCount.get() should be(extracts)

  it should "reconstruct the view after an update" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms", title = Some("So far away")),
      createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood"),
      createMetadata(artist = Some("Dire Straits"), album = "Love over Gold", title = Some("Telegraph Road")),
      createMetadata(artist = None, "unknown", title = Some("Song without artist"))
    )
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = artistExtractor,
        idManager = createIdManager(),
        groupingFunc = MediumContentManagerActor.AggregateGroupingFunc
      )
    )
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()
    val dataRequest = createArtistsRequest(probe)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)
    managerActor ! dataRequest
    probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]

    val newSongData = createMetadata(
      artist = Some("Supertramp"),
      album = "Even in the quitest moments",
      title = Some("Fools overture")
    ) :: songData
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(newSongData)
    managerActor ! dataRequest
    val artistResult = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]

    artistResult.optResult.value should contain(ArchiveModel.ArtistInfo(calcArtistID("Supertramp"), "Supertramp"))

  it should "not construct the mapping twice under concurrent access" in :
    val Artist = "Dire Straits"
    val artistID = calcArtistID(Artist)
    val songData = List(
      createMetadata(artist = Some(Artist), album = "Brothers in Arms", title = Some("Brothers in Arms"))
    )
    val probeIdManager = testKit.createTestProbe[IdManagerActor.QueryIdCommand]()
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = artistExtractor,
        idManager = probeIdManager.ref,
        groupingFunc = MediumContentManagerActor.AggregateGroupingFunc
      )
    )
    val probeClient1 = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()
    val dataRequest1 = createArtistsRequest(probeClient1)
    val probeClient2 = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()
    val dataRequest2 = createArtistsRequest(probeClient2)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)

    managerActor ! dataRequest1
    managerActor ! dataRequest2

    val idsRequest = probeIdManager.expectMessageType[IdManagerActor.QueryIdCommand.GetIds]
    probeIdManager.expectNoMessage(100.millis)
    idsRequest.replyTo ! IdManagerActor.GetIdsResponse(Map(Some(Artist) -> artistID))
    val expectedArtistInfos = List(ArchiveModel.ArtistInfo(artistID, Artist))
    val result1 = probeClient1.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]
    val result2 = probeClient2.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]
    result1.optResult.value should contain theSameElementsAs expectedArtistInfos
    result2.optResult.value should contain theSameElementsAs expectedArtistInfos

  it should "handle updates while constructing the mapping" in :
    val Artist1 = "Dire Straits"
    val artist1ID = calcArtistID(Artist1)
    val Artist2 = "Alon Parsons"
    val artist2ID = calcArtistID(Artist2)
    val song1 = createMetadata(artist = Some(Artist1), album = "Communique", title = Some("Lady writer"))
    val song2 = createMetadata(
      artist = Some(Artist2),
      album = "Tails of mystery and imagination",
      title = Some("The raven")
    )
    val probeIdManager = testKit.createTestProbe[IdManagerActor.QueryIdCommand]()
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = artistExtractor,
        idManager = probeIdManager.ref,
        groupingFunc = MediumContentManagerActor.AggregateGroupingFunc
      )
    )
    val probeClient = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()
    val dataRequest = createArtistsRequest(probeClient)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(List(song1))

    managerActor ! dataRequest
    val idsRequest1 = probeIdManager.expectMessageType[IdManagerActor.QueryIdCommand.GetIds]
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(List(song1, song2))
    val idsRequest2 = probeIdManager.expectMessageType[IdManagerActor.QueryIdCommand.GetIds]
    idsRequest1.replyTo ! IdManagerActor.GetIdsResponse(Map.empty)
    val ids: Map[IdManagerActor.EntityName, String] = Map(
      Some(Artist1) -> artist1ID,
      Some(Artist2) -> artist2ID
    )
    idsRequest2.replyTo ! IdManagerActor.GetIdsResponse(ids)

    val response = probeClient.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]
    val expectedArtistInfos = List(
      ArchiveModel.ArtistInfo(artist1ID, Artist1),
      ArchiveModel.ArtistInfo(artist2ID, Artist2)
    )
    response.optResult.value should contain theSameElementsAs expectedArtistInfos

  it should "clear pending requests after they have been processed" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms", title = Some("So far away")),
    )
    val probeIdManager = testKit.createTestProbe[IdManagerActor.QueryIdCommand]()
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = artistExtractor,
        idManager = probeIdManager.ref,
        groupingFunc = MediumContentManagerActor.AggregateGroupingFunc
      )
    )
    val probeClient = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]()
    val dataRequest = createArtistsRequest(probeClient)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)

    managerActor ! dataRequest
    val idRequest = probeIdManager.expectMessageType[IdManagerActor.QueryIdCommand.GetIds]
    idRequest.replyTo ! IdManagerActor.GetIdsResponse(Map(Some("Dire Straits") -> "someID"))
    probeClient.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.ArtistInfo]]
    val nextSongData = createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood") :: songData
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(nextSongData)

    probeIdManager.expectNoMessage(100.millis)

  it should "support a transformer function" in :
    val transformerFunc: MediumContentManagerActor.DataTransformer[Option[String], ArchiveModel.AlbumInfo] = mapping =>
      val transformedMapping = mapping.map: (key, albums) =>
        val albumInfos = albums.map: album =>
          val albumID = calcAlbumID(album)
          val albumName = album.getOrElse("")
          ArchiveModel.AlbumInfo(albumID, albumName)
        (key, albumInfos)
      Future.successful(transformedMapping)
    val dataExtractorFunc: MediumContentManagerActor.DataExtractor[Option[String]] = (_, metadata) => metadata.album
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms", title = Some("So far away")),
      createMetadata(artist = Some("Dire Straits"), album = "Love over Gold", title = Some("Telegraph Road")),
      createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood"),
      createMetadata(artist = Some("Dire Straits"), album = "Communique", title = Some("Once upon a time in the west"))
    )
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newTransformingInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = dataExtractorFunc,
        transformer = transformerFunc,
        idManager = createIdManager()
      )
    )
    val artistID = calcArtistID("Dire Straits")
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]()
    val request = ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(
      TestMediumID,
      artistID,
      probe.ref
    )

    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      artistID,
      request,
      probe.ref
    )

    val result = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[ArchiveModel.AlbumInfo]]
    result.request should be(request)
    val expectedAlbumInfos = List("Brothers in Arms", "Communique", "Love over Gold").map: albumName =>
      ArchiveModel.AlbumInfo(calcAlbumID(Some(albumName)), albumName)
    result.optResult.value should contain theSameElementsInOrderAs expectedAlbumInfos

  it should "provide an ordering for MediaMetadata" in :
    val Artist = Some("Dire Straits")
    val Album = "Brothers in Arms"
    val songData = List(
      createMetadata(artist = Artist, album = Album, title = Some("So far away"), trackNo = Some(1)),
      createMetadata(artist = Artist, album = Album, title = Some("Money for nothing"), trackNo = Some(2)),
      createMetadata(artist = Some("Supertramp"), album = "Even in the quitest moments"),
      createMetadata(artist = Artist, album = Album, title = Some("Your latest trick"), trackNo = Some(4)),
      createMetadata(artist = Artist, album = Album, title = Some("Walk of life"), trackNo = Some(3))
    )
    val managerActor = testKit.spawn(
      MediumContentManagerActor.newInstance(
        keyExtractor = artistKeyExtractor,
        dataExtractor = MediumContentManagerActor.MetadataExtractor,
        idManager = createIdManager()
      )
    )
    val artistID = calcArtistID("Dire Straits")
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumDataResponse[MediaMetadata]]()
    val request = ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
      TestMediumID,
      artistID,
      probe.ref
    )

    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.UpdateData(songData)
    managerActor ! MediumContentManagerActor.MediumContentManagerCommand.GetDataFor(
      artistID,
      request,
      probe.ref
    )

    val songs = probe.expectMessageType[ArchiveCommands.GetMediumDataResponse[MediaMetadata]].optResult.value
    val expectedSongs = List(
      songData.head,
      songData(1),
      songData(4),
      songData(3)
    )
    songs should contain theSameElementsInOrderAs expectedSongs

  "MediaMetadataOrdering" should "sort songs by their albums first" in :
    val Artist = Some("Dire Straits")
    val Album = "brothers in arms"
    val songData = List(
      createMetadata(artist = Artist, album = Album, title = Some("So far away"), trackNo = Some(1)),
      createMetadata(artist = Artist, album = Album, title = Some("Money for nothing"), trackNo = Some(2)),
      createMetadata(
        artist = Some("Supertramp"),
        album = "Even in the quitest moments",
        title = Some("Give a little bit"),
        trackNo = Some(1)
      ),
      createMetadata(artist = Artist, album = Album, title = Some("Your latest trick"), trackNo = Some(4)),
      createMetadata(artist = Artist, album = Album, title = Some("Walk of life"), trackNo = Some(3))
    )

    val sortedSongs = songData.sorted

    val expectedSongs = List(
      songData.head,
      songData(1),
      songData(4),
      songData(3),
      songData(2)
    )
    sortedSongs should contain theSameElementsInOrderAs expectedSongs

  it should "sort songs on an album by its track numbers and titles" in :
    val songWithNoAlbum = createMetadata(artist = None, album = "", title = Some("No album"), trackNo = Some(17))
      .copy(album = None)
    val Artist = Some("Dire Straits")
    val Album = "Brothers in Arms"
    val track1 = createMetadata(artist = Artist, album = Album, title = Some("So far away"), trackNo = Some(1))
    val track2 = createMetadata(artist = Artist, album = Album, title = Some("Money for nothing"), trackNo = Some(2))
    val track3 = createMetadata(artist = Artist, album = Album, title = Some("Walk of life"), trackNo = Some(3))
    val track01 = createMetadata(artist = Artist, album = Album, title = Some("08 One world"))
    val track02 = createMetadata(artist = Artist, album = Album, title = Some("09 Brothers in arms"))
    val track03 = createMetadata(artist = Artist, album = Album, title = Some("ride across the river"))
    val track04 = createMetadata(artist = Artist, album = Album, title = Some("The man's too big"))
    val songData = List(
      track2,
      track02,
      track3,
      songWithNoAlbum,
      track03,
      track1,
      track04,
      track01
    )

    val sortedSongs = songData.sorted

    val expectedSongs = List(
      songWithNoAlbum,
      track01,
      track02,
      track03,
      track04,
      track1,
      track2,
      track3
    )
    sortedSongs should contain theSameElementsInOrderAs expectedSongs

  "ArtistInfoOrdering" should "sort objects based on the artist name" in :
    val artistDireStraits = ArchiveModel.ArtistInfo("art1", "Dire Straits")
    val artistSupertramp = ArchiveModel.ArtistInfo("art2", "Supertramp")
    val artistOldfield = ArchiveModel.ArtistInfo("art3", "mike oldfield")
    val artistNightwish = ArchiveModel.ArtistInfo("art4", "nightwish")
    val infos = List(
      artistNightwish,
      artistSupertramp,
      artistOldfield,
      artistDireStraits
    )

    val sortedInfos = infos.sorted

    val expectedInfos = List(
      artistDireStraits,
      artistOldfield,
      artistNightwish,
      artistSupertramp
    )
    sortedInfos should contain theSameElementsInOrderAs expectedInfos

  "AlbumInfoOrdering" should "sort objects based on the album name" in :
    val albumDireStraits = ArchiveModel.AlbumInfo("alb1", "Dire Straits")
    val albumLoveOverGold = ArchiveModel.AlbumInfo("alb2", "love over gold")
    val albumBrothersInArms = ArchiveModel.AlbumInfo("alb3", "brothers in arms")
    val albumMakingMovies = ArchiveModel.AlbumInfo("alb4", "Making movies")

    val infos = List(
      albumDireStraits,
      albumBrothersInArms,
      albumMakingMovies,
      albumLoveOverGold
    )

    val sortedInfos = infos.sorted

    val expectedInfos = List(
      albumBrothersInArms,
      albumDireStraits,
      albumLoveOverGold,
      albumMakingMovies
    )
    sortedInfos should contain theSameElementsInOrderAs expectedInfos
  