package de.oliver_heger.splaya.playlist.gen.randomart

import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.matchers.ShouldMatchers

import scala.xml.NodeSeq

/**
 * Test class for ''PlaylistGeneratorRandomArtist''.
 */
class TestPlaylistGeneratorRandomArtist extends JUnitSuite with ShouldMatchers {
  /**
   * Tests whether the artist name is correctly extracted.
   */
  @Test def testExtractArtistName(): Unit = {
    val generator = new PlaylistGeneratorRandomArtistTestImpl
    val parts = Array("Veruca Salt", "2006 - IV", "06 - Closer.mp2")
    generator.extractUriPart(parts) should be(parts(0))
  }

  /**
   * Tests that songs without an artist are handled correctly and sorted in
   * random order.
   */
  @Test def testSongsWithoutArtistAppearInRandomOrder(): Unit = {
    val TestCount = 8
    val songList = List("someSong.mp3", "someOtherSong.mp3", "anotherSong.mp3", "yetAnotherSong" +
      ".mp3")
    val playlistSet = collection.mutable.Set.empty[Seq[String]]
    val generator = new PlaylistGeneratorRandomArtist

    for (i <- 1 to TestCount) {
      playlistSet add generator.generatePlaylist(songList, "mode", NodeSeq.Empty)
    }
    playlistSet.size should be > 1
  }

  /**
   * Test implementation of the playlist generator.
   */
  private class PlaylistGeneratorRandomArtistTestImpl extends PlaylistGeneratorRandomArtist {
    /**
     * @inheritdoc This is overridden to make it visible for the test class.
     */
    override def extractUriPart(parts: Array[String]): String = super.extractUriPart(parts)
  }

}
