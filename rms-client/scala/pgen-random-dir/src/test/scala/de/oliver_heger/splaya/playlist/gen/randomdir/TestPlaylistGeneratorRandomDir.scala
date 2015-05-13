package de.oliver_heger.splaya.playlist.gen.randomdir

import org.junit.Assert._
import org.junit.Test
import org.scalatest.junit.JUnitSuite

import scala.xml.NodeSeq

object TestPlaylistGeneratorRandomDir {
  /** Constant for a prefix for directories of all test songs. */
  private val SongPrefix = "Dir1/anotherDir/"

  /** Constant for the name of an album directory. */
  private val AlbumDir = "testAlbum"

  /** An array defining the number of songs on the test albums. */
  private val AlbumLengths = Array(4, 2, 3, 5)

  /**
   * Generates the name of a test song.
   * @param albumIdx the index of the album the song belongs to
   * @param songIdx the index of the song in its album
   * @return the name of this song
   */
  private def songName(albumIdx: Int, songIdx: Int): String =
    SongPrefix + AlbumDir + albumIdx + "/testSong" + songIdx + ".mp3"

  /**
   * Generates a list with the songs contained on the album with the given
   * index.
   * @param albumIndex the album index
   * @return a list with the songs on this album
   */
  private def orderedAlbum(albumIndex: Int): List[String] =
    (for (i <- 0 until AlbumLengths(albumIndex)) yield songName(albumIndex, i)).toList

  /**
   * Returns an unordered initial list with test songs. This list serves as
   * input for the playlist generator.
   * @return the initial, unordered song list
   */
  private def initialSongList(): Seq[String] = {
    List(songName(1, 0), songName(2, 2), songName(0, 1), songName(3, 4),
      songName(1, 1), songName(2, 0), songName(3, 0), songName(3, 3), songName(3, 2),
      songName(2, 1), songName(0, 3), songName(0, 2), songName(3, 1),
      songName(0, 0))
  }

  /**
   * Extracts the index of an album from a song name.
   * @param song the song name
   * @return the album index
   */
  private def extractAlbumIndex(song: String): Int =
    song(SongPrefix.length + AlbumDir.length) - '0'

  /**
   * Determines the order of the test albums from the given song list. The
   * resulting list contains the indices of the test albums in the order in
   * which they appear in the song list.
   * @param songs the song list
   * @return the list with album indices
   */
  private def albumIndexSeq(songs: Seq[String]): Seq[Int] = {
    val reverseList = (songs map extractAlbumIndex).foldLeft(List.empty[Int]) { (list, idx) =>
      if (list.nonEmpty && list.head == idx) list
      else idx :: list
    }
    reverseList.reverse
  }

  /**
   * Generates the final expected song list based on the given index sequence.
   * @param indexSeq the index sequence
   * @return the expected song list
   */
  private def orderedSongList(indexSeq: Seq[Int]): List[String] =
    indexSeq.map(orderedAlbum).flatten.toList
}

/**
 * Test class for ''PlaylistGeneratorRandomDir''.
 */
class TestPlaylistGeneratorRandomDir extends JUnitSuite {

  import TestPlaylistGeneratorRandomDir._

  /**
   * Tests whether a playlist is correctly generated.
   */
  @Test def testPlaylistGeneration(): Unit = {
    val initialList = initialSongList()
    assertEquals("Wrong length of initial list", AlbumLengths.sum, initialList.length)

    val generator = new PlaylistGeneratorRandomDir
    val randomList = generator.generatePlaylist(initialList, "unspecified", NodeSeq.Empty)
    val Count = 8
    var randomOrder = false
    for (i <- 1 to Count) {
      val list = generator.generatePlaylist(initialList, "random_directories", NodeSeq.Empty)
      val checkedList = orderedSongList(albumIndexSeq(list))
      assertEquals("Wrong ordered list at run " + i, checkedList, list)
      if (list != randomList) {
        randomOrder = true
      }
    }

    assertTrue("No random order produced", randomOrder)
  }

  /**
   * Tests whether songs without an album can be processed.
   */
  @Test def testSongWithoutAlbum(): Unit = {
    val generator = new PlaylistGeneratorRandomDir
    val initialList = List("song1.mp3", "song0.mp3", songName(1, 0))
    val expResult1 = List("song0.mp3", "song1.mp3", songName(1, 0))
    val expResult2 = List(songName(1, 0), "song0.mp3", "song1.mp3")

    val randomList = generator.generatePlaylist(initialList, "", NodeSeq.Empty)
    assertTrue("Wrong result", randomList == expResult1 || randomList == expResult2)
  }
}
