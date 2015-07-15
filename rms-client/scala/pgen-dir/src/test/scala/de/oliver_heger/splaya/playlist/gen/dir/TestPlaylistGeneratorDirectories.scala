package de.oliver_heger.splaya.playlist.gen.dir

import org.scalatest.junit.JUnitSuite
import org.junit.Assert._
import org.junit.Before
import org.junit.Test

/**
 * Test class for ''PlaylistGeneratorDirectories''.
 */
class TestPlaylistGeneratorDirectories extends JUnitSuite {
  /** The generator to be tested. */
  private var gen: PlaylistGeneratorDirectories = _

  @Before def setUp() {
    gen = new PlaylistGeneratorDirectories
  }

  /**
   * Tests whether a playlist can be generated.
   */
  @Test def testGeneratePlaylist() {
    val songs = Array("ACDC/Thunder/01 - Thunderstrike",
      "zZ Top/Texas/03 - LeGrange",
      "Abba/Music/02 - DancingQueen",
      "acdc/Thunder/02 - TNT",
      "ABBA/Music/01 - Waterloo",
      "AcDc/HighwayToHell/09 - Dirty Deeds",
      "OMD/Pandorras Box/01 - Sailing on the 7 seas")
    val expectedIndices = Array(4, 2, 5, 0, 3, 6, 1)
    val playlist = gen.generatePlaylist(songs, "directories",
      scala.xml.NodeSeq.Empty)
    for (i <- 0 until songs.length) {
      assertEquals("Wrong song at " + i, songs(expectedIndices(i)), playlist(i))
    }
  }
}
