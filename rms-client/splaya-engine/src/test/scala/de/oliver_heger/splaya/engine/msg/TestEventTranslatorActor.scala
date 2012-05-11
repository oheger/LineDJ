package de.oliver_heger.splaya.engine.msg

import org.scalatest.junit.JUnitSuite
import org.junit.Assert._
import de.oliver_heger.splaya.AudioPlayerListener
import java.util.concurrent.SynchronousQueue
import de.oliver_heger.splaya.AudioPlayerEvent
import de.oliver_heger.splaya.AudioPlayerEventType
import de.oliver_heger.splaya.tsthlp.WaitForExit
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.After
import org.junit.Test
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackSourceStart
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.PlaybackPositionChanged
import de.oliver_heger.splaya.PlaybackTimeChanged
import de.oliver_heger.splaya.PlaybackError
import de.oliver_heger.splaya.PlaybackStarts
import de.oliver_heger.splaya.PlaybackStops
import de.oliver_heger.splaya.PlaylistListener
import de.oliver_heger.splaya.PlaylistEvent
import de.oliver_heger.splaya.PlaylistEventType
import de.oliver_heger.splaya.PlaylistData
import java.util.concurrent.BlockingQueue
import org.easymock.EasyMock
import de.oliver_heger.splaya.PlaylistUpdate
import de.oliver_heger.splaya.PlaylistEnd

/**
 * Test class for ''EventTranslatorActor''.
 */
class TestEventTranslatorActor extends JUnitSuite {
  /** Constant of a test audio source. */
  private val Source = AudioSource("SomeTestSong", 11, 20120421222632L, 0, 0)

  /** A test audio player event listener. */
  private var listener: AudioPlayerListenerImpl = _

  /** A test playlist event listener. */
  private var playlistListener: PlaylistListenerImpl = _

  /** The actor to be tested. */
  private var actor: EventTranslatorActor = _

  @Before def setUp() {
    actor = new EventTranslatorActor
    actor.start()
    listener = new AudioPlayerListenerImpl
    actor ! AddAudioPlayerEventListener(listener)
    playlistListener = new PlaylistListenerImpl
    actor ! AddPlaylistEventListener(playlistListener)
  }

  @After def tearDown() {
    if (actor != null) {
      shutdownActor()
    }
  }

  /**
   * Shuts down the test actor and waits until it is down.
   */
  private def shutdownActor() {
    val ex = new WaitForExit
    if (!ex.shutdownActor(actor)) {
      fail("Actor did not exit!")
    }
    actor = null
  }

  /**
   * Checks whether the queue of a test listener does not contain an event.
   * @param q the queue to be checked
   */
  def checkNoEvent[E](q: BlockingQueue[E]) {
    val event = q.poll(100, TimeUnit.MILLISECONDS)
    assertNull("Got an event: " + event, event)
  }

  /**
   * Creates a mock playlist.
   * @return the mock playlist
   */
  def createPlaylist(): PlaylistData = {
    val pl = EasyMock.createMock(classOf[PlaylistData])
    EasyMock.replay(pl)
    pl
  }

  /**
   * Tests whether an audio player event listener can be removed.
   */
  @Test def testRemovePlayerListener() {
    actor ! RemoveAudioPlayerEventListener(listener)
    actor ! PlaylistEnd
    checkNoEvent(listener.queue)
  }

  /**
   * Tests whether a playlist listener can be removed.
   */
  @Test def testRemovePlaylistListener() {
    actor ! RemovePlaylistEventListener(playlistListener)
    actor ! createPlaylist()
    checkNoEvent(playlistListener.queue)
  }

  /**
   * Tests whether multiple listener instances can be handled.
   */
  @Test def testAddListenerMultipleTimes() {
    actor ! AddAudioPlayerEventListener(listener)
    actor ! RemoveAudioPlayerEventListener(listener)
    actor ! PlaylistEnd
    listener.expectEvent(AudioPlayerEventType.PLAYLIST_END)
  }

  /**
   * Tests whether a playlist end event is correctly transformed.
   */
  @Test def testPlaylistEndEvent() {
    actor ! PlaylistEnd
    val ev = listener.expectEvent(AudioPlayerEventType.PLAYLIST_END)
    assertNull("Got an audio source", ev.getSource)
    assertNull("Got an exception", ev.getException)
  }

