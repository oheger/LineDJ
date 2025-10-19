package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

object MediumContentManagerSpec:
  /**
    * A counter that is incremented on every invocation of the key extractor
    * function. This is used for testing whether data is cached.
    */
  private val extractCount = new AtomicInteger

  /**
    * A test key extractor function that extracts the artist as key.
    */
  private val artistKeyExtractor: MediumContentManager.KeyExtractor = data =>
    extractCount.incrementAndGet()
    data.artist

  /**
    * A data extractor function for the album name of a media file. It returns
    * an empty string if the album is undefined.
    */
  private val albumExtractor: MediumContentManager.DataExtractor[String] = _.album.getOrElse("")

  /** The prefix for artist IDs. */
  private val ArtistIdPrefix = "art"

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
end MediumContentManagerSpec

/**
  * Test class for [[MediumContentManager]].
  */
class MediumContentManagerSpec extends AnyFlatSpec with Matchers with OptionValues:

  import MediumContentManagerSpec.*
  import MediumContentManager.given

  "idFor()" should "return different IDs for different keys" in :
    val id1 = MediumContentManager.idFor(Some("foo"), ArtistIdPrefix)
    val id2 = MediumContentManager.idFor(Some("bar"), ArtistIdPrefix)

    id1 should not be id2

  it should "return the same IDs for strings differing only in case" in :
    val id1 = MediumContentManager.idFor(Some("foo"), ArtistIdPrefix)
    val id2 = MediumContentManager.idFor(Some("FOo"), ArtistIdPrefix)

    id1 should be(id2)

  it should "return IDs starting with the passed in ID prefix" in :
    val id = MediumContentManager.idFor(Some("a-test-value"), ArtistIdPrefix)

    id should startWith(ArtistIdPrefix + "_")

  it should "return IDs that do not contain space characters" in :
    val id = MediumContentManager.idFor(Some("this is a   test    value "), ArtistIdPrefix)

    id should not include " "

  it should "return a reserved ID for undefined input" in :
    val id = MediumContentManager.idFor(None, ArtistIdPrefix)

    id should be(s"${ArtistIdPrefix}0")

  "A MediumContentManager" should "be empty initially" in :
    val manager = new MediumContentManager(artistKeyExtractor, albumExtractor, ArtistIdPrefix)

    manager.keyMapping shouldBe empty

  it should "construct the managed data" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms"),
      createMetadata(artist = Some("Dire Straits"), album = "Love over Gold"),
      createMetadata(artist = Some("Marillion"), album = "Misplaced Childhood"),
      createMetadata(artist = Some("Dire Straits"), album = "Communique")
    )
    val manager = new MediumContentManager(artistKeyExtractor, albumExtractor, ArtistIdPrefix)

    manager.update(songData)
    val albums = manager(s"${ArtistIdPrefix}_dire_straits").value

    albums should contain theSameElementsInOrderAs List("Brothers in Arms", "Communique", "Love over Gold")

  it should "construct a correct key mapping" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms"),
      createMetadata(artist = Some("dire Straits"), album = "Love over Gold"),
      createMetadata(artist = Some("Supertramp"), album = "Even in the quitest moments"),
      createMetadata(artist = None, album = "Album of unknown artist")
    )

    val manager = new MediumContentManager(artistKeyExtractor, albumExtractor, ArtistIdPrefix)
    manager.update(songData)

    manager.keyMapping should have size 3
    manager.keyMapping(s"${ArtistIdPrefix}_dire_straits") should be("Dire Straits")
    manager.keyMapping(s"${ArtistIdPrefix}_supertramp") should be("Supertramp")
    manager.keyMapping(s"${ArtistIdPrefix}0") should be("")

  it should "handle data from undefined keys" in :
    val songData = List(
      createMetadata(artist = Some("Supertramp"), album = "Even in the quitest moments"),
      createMetadata(artist = None, album = "Album 2 of unknown artist"),
      createMetadata(artist = None, album = "Album 1 of unknown artist")
    )
    val manager = new MediumContentManager(artistKeyExtractor, albumExtractor, ArtistIdPrefix)
    manager.update(songData)

    val albums = manager(s"${ArtistIdPrefix}0").value

    albums should contain theSameElementsInOrderAs List("Album 1 of unknown artist", "Album 2 of unknown artist")

  it should "return an undefined option for an unknown ID" in :
    val manager = new MediumContentManager(artistKeyExtractor, albumExtractor, ArtistIdPrefix)
    manager.update(List(createMetadata(Some("artist"), "album")))

    manager("undefinedID") shouldBe empty

  it should "cache the mapping after its construction" in :
    val songData = List(
      createMetadata(artist = Some("Dire Straits"), album = "Brothers in Arms"),
      createMetadata(artist = Some("dire Straits"), album = "Love over Gold"),
      createMetadata(artist = Some("Supertramp"), album = "Even in the quitest moments"),
      createMetadata(artist = Some("Metallica"), album = "Right the Lightning"),
      createMetadata(artist = Some("Metallica"), album = "Garage Inc.")
    )

    val manager = new MediumContentManager(artistKeyExtractor, albumExtractor, ArtistIdPrefix)
    manager.update(songData)
    val metallicaID = manager.keyMapping.find(_._2 == "Metallica").map(_._1).value
    manager(metallicaID).value should have size 2

    val extracts = extractCount.get()
    extracts should be > 0
    manager(s"${ArtistIdPrefix}_supertramp").value should have size 1
    extractCount.get() should be(extracts)

  it should "provide an Ordering for metadata" in :
    val Artist = Some("Dire Straits")
    val Album = "Brothers in Arms"
    val songData = List(
      createMetadata(artist = Artist, album = Album, title = Some("So far away"), trackNo = Some(1)),
      createMetadata(artist = Artist, album = Album, title = Some("Money for nothing"), trackNo = Some(2)),
      createMetadata(artist = Some("Supertramp"), album = "Even in the quitest moments"),
      createMetadata(artist = Artist, album = Album, title = Some("Your latest trick"), trackNo = Some(4)),
      createMetadata(artist = Artist, album = Album, title = Some("Walk of life"), trackNo = Some(3))
    )

    val manager = new MediumContentManager(artistKeyExtractor, MediumContentManager.MetadataExtractor, ArtistIdPrefix)
    manager.update(songData)
    val artistID = manager.keyMapping.find(_._2 == Artist.get).map(_._1).value
    val songs = manager(artistID).value

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
