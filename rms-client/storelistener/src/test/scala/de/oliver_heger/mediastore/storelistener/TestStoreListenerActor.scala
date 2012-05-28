package de.oliver_heger.mediastore.storelistener

import scala.actors.Actor

import org.easymock.EasyMock.{eq => matchEq}
import org.easymock.IAnswer
import org.easymock.EasyMock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import de.oliver_heger.mediastore.localstore.MediaStore
import de.oliver_heger.mediastore.service.SongData
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.PlayerShutdown
import de.oliver_heger.splaya.PlaylistData
import de.oliver_heger.splaya.PlaylistUpdate
import de.oliver_heger.tsthlp.TestActorSupport

/**
 * Test class for ''StoreListenerActor''.
 */
class TestStoreListenerActor extends JUnitSuite with EasyMockSugar
  with TestActorSupport {
  /** Constant for a song name. */
  private val Title = "Money for Nothing"

  /** Constant for an artist. */
  private val Artist = "Dire Straits"

  /** Constant for an album. */
  private val Album = "Borthers in Arms"

  /** Constant for the track number. */
  private val Track = 2

  /** Constant for the inception year. */
  private val Year = 1980

  /** Constant for the duration. */
  private val Duration = 510000L

  /** Constant for the site of the playlist. */
  private val PlaylistSize = 122

  /** Constant for the index of the test song in the playlist. */
  private val Index = 42

  /** A mock for the media store. */
  private var store: MediaStore = _

  /** The actor to be tested. */
  protected var actor: Actor = _

  @Before def setUp() {
    store = mock[MediaStore]
    actor = new StoreListenerActor(store)
    actor.start()
  }

  /**
   * Creates a mock ''AudioSourceData'' object with data for the test song.
   * @return the mock data object
   */
  private def createSourceData(): AudioSourceData = {
    val data = mock[AudioSourceData]
    EasyMock.expect(data.albumName).andReturn(Album).anyTimes()
    EasyMock.expect(data.artistName).andReturn(Artist).anyTimes()
    EasyMock.expect(data.duration).andReturn(Duration).anyTimes()
    EasyMock.expect(data.inceptionYear).andReturn(Year).anyTimes()
    EasyMock.expect(data.title).andReturn(Title).anyTimes()
    EasyMock.expect(data.trackNo).andReturn(Track).anyTimes()
    EasyMock.replay(data)
    data
  }

  /**
   * Creates a mock ''PlaylistData'' object. The size of the playlist is already
   * defined. Further functionality has to be added manually.
   * @return the mock playlist data object
   */
  private def createPlaylistData(): PlaylistData = {
    val pd = mock[PlaylistData]
    EasyMock.expect(pd.size).andReturn(PlaylistSize).anyTimes()
    pd
  }

  /**
   * Creates a test ''AudioSource'' for the specified playlist index.
   * @param idx the playlist index
   * @return the corresponding ''AudioSource'' object
   */
  private def createAudioSource(idx: Int) =
    AudioSource(index = idx, uri = "TestURL", length = 20120526205132L,
      skip = 0, skipTime = 0)

  /**
   * Prepares the mock for the media store to expect an update operation.
   * @param playCount the number of times the song was played
   */
  private def expectMediaStoreUpdate(playCount: Int = 1) {
    store.updateSongData(EasyMock.anyObject(), matchEq(playCount))
    EasyMock.expectLastCall().andAnswer(new IAnswer[Unit]() {
      def answer() {
        val data = EasyMock.getCurrentArguments()(0).asInstanceOf[SongData]
        checkSongData(data)
      }
    })
  }

  /**
   * Tests whether the specified song data object contains the expected
   * information.
   * @param data the data object to be checked
   */
  private def checkSongData(data: SongData) {
    assertEquals("Wrong album name", Album, data.getAlbumName())
    assertEquals("Wrong artist name", Artist, data.getArtistName())
    assertEquals("Wrong duration", Duration / 1000, data.getDuration().longValue())
    assertEquals("Wrong title", Title, data.getName())
    assertEquals("Wrong track", Track, data.getTrackNo().intValue())
    assertEquals("Wrong year", Year, data.getInceptionYear().intValue())
  }

  /**
   * Tests whether an audio source is stored in the local media store.
   */
  @Test def testAudioSourceStored() {
    val plData = createPlaylistData()
    val src = createAudioSource(Index)
    EasyMock.expect(plData.getAudioSourceData(Index)).andReturn(createSourceData())
    expectMediaStoreUpdate()
    whenExecuting(store, plData) {
      actor ! plData
      actor ! PlaylistUpdate(plData, Index)
      actor ! PlaybackSourceEnd(src, false)
      shutdownActor()
    }
  }

  /**
   * Tests whether a song that has been played multiple times is handled
   * correctly.
   */
  @Test def testAudioSourceStoredPlayCount() {
    val plData = createPlaylistData()
    val src = createAudioSource(Index)
    EasyMock.expect(plData.getAudioSourceData(Index)).andReturn(createSourceData())
    expectMediaStoreUpdate(2)
    whenExecuting(store, plData) {
      actor ! plData
      actor ! PlaybackSourceEnd(src, false)
      actor ! PlaybackSourceEnd(src, false)
      actor ! PlaylistUpdate(plData, Index)
      shutdownActor()
    }
  }

  /**
   * Tests whether internal counters are reset after a source has been stored.
   * If the source is played a second time, play count should be 1 again.
   */
  @Test def testAudioSourcePlayedMultipleTimes() {
    val plData = createPlaylistData()
    val src = createAudioSource(Index)
    EasyMock.expect(plData.getAudioSourceData(Index))
      .andReturn(createSourceData()).times(2)
    expectMediaStoreUpdate()
    expectMediaStoreUpdate()
    whenExecuting(store, plData) {
      actor ! plData
      actor ! PlaybackSourceEnd(src, false)
      actor ! PlaylistUpdate(plData, Index)
      actor ! PlaybackSourceEnd(src, false)
      shutdownActor()
    }
  }

  /**
   * Tests that the actor ignores messages if no current playlist is set (this
   * should not happen in practice, but to be on the safe side...).
   */
  @Test def testNoPlaylistData() {
    val plData = createPlaylistData()
    val src = createAudioSource(Index)
    whenExecuting(store, plData) {
      actor ! PlaylistUpdate(plData, Index)
      actor ! PlaybackSourceEnd(src, false)
      shutdownActor()
    }
  }

  /**
   * Tests whether source played messages for sources are ignored if no media
   * data is available for them.
   */
  @Test def testOtherSources() {
    val plData = createPlaylistData()
    whenExecuting(store, plData) {
      actor ! plData
      actor ! PlaylistUpdate(plData, Index)
      for (i <- 0 until PlaylistSize if i != Index) {
        actor ! PlaybackSourceEnd(createAudioSource(i), false)
      }
      shutdownActor()
    }
  }

  /**
   * Tests whether a source is ignored if its playback was skipped.
   */
  @Test def testSourceSkipped() {
    val plData = createPlaylistData()
    val src = createAudioSource(Index)
    whenExecuting(store, plData) {
      actor ! plData
      actor ! PlaylistUpdate(plData, Index)
      actor ! PlaybackSourceEnd(src, true)
      shutdownActor()
    }
  }

  /**
   * Tests whether the actor terminates on an PlayerExited message.
   */
  @Test def testPlayerExited() {
    val plData = createPlaylistData()
    val src = createAudioSource(Index)
    whenExecuting(store, plData) {
      actor ! plData
      actor ! PlayerShutdown
      actor ! PlaylistUpdate(plData, Index)
      actor ! PlaybackSourceEnd(src, false)
    }
    actor = null
  }

  /**
   * Tests whether default values for audio meta data are treated correctly.
   */
  @Test def testCreateSongDataDefaultValues() {
    val srcData = niceMock[AudioSourceData]
    EasyMock.replay(srcData)
    val data = actor.asInstanceOf[StoreListenerActor].createSongData(srcData)
    assertNull("Got a title", data.getName())
    assertNull("Got an artist", data.getArtistName())
    assertNull("Got an album", data.getAlbumName())
    assertNull("Got a duration", data.getDuration())
    assertNull("Got an inception year", data.getInceptionYear())
    assertNull("Got a track number", data.getTrackNo())
  }
}
