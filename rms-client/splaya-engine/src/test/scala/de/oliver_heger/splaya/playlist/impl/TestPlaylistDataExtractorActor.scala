package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import de.oliver_heger.splaya.tsthlp.ActorTestImpl
import org.junit.Before
import org.junit.After
import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.splaya.tsthlp.WaitForExit
import org.scalatest.mock.EasyMockSugar
import de.oliver_heger.splaya.PlaylistData
import de.oliver_heger.splaya.PlaylistSettings
import de.oliver_heger.splaya.AudioSourceData
import org.junit.Test
import org.junit.Assert._
import de.oliver_heger.splaya.tsthlp.QueuingActor
import scala.actors.Actor
import de.oliver_heger.splaya.engine.msg.Gateway
import org.junit.BeforeClass
import de.oliver_heger.splaya.PlaylistUpdate
import de.oliver_heger.splaya.engine.msg.AccessSourceMedium

/**
 * Test class for ''PlaylistDataExtractorActor''.
 */
class TestPlaylistDataExtractorActor extends JUnitSuite with EasyMockSugar {
  /** Constant for a prefix for a playlist URI. */
  private val URI = "file:///music/song"

  /** Constant for a prefix for a title of a song in the playlist. */
  private val Title = "TestSong No "

  /** Constant for the number of songs in the test playlist. */
  private val PlaylistSize = 3

  /** The mock actor for doing the extraction. */
  private var extractor: ActorTestImpl = _

  /** The actor to be tested. */
  private var actor: PlaylistDataExtractorActor = _

  @After def tearDown() {
    if (actor != null) {
      shutdownActor()
    }
  }

  /**
   * Sends an Exit message to the test actor and waits until it is processed.
   */
  private def shutdownActor() {
    var msg = new WaitForExit
    if (!msg.shutdownActor(actor)) {
      fail("Actor did not exit!")
    }
    actor = null
  }

  /**
   * Creates a mock for the extractor actor which sends default results for
   * extraction requests.
   * @return the mock actor
   */
  private def createMockExtractor(): QueuingActor = {
    val extr = new QueuingActor({
      case req: ExtractSourceDataRequest =>
        req.sender ! ExtractSourceDataResult(req.playlistID, req.index,
          Some(createSourceData(req.index)))
    })
    extr.start()
    extr
  }

  /**
   * Creates and starts a test actor instance which uses the specified extractor
   * actor.
   * @param extractor the actor to be used as extractor
   * @return the test actor instance
   */
  private def setUpActor(extractor: Actor): PlaylistDataExtractorActor = {
    actor = new PlaylistDataExtractorActor(extractor)
    actor.start()
    actor
  }

  /**
   * Creates an event listener actor and registers it at the Gateway.
   * @return the listener actor
   */
  private def installListener(): QueuingActor = {
    val listener = new QueuingActor
    listener.start()
    Gateway.register(listener)
    listener
  }

  /**
   * Shuts down the given listener actor and unregisters it from the Gateway.
   * @param listener the listener actor
   */
  private def deregister(listener: QueuingActor) {
    Gateway.unregister(listener)
    listener.shutdown()
  }

  /**
   * Generates a URI for the playlist.
   * @param idx the index of the item
   * @return the URI for this playlist item
   */
  private def playlistURI(idx: Int) = URI + idx

  /**
   * Generates the test playlist data object.
   * @return the playlist data used by the tests
   */
  private def createPlaylistData(): PlaylistData = {
    val items =
      for (i <- 0 until PlaylistSize) yield playlistURI(i)
    PlaylistDataImpl(playlist = items, settings = mock[PlaylistSettings],
      startIndex = 1)
  }

  /**
   * Generates an ''AudioSourceData'' object for the playlist item with the
   * given index.
   * @param idx the index
   * @return the audio source data object for this playlist item
   */
  private def createSourceData(idx: Int): AudioSourceData =
    AudioSourceDataImpl(title = Title + idx, inceptionYear = 1980 + idx,
      trackNo = idx, duration = 100 + idx, albumName = null, artistName = null)

  /**
   * Obtains the next ''PlaylistUpdate'' message from the specified actor or
   * fails if the next message is not of this type.
   * @param qa the actor
   * @return the next playlist update message
   */
  private def nextUpdateMsg(qa: QueuingActor): PlaylistUpdate = {
    qa.nextMessage() match {
      case pu: PlaylistUpdate => pu
      case _ => fail("Unexpected message!")
    }
  }

