package de.oliver_heger.splaya.playlist

import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.matchers.ShouldMatchers

import scala.xml.NodeSeq

/**
 * Test class for ''PlaylistGeneratorRandomUriPart''.
 */
class TestPlaylistGeneratorRandomUriPart extends JUnitSuite with ShouldMatchers {
  @Test def testRandomOrder(): Unit = {
    val list = List("a/b/song1.mp3", "a/b/song2.mp3", "a/b/song3.mp3")
    val TestCount = 8
    var randomOrder = false

    val generator = new GeneratorImpl
    for (i <- 1 to TestCount) {
      val playlist = generator.generatePlaylist(list, "some mode", NodeSeq.Empty)
      playlist.length should be(list.length)
      playlist.forall(list.contains) should be(right = true)
      if (playlist != list) {
        randomOrder = true
      }
    }
    randomOrder should be(right = true)
  }

  /**
   * Tests the creation of a playlist that contains only songs in the top-level
   * directory. The default behavior is that all these songs are assigned to
   * the same path component and thus the resulting list should be ordered.
   */
  @Test def testSortedOrderAndHandlingOfShortSongNames(): Unit = {
    val list = List("song3.mp3", "song1.mp3", "song2.mp3")
    val expected = List("song1.mp3", "song2.mp3", "song3.mp3")
    val generator = new GeneratorImpl

    generator.generatePlaylist(list, "", NodeSeq.Empty) should be(expected)
  }

  /**
   * A test implementation of the playlist generator. This implementation
   * returns always the last part of the URI meaning that a playlist in full
   * random order has to be produced.
   */
  private class GeneratorImpl extends PlaylistGeneratorRandomUriPart {
    override protected def extractUriPart(parts: Array[String]): String = parts.last
  }

}
