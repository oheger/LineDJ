package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import de.oliver_heger.splaya.tsthlp.ActorTestImpl
import org.junit.Before
import org.junit.Test
import de.oliver_heger.splaya.engine.msg.Exit

/**
 * Test class for ''PlaylistControllerImpl''.
 */
class TestPlaylistControllerImpl extends JUnitSuite {
  /** The test actor. */
  private var actor: ActorTestImpl = _

  /** The controller to be tested. */
  private var ctrl: PlaylistControllerImpl = _

  @Before def setUp() {
    actor = new ActorTestImpl
    ctrl = new PlaylistControllerImpl(actor)
  }

  /**
   * Tests whether a specific source can be selected in the playlist.
   */
  @Test def testMoveToSourceAt() {
    val idx = 42
    ctrl.moveToSourceAt(idx)
    actor.expectMessage(MoveTo(idx))
    actor.ensureNoMessages()
  }

  /**
   * Tests whether a relative position in the playlist can be selected.
   */
  @Test def testMoveToSourceRelative() {
    val delta = -3
    ctrl.moveToSourceRelative(delta)
    actor.expectMessage(MoveRelative(delta))
    actor.ensureNoMessages()
  }

  /**
   * Tests whether the content of the source medium can be read.
   */
  @Test def testReadMedium() {
    val rootURI = "/music/"
    ctrl.readMedium(rootURI)
    actor.expectMessage(SavePlaylist)
    actor.expectMessage(ReadMedium(rootURI))
    actor.ensureNoMessages()
  }

  /**
   * Tests whether the controller can be shut down.
   */
  @Test def testShutdown() {
    ctrl.shutdown()
    actor.expectMessage(SavePlaylist)
    actor.expectMessage(Exit)
    actor.ensureNoMessages()
  }
}
