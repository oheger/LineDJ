package de.oliver_heger.splaya.playlist.impl

import org.easymock.EasyMock
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.MediaDataExtractor
import de.oliver_heger.tsthlp.ActorTestImpl
import de.oliver_heger.tsthlp.TestActorSupport

/**
 * Test class for ''AudioSourceDataExtractorActor''.
 */
class TestAudioSourceDataExtractorActor extends JUnitSuite with EasyMockSugar
  with TestActorSupport {
  /** The actor type to be tested. */
  type ActorUnderTest = AudioSourceDataExtractorActor

  /** Constant for a medium root URI. */
  private val RootURI = "file://d/sounds"

  /** Constant for an URI.*/
  private val URI = "TestMusic.la"

  /** Constant for a playlist ID. */
  private val PlaylistID = 20120418214432L

  /** A mock for the extractor. */
  private var extractor: AudioSourceDataExtractor = _

  /** The actor to be tested. */
  protected var actor: ActorUnderTest = _

  @Before def setUp() {
    extractor = mock[AudioSourceDataExtractor]
    actor = new AudioSourceDataExtractorActor(extractor)
    actor.start()
  }

  /**
   * Tests whether a request for extracting audio data is processed correctly.
   */
  @Test def testExtractData() {
    val data = mock[AudioSourceData]
    val index = 42
    EasyMock.expect(extractor.extractAudioSourceData(RootURI, URI))
      .andReturn(Some(data))
    val rec = new ActorTestImpl
    val msg = ExtractSourceDataRequest(PlaylistID, RootURI, URI, index, rec)
    whenExecuting(data, extractor) {
      actor ! msg
      shutdownActor()
    }
    rec.expectMessage(ExtractSourceDataResult(PlaylistID, index, Some(data)))
  }

  /**
   * Tests whether a media data extractor can be added.
   */
  @Test def testAddMediaDataExtractor() {
    val dataExtr = mock[MediaDataExtractor]
    extractor.addMediaDataExtractor(dataExtr)
    whenExecuting(extractor, dataExtr) {
      actor ! AddMediaDataExtractor(dataExtr)
      shutdownActor()
    }
  }

  /**
   * Tests whether a media data extractor can be removed.
   */
  @Test def testRemoveMediaDataExtractor() {
    val dataExtr = mock[MediaDataExtractor]
    extractor.removeMediaDataExtractor(dataExtr)
    whenExecuting(extractor, dataExtr) {
      actor ! RemoveMediaDataExtractor(dataExtr)
      shutdownActor()
    }
  }
}
