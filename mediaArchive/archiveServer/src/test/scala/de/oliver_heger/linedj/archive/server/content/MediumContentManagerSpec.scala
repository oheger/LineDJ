package de.oliver_heger.linedj.archive.server.content

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
    * @param artist the optional artist
    * @param album  the album
    * @return the [[MediaMetadata]] with this data
    */
  private def createMetadata(artist: Option[String], album: String): MediaMetadata =
    MediaMetadata(artist = artist, album = Some(album), size = 0, checksum = "")
end MediumContentManagerSpec

/**
  * Test class for [[MediumContentManager]].
  */
class MediumContentManagerSpec extends AnyFlatSpec with Matchers with OptionValues:

  import MediumContentManagerSpec.*

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