  /**
   * Tests whether an audio source start event is correctly transformed.
   */
  @Test def testSourceStartEvent() {
    actor ! PlaybackSourceStart(Source)
    val ev = listener.expectEvent(AudioPlayerEventType.START_SOURCE)
    assert(Source === ev.getSource)
    assert(0 === ev.getPosition)
    assert(0 === ev.getPlaybackTime)
  }

  /**
   * Tests whether an audio source end event is correctly transformed.
   */
  @Test def testSourceEndEvent() {
    actor ! PlaybackSourceEnd(Source, true)
    val ev = listener.expectEvent(AudioPlayerEventType.END_SOURCE)
    assert(Source === ev.getSource)
    assertTrue("Wrong skip flag", ev.isSkipped)
  }

  /**
   * Tests whether position changed events are correctly handled.
   */
  @Test def testPositionChangedEvent() {
    actor ! PlaybackPositionChanged(400, 1000, 10, Source)
    actor ! PlaybackTimeChanged(5000)
    val ev = listener.expectEvent(AudioPlayerEventType.POSITION_CHANGED)
    assert(Source === ev.getSource)
    assertEquals("Wrong position", 400, ev.getPosition)
    assertEquals("Wrong relative position", 40, ev.getRelativePosition)
    assertEquals("Wrong time", 5000, ev.getPlaybackTime)
  }

  /**
   * Tests that position changed events are suppressed if the time has not
   * changed.
   */
  @Test def testPositionChangedEventSameTime() {
    actor ! PlaybackPositionChanged(400, 1000, 10, Source)
    actor ! PlaybackTimeChanged(5000)
    listener.expectEvent(AudioPlayerEventType.POSITION_CHANGED)
    actor ! PlaybackPositionChanged(405, 1000, 11, Source)
    actor ! PlaybackTimeChanged(5000)
    actor ! PlaybackPositionChanged(410, 1000, 12, Source)
    actor ! PlaybackTimeChanged(5500)
    val ev = listener.expectEvent(AudioPlayerEventType.POSITION_CHANGED)
    assertEquals("Wrong position", 410, ev.getPosition)
    assertEquals("Wrong relative position", 41, ev.getRelativePosition)
    assertEquals("Wrong time", 5500, ev.getPlaybackTime)
  }

  /**
   * Tests whether a playback error event is correctly transformed.
   */
  @Test def testPlaybackErrorEvent() {
    val ex = new Exception("A test exception")
    actor ! PlaybackError("Some error", ex, false)
    val ev = listener.expectEvent(AudioPlayerEventType.EXCEPTION)
    assert(ex === ev.getException)
  }

  /**
   * Tests whether a fatal playback error event is correctly transformed.
   */
  @Test def testPlaybackErrorEventFatal() {
    val ex = new Exception("A fatal test exception")
    actor ! PlaybackError("Some error", ex, true)
    val ev = listener.expectEvent(AudioPlayerEventType.FATAL_EXCEPTION)
    assert(ex === ev.getException)
  }

  /**
   * Tests whether an event for starting playback is correctly transformed.
   */
  @Test def testPlaybackStartEvent() {
    actor ! PlaybackStarts
    val ev = listener.expectEvent(AudioPlayerEventType.START_PLAYBACK)
    assertNull("Got a source", ev.getSource)
    assertEquals("Wrong position", 0, ev.getPosition)
  }

  /**
   * Tests whether additional information is available in a start playback event
   * if a position event was received before.
   */
  @Test def testPlaybackStartEventAfterPosition() {
    actor ! PlaybackPositionChanged(400, 1000, 10, Source)
    actor ! PlaybackStarts
    val ev = listener.expectEvent(AudioPlayerEventType.START_PLAYBACK)
    assert(Source === ev.getSource)
    assertEquals("Wrong position", 400, ev.getPosition)
  }

  /**
   * Tests whether a stop playback event is correctly transformed.
   */
  @Test def testPlaybackStopEventAfterPosition() {
    actor ! PlaybackPositionChanged(400, 1000, 10, Source)
    actor ! PlaybackStops
    val ev = listener.expectEvent(AudioPlayerEventType.STOP_PLAYBACK)
    assert(Source === ev.getSource)
    assertEquals("Wrong position", 400, ev.getPosition)
  }

  /**
   * Tests whether a playlist creation event is correctly transformed.
   */
  @Test def testCreatePlaylistEvent() {
    val pl = createPlaylist()
    actor ! pl
    val ev = playlistListener.expectEvent(PlaylistEventType.PLAYLIST_CREATED)
    assertSame("Wrong playlist data", pl, ev.getPlaylistData)
    assertEquals("Wrong update index", -1, ev.getUpdateIndex)
  }

