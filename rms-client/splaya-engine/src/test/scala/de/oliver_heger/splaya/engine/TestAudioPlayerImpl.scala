package de.oliver_heger.splaya.engine

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.fail
import org.easymock.EasyMock

/**
 * Test class for ''AudioPlayerImpl''.
 */
class TestAudioPlayerImpl extends JUnitSuite with EasyMockSugar {
  /** A mock for the playlist controller. */
  private var plCtrl: PlaylistController = _

  /** A mock for the playback actor. */
  private var playbackActor: QueuingActor = _

  /** A mock for the source reader actor. */
  private var readerActor: QueuingActor = _

  /** A mock for the timing actor. */
  private var timingActor: QueuingActor = _

  /** The player to be tested. */
  private var player: AudioPlayerImpl = _

  @Before def setUp() {
    plCtrl = mock[PlaylistController]
    playbackActor = new QueuingActor
    playbackActor.start()
    Gateway += Gateway.ActorPlayback -> playbackActor
    readerActor = new QueuingActor
    readerActor.start()
    Gateway += Gateway.ActorSourceRead -> readerActor
    Gateway.start()
    timingActor = new QueuingActor
    timingActor.start()
    player = new AudioPlayerImpl(plCtrl, timingActor)
  }

  @After def tearDown() {
    playbackActor.shutdown()
    readerActor.shutdown()
    timingActor.shutdown()
  }

  /**
   * Helper method for testing whether the actors do not have any more messages
   * to process.
   */
  private def ensureActorsNoMessages() {
    playbackActor.ensureNoMessages()
    readerActor.ensureNoMessages()
  }

  /**
   * Tests whether playback can be started.
   */
  @Test def testStartPlayback() {
    EasyMock.replay(plCtrl)
    player.startPlayback()
    playbackActor.expectMessage(StartPlayback)
    ensureActorsNoMessages()
  }

  /**
   * Tests whether playback can be stopped.
   */
  @Test def testStopPlayback() {
    EasyMock.replay(plCtrl)
    player.stopPlayback()
    playbackActor.expectMessage(StopPlayback)
    ensureActorsNoMessages()
  }

  /**
   * Tests whether the player can move forward to the next song in the playlist.
   */
  @Test def testMoveForward() {
    EasyMock.replay(plCtrl)
    player.moveForward()
    playbackActor.expectMessage(SkipCurrentSource)
    ensureActorsNoMessages()
  }

  /**
   * Tests whether the player can jump to a specific audio source in the
   * playlist.
   */
  @Test def testMoveToSource() {
    val idx = 42
    plCtrl.moveToSourceAt(idx)
    whenExecuting(plCtrl) {
      player.moveToSource(idx)
    }
    readerActor.expectMessage(FlushPlayer)
    ensureActorsNoMessages()
  }

  /**
   * Tests whether a medium can be read.
   */
  @Test def testReadMedium() {
    val uri = "/data/mymusic/"
    plCtrl.readMedium(uri)
    whenExecuting(plCtrl) {
      player.readMedium(uri)
    }
    readerActor.expectMessage(FlushPlayer)
    ensureActorsNoMessages()
  }

  /**
   * Tests whether a shutdown of the player works correctly.
   */
  @Test def testShutdown() {
    val lineActor = new QueuingActor
    lineActor.start()
    Gateway += Gateway.ActorLineWrite -> lineActor
    plCtrl.shutdown()
    whenExecuting(plCtrl) {
      player.shutdown()
    }
    readerActor.expectMessage(Exit)
    playbackActor.expectMessage(Exit)
    lineActor.expectMessage(Exit)
    ensureActorsNoMessages()
  }

  /**
   * Extracts a ''TimeAction'' message from the mock timing actor or fails.
   * @return the ''TimeAction'' message
   */
  private def extractTimeAction(): TimeAction =
    timingActor.nextMessage() match {
      case ta: TimeAction => ta
      case _ => fail("Unexpected message!")
    }

  /**
   * Tests a move backward operation if the current audio source has to be
   * played again.
   */
  @Test def testMoveBackwardReplay() {
    plCtrl.moveToSourceRelative(0)
    whenExecuting(plCtrl) {
      player.moveBackward()
      extractTimeAction().f(5000)
    }
    readerActor.expectMessage(FlushPlayer)
    ensureActorsNoMessages()
  }

  /**
   * Tests a move backward operation if the previous audio source has to be
   * played.
   */
  @Test def testMoveBackwardPrevious() {
    plCtrl.moveToSourceRelative(-1)
    whenExecuting(plCtrl) {
      player.moveBackward()
      extractTimeAction().f(4999)
    }
    readerActor.expectMessage(FlushPlayer)
    ensureActorsNoMessages()
  }
}
