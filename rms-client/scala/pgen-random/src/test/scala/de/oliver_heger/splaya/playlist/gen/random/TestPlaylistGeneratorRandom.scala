package de.oliver_heger.splaya.playlist.gen.random

import org.scalatest.junit.JUnitSuite
import org.junit.Before
import org.junit.Test
import org.junit.Assert._

/**
 * Test class for ''PlaylistGeneratorRandom''.
 */
class TestPlaylistGeneratorRandom extends JUnitSuite {
  /** Constant for the name of a test song. */
  private val TestSong = "file://testSong_"

  /** Constant for the number of test songs. */
  private val SongCount = 32

  /** The generator to be tested. */
  private var gen: PlaylistGeneratorRandom = _

  @Before def setUp() {
    gen = new PlaylistGeneratorRandom
  }

  /**
   * Creates an ordered sequence of test songs.
   * @return the sequence with test songs
   */
  private def createSongs() =
    for (i <- 1 to SongCount) yield TestSong + i

  /**
   * Extracts the index of a test song.
   */
  private def extractIndex(song: String): Int = {
    val pos = song.lastIndexOf('_')
    song.substring(pos + 1).toInt
  }

  /**
   * Checks whether the specified playlist is in random order. Result is true
   * if at least on element is out of its expected order.
   * @param playlist the playlist to be checked
   * @param idx the current index
   * @return a flag whether the playlist is in random order
   */
  private def isRandomOrder(playlist: Seq[String], idx: Int): Boolean = {
    if (playlist.isEmpty) false
    else {
      if (extractIndex(playlist.head) != idx) true
      else isRandomOrder(playlist.tail, idx + 1)
    }
  }

  /**
   * Tests whether the generated playlist is in random order.
   */
  @Test def testRandomOrder() {
    var attempts = 10
    var foundPermutation = false
    while (!foundPermutation && attempts > 0) {
      val playlist = gen.generatePlaylist(createSongs(), "random",
        xml.NodeSeq.Empty)
      assert(SongCount === playlist.size)
      foundPermutation = isRandomOrder(playlist, 1)
      attempts -= 1
    }
    assertTrue("No random order", foundPermutation)
  }

  /**
   * Tests whether keep groups are taken into account.
   */
  @Test def testKeepGroups() {
    def assertGroup(it: Iterator[String], indices: Int*) {
      for (i <- indices) {
        assert(i === extractIndex(it.next()))
      }
    }

    val params = <keep>
                   <file name={ TestSong + 5 }/>
                   <file name={ TestSong + 6 }/>
                 </keep>
                 <otherGroup>Test</otherGroup>
                 <keep>
                   <file name={ TestSong + 1 }/>
                   <otherTag unknown="yes">some content</otherTag>
                   <file name={ TestSong + 12 }/>
                   <file name={ TestSong + 3 }/>
                 </keep>;

    val playlist = gen.generatePlaylist(createSongs(), "", params)
    val iterator = playlist.toIterator
    while (iterator.hasNext) {
      val song = iterator.next()
      val idx = extractIndex(song)
      if (idx == 5) {
        assertGroup(iterator, 6)
      } else if (idx == 1) {
        assertGroup(iterator, 12, 3)
      }
    }
  }
}