  /**
   * Tests whether a playlist update event is correctly transformed.
   */
  @Test def testUpdatePlaylistEvent() {
    val pl = createPlaylist()
    val idx = 42
    actor ! PlaylistUpdate(pl, idx)
    val ev = playlistListener.expectEvent(PlaylistEventType.PLAYLIST_UPDATED)
    assertSame("Wrong playlist data", pl, ev.getPlaylistData)
    assertEquals("Wrong update index", idx, ev.getUpdateIndex)
  }
}

object TestEventTranslatorActor {
  /** Constant for the timeout for incoming events (in seconds). */
  val EventTimeOut = 5
}

/**
 * A test implementation of the event listener interface. This class implements
 * a thread-safe way for waiting for an event received from the actor.
 */
private class AudioPlayerListenerImpl extends AudioPlayerListener {
  /** A queue for storing received events. */
  val queue = new SynchronousQueue[AudioPlayerEvent]

  def playbackError(ev: AudioPlayerEvent) {
    addMessage(ev, AudioPlayerEventType.EXCEPTION,
      AudioPlayerEventType.FATAL_EXCEPTION)
  }

  def playlistEnds(ev: AudioPlayerEvent) {
    addMessage(ev, AudioPlayerEventType.PLAYLIST_END)
  }

  def positionChanged(ev: AudioPlayerEvent) {
    addMessage(ev, AudioPlayerEventType.POSITION_CHANGED)
  }

  def sourceEnds(ev: AudioPlayerEvent) {
    addMessage(ev, AudioPlayerEventType.END_SOURCE)
  }

  def sourceStarts(ev: AudioPlayerEvent) {
    addMessage(ev, AudioPlayerEventType.START_SOURCE)
  }

  def playbackStops(ev: AudioPlayerEvent) {
    addMessage(ev, AudioPlayerEventType.STOP_PLAYBACK)
  }

  def playbackStarts(ev: AudioPlayerEvent) {
    addMessage(ev, AudioPlayerEventType.START_PLAYBACK)
  }

  /**
   * Waits until an event is received and returns it. The event type is checked.
   * If no event is received in a specific time interval, this method fails.
   * @param expType the expected event type
   * @return the audio player event
   */
  def expectEvent(expType: AudioPlayerEventType): AudioPlayerEvent = {
    val ev = queue.poll(TestEventTranslatorActor.EventTimeOut, TimeUnit.SECONDS)
    assertNotNull("No event received", ev)
    assertEquals("Wrong event type", expType, ev.getType)
    ev
  }

  /**
   * Adds an event received by one of the listener methods to the internal
   * queue. The event type is checked to be one of the expected types.
   * @param ev the event to add
   * @param exTypes a sequence of expected types
   */
  private def addMessage(ev: AudioPlayerEvent, exTypes: AudioPlayerEventType*) {
    assertTrue("Wrong event type", exTypes.toSet(ev.getType))
    queue.put(ev)
  }
}

/**
 * A test implementation of the playlist listener interface analogously to
 * ''AudioPlayerListenerImpl''.
 */
private class PlaylistListenerImpl extends PlaylistListener {
  /** A queue for storing received events. */
  val queue = new SynchronousQueue[PlaylistEvent]

  def playlistCreated(ev: PlaylistEvent) {
    addMessage(ev, PlaylistEventType.PLAYLIST_CREATED)
  }

  def playlistUpdated(ev: PlaylistEvent) {
    addMessage(ev, PlaylistEventType.PLAYLIST_UPDATED)
  }

  /**
   * Waits until an event is received and returns it. The event type is checked.
   * If no event is received in a specific time interval, this method fails.
   * @param expType the expected event type
   * @return the audio player event
   */
  def expectEvent(expType: PlaylistEventType): PlaylistEvent = {
    val ev = queue.poll(TestEventTranslatorActor.EventTimeOut, TimeUnit.SECONDS)
    assertNotNull("No event received", ev)
    assertEquals("Wrong event type", expType, ev.getType)
    ev
  }

  /**
   * Adds an event received by one of the listener methods to the internal
   * queue and checks the event type.
   * @param ev the event to add
   * @param expType the expected event type
   */
  private def addMessage(ev: PlaylistEvent, expType: PlaylistEventType) {
    assertEquals("Wrong event type", expType, ev.getType)
    queue.put(ev)
  }
}