  /**
   * Tests whether a complete playlist can be processed.
   */
  @Test def testExtractDataForPlaylist() {
    val extr = createMockExtractor()
    val listener = installListener()
    setUpActor(extr)
    actor ! createPlaylistData()
    val upd1 = nextUpdateMsg(listener)
    assertEquals("Wrong update index (1)", 1, upd1.updatedSourceDataIdx)
    assertEquals("Wrong source data (1)", createSourceData(1),
      upd1.playlistData.getAudioSourceData(1))
    val upd2 = nextUpdateMsg(listener)
    assertEquals("Wrong source data (2)", createSourceData(2),
      upd2.playlistData.getAudioSourceData(2))
    val upd3 = nextUpdateMsg(listener)
    assertEquals("Wrong source data (3)", createSourceData(0),
      upd3.playlistData.getAudioSourceData(0))
    deregister(listener)
    extr.shutdown()
  }

  /**
   * Tests that an extract result with no data is ignored.
   */
  @Test def testExtractResultUndefined() {
    val extractor = new QueuingActor
    extractor.start()
    val listener = installListener()
    setUpActor(extractor)
    actor ! createPlaylistData()
    extractor.expectMessage(ExtractSourceDataRequest(1, playlistURI(1), 1, actor))
    actor ! ExtractSourceDataResult(1, 1, None)
    shutdownActor()
    listener.ensureNoMessages()
    deregister(listener)
    extractor.shutdown()
  }

  /**
   * Tests that nothing happens if the source medium is locked.
   */
  @Test def testAccessSourceMediumLocked() {
    val extr = createMockExtractor()
    setUpActor(extr)
    actor ! AccessSourceMedium(true)
    actor ! createPlaylistData()
    shutdownActor()
    extr.ensureNoMessages()
    extr.shutdown()
  }

  /**
   * Tests whether a message for unlocking the source medium is processed.
   */
  @Test def testAccessSourceMediumUnlocked() {
    val extractor = new QueuingActor
    extractor.start()
    setUpActor(extractor)
    actor ! AccessSourceMedium(true)
    actor ! createPlaylistData()
    actor ! AccessSourceMedium(false)
    extractor.expectMessage(ExtractSourceDataRequest(1, playlistURI(1), 1, actor))
    extractor.shutdown()
  }

  /**
   * Tests that messages for locking or unlocking the source medium do not
   * cause problems after the playlist has been processed.
   */
  @Test def testAccessSourceMediumAfterPlaylistProcessed() {
    val extr = createMockExtractor()
    val listener = installListener()
    setUpActor(extr)
    actor ! createPlaylistData()
    extr.skipMessages(3)
    listener.skipMessages(3)
    actor ! AccessSourceMedium(true)
    actor ! AccessSourceMedium(false)
    shutdownActor()
    extr.ensureNoMessages()
    listener.ensureNoMessages()
    deregister(listener)
    extr.shutdown()
  }

  /**
   * Tests whether the playlist ID in result messages is taken into account.
   */
  @Test def testExtractResultForDifferentPlaylist() {
    val extractor = new QueuingActor
    extractor.start()
    val listener = installListener()
    setUpActor(extractor)
    actor ! createPlaylistData()
    extractor.expectMessage(ExtractSourceDataRequest(1, playlistURI(1), 1, actor))
    actor ! ExtractSourceDataResult(0, 1, Some(createSourceData(1)))
    shutdownActor()
    listener.ensureNoMessages()
    deregister(listener)
    extractor.shutdown()
  }

  /**
   * Tests whether the counter for playlist IDs is increased.
   */
  @Test def testNewPlaylistID() {
    val extractor = new QueuingActor
    extractor.start()
    setUpActor(extractor)
    actor ! createPlaylistData()
    extractor.expectMessage(ExtractSourceDataRequest(1, playlistURI(1), 1, actor))
    actor ! createPlaylistData()
    extractor.expectMessage(ExtractSourceDataRequest(2, playlistURI(1), 1, actor))
    extractor.shutdown()
  }
}

object TestPlaylistDataExtractorActor {
  @BeforeClass def setUpBeforeClass() {
    Gateway.start()
  }
}
