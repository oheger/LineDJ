package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import de.oliver_heger.splaya.playlist.AudioSourceDataExtractor
import de.oliver_heger.splaya.AudioSourceData
import org.junit.Before
import org.junit.After
import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.splaya.tsthlp.WaitForExit
import org.junit.Test
import org.easymock.EasyMock
import de.oliver_heger.splaya.tsthlp.ActorTestImpl

/**
 * Test class for ''AudioSourceDataExtractorActor''.
 */
class TestAudioSourceDataExtractorActor extends JUnitSuite with EasyMockSugar {
  /** Constant for an URI.*/
  private val URI = "audio://TestMusic.la"

  /** Constant for a playlist ID. */
  private val PlaylistID = 20120418214432L

  /** A mock for the extractor. */
  private var extractor: AudioSourceDataExtractor = _

  /** The actor to be tested. */
  private var actor: AudioSourceDataExtractorActor = _

  @Before def setUp() {
    extractor = mock[AudioSourceDataExtractor]
    actor = new AudioSourceDataExtractorActor(extractor)
    actor.start()
  }

  @After def tearDown() {
    if(actor != null) {
      actor ! Exit
    }
  }

  /**
   * Sends the test actor an Exit message and waits until its completion.
   */
  private def shutdownActor() {
    val msg = new WaitForExit
    if(!msg.shutdownActor(actor)) {
      fail("Actor did not exit!")
    }
    actor = null
  }

  /**
   * Tests whether a request for extracting audio data is processed correctly.
   */
  @Test def testExtractData() {
    val data = mock[AudioSourceData]
    val index = 42
    EasyMock.expect(extractor.extractAudioSourceData(URI)).andReturn(Some(data))
    val rec = new ActorTestImpl
    val msg = ExtractSourceDataRequest(PlaylistID, URI, index, rec)
    whenExecuting(data, extractor) {
      actor ! msg
      shutdownActor()
    }
    rec.expectMessage(ExtractSourceDataResult(PlaylistID, index, Some(data)))
  }
}
