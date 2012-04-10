package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import de.oliver_heger.splaya.AudioSourceData
import org.easymock.EasyMock
import de.oliver_heger.splaya.PlaylistSettings
import org.junit.Before
import org.junit.Test
import org.junit.Assert._

/**
 * Test class for ''PlaylistDataImpl''.
 */
class TestPlaylistDataImpl extends JUnitSuite with EasyMockSugar {
  /** Constant for the number of items in the playlist. */
  private val PlaylistSize = 32

  /** Constant for an URI prefix for playlist items. */
  private val URIPrefix = "file://audioSource"

  /** Constant for a song title prefix. */
  private val TitlePrefix = "TestSong"

  /** Constant for the start index. */
  private val startIdx = 8

  /** A mock for the playlist settings. */
  private var settings: PlaylistSettings = _

  /** The object to be tested. */
  private var data: PlaylistDataImpl = _

  @Before def setUp() {
    settings = mock[PlaylistSettings]
    EasyMock.replay(settings)
    data = PlaylistDataImpl(settings, startIdx, createPlaylist(),
      createSourceDataArray())
  }

  /**
   * Generates the URI of the playlist item at the given index.
   * @param idx the index
   * @return the URI of the item at this index
   */
  private def uri(idx: Int) = URIPrefix + idx

  /**
   * Generates the song title of the playlist item at the given index.
   * @param idx the index
   * @return the song title of this playlist item
   */
  private def title(idx: Int) = TitlePrefix + idx

  /**
   * Creates a test playlist.
   * @return the sequence with the test playlist
   */
  private def createPlaylist(): Seq[String] =
    for (i <- 0 until PlaylistSize) yield uri(i)

  /**
   * Creates a source data object for the playlist item at the given index.
   * @param idx the index
   * @return the corresponding source data object
   */
  private def createSourceData(idx: Int): AudioSourceData = {
    val data = mock[AudioSourceData]
    EasyMock.expect(data.title).andReturn(title(idx)).anyTimes()
    EasyMock.expect(data.trackNo).andReturn(idx).anyTimes()
    EasyMock.replay(data)
    data
  }

  /**
   * Creates an array with source data objects for the playlist item. Only
   * elements with even indices are defined; for odd indices no source data
   * objects are available.
   */
  private def createSourceDataArray(): Array[AudioSourceData] = {
    val srcDataArray = new Array[AudioSourceData](PlaylistSize)
    for (i <- 0 until PlaylistSize / 2) {
      srcDataArray(2 * i) = createSourceData(2 * i)
    }
    srcDataArray
  }

  /**
   * Tests whether the playlist settings can be queried.
   */
  @Test def testSettings() {
    assert(settings === data.settings)
  }

  /**
   * Tests whether the correct start index is returned.
   */
  @Test def testStartIndex() {
    assert(startIdx === data.startIndex)
  }

  /**
   * Tests whether an audio source data object can be queried if it is defined.
   */
  @Test def testGetAudioDataDefined() {
    val srcData = data.getAudioSourceData(2)
    assert(title(2) === srcData.title)
    assert(2 === srcData.trackNo)
  }

  /**
   * Tests whether an audio source data object can be queried if it is
   * undefined.
   */
  @Test def testGetAudioDataUndefined() {
    val srcData = data.getAudioSourceData(1)
    assert(uri(1) === srcData.title)
    assert(0 === srcData.trackNo)
    assert(0 === srcData.duration)
    assert(0 === srcData.inceptionYear)
    assertNull("Got an artist", srcData.artistName)
    assertNull("Got an album", srcData.albumName)
  }

  /**
   * Tests whether a copy of the array with audio sources is created.
   */
  @Test def testAudioSourceArrayDefensiveCopy() {
    val array = createSourceDataArray()
    data = PlaylistDataImpl(settings, 0, createPlaylist(), array)
    array(1) = createSourceData(1)
    assert(uri(1) === data.getAudioSourceData(1).title)
  }

  /**
   * Tests whether the URIs of the playlist can be queried.
   */
  @Test def testGetURI() {
    var idx = 0
    for (uri <- createPlaylist()) {
      assert(uri === data.getURI(idx))
      idx += 1
    }
  }

  /**
   * Tests whether the playlist size can be queried.
   */
  @Test def testSize() {
    assert(PlaylistSize === data.size)
  }
}
