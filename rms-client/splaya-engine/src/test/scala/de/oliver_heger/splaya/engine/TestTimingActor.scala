package de.oliver_heger.splaya.engine

import scala.actors.Actor
import org.apache.commons.lang3.time.StopWatch
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackSourceStart
import org.junit.After
import de.oliver_heger.splaya.PlaybackPositionChanged
import de.oliver_heger.splaya.PlaybackTimeChanged
import de.oliver_heger.splaya.PlaybackStops
import de.oliver_heger.splaya.PlaybackStarts
import org.easymock.EasyMock
import java.util.concurrent.atomic.AtomicLong
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.tsthlp.WaitForExit
import de.oliver_heger.splaya.engine.msg.TimeAction

/**
 * Test class for ''TimingActor''.
 */
class TestTimingActor extends JUnitSuite with EasyMockSugar {
  /** A mock for the timer. */
  private var clock: StopWatch = _

  /** A test event listener actor. */
  private var listener: QueuingActor = _

  @After def tearDown() {
    if (listener != null) {
      Gateway.unregister(listener)
      listener.shutdown()
    }
  }

  /**
   * Creates the test actor instance and also a mock timer.
   * @return the test instance
   */
  private def createActor(): TimingActor = {
    clock = mock[StopWatch]
    val actor = new TimingActor(clock)
    actor.start()
    actor
  }

  /**
   * Ensures that the given actor is correctly shut down.
   * @param actor the affected actor
   */
  private def shutdownActor(actor: Actor) {
    val exitCmd = new WaitForExit
    if (!exitCmd.shutdownActor(actor, 5000)) {
      fail("Actor did not exit!")
    }
  }

  /**
   * Creates a test event listener actor and installs it at the Gateway.
   * @return the listener actor
   */
  private def installListener(): QueuingActor = {
    listener = new QueuingActor
    listener.start()
    Gateway.register(listener)
    listener
  }

  /**
   * Tests whether a default stop watch instance is created.
   */
  @Test def testDefaultClock() {
    val actor = new TimingActor
    assertNotNull("No clock", actor.clock)
  }

  /**
   * Tests whether a start playback of source event followed by a position
   * changed event is correctly processed. The test assumes that the skip
   * position is not yet reached.
   */
  @Test def testStartPlaybackOfSourceBeforeSkipTime() {
    val source = AudioSource("source", 1, 10000, 1000, 100)
    val actor = createActor()
    installListener()
    clock.reset()
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackPositionChanged(source.skip - 1, -1, source.skip + 10, source)
      shutdownActor(actor)
    }
    listener.ensureNoMessages()
  }

  /**
   * Tests whether position changed events after the skip position is reached
   * are handled correctly.
   */
  @Test def testPositionChangedAfterSkipTime() {
    import TestTimingActor.Source
    val actor = createActor()
    installListener()
    clock.reset()
    clock.start()
    val time = 500
    EasyMock.expect(clock.getTime()).andReturn(time)
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(Source)
      actor ! PlaybackPositionChanged(Source.skip, -1, Source.skip + 10, Source)
      actor ! PlaybackPositionChanged(Source.skip + 10, -1, Source.skip + 20, Source)
      shutdownActor(actor)
    }
    listener.expectMessage(PlaybackTimeChanged(time + Source.skipTime))
    listener.ensureNoMessages()
  }

  /**
   * Tests whether start and stop events are handled correctly.
   */
  @Test def testStartAndStop() {
    import TestTimingActor.{ SourceNoSkip => source }
    val actor = createActor()
    installListener()
    clock.reset()
    clock.start()
    clock.suspend()
    clock.resume()
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackStops
      actor ! PlaybackStarts
      shutdownActor(actor)
    }
    listener.ensureNoMessages()
  }

  /**
   * Tests that an initial start playback message does not cause problems.
   */
  @Test def testInitialStartPlayback() {
    val actor = createActor()
    whenExecuting(clock) {
      actor ! PlaybackStarts
      shutdownActor(actor)
    }
  }

  /**
   * Tests whether a PlaybackStarts message is ignored if the clock is already
   * running.
   */
  @Test def testStartMsgAlreadyRunning() {
    val actor = createActor()
    clock.reset()
    clock.start()
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(TestTimingActor.SourceNoSkip)
      actor ! PlaybackStarts
      shutdownActor(actor)
    }
  }

  /**
   * Tests whether a stop message is ignored if the clock is not running.
   */
  @Test def testStopMsgNotRunning() {
    val actor = createActor()
    clock.reset()
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(TestTimingActor.Source)
      actor ! PlaybackStops
      shutdownActor(actor)
    }
  }

  /**
   * Tests that time events are suppressed if there is no significant delta.
   */
  @Test def testTimeMessageThreshold() {
    import TestTimingActor.{ SourceNoSkip => source }
    val actor = createActor()
    clock.reset()
    clock.start()
    val time1 = 10000
    val time2 = time1 + 499
    val time3 = time2 + 50
    EasyMock.expect(clock.getTime()).andReturn(time1)
    EasyMock.expect(clock.getTime()).andReturn(time2)
    EasyMock.expect(clock.getTime()).andReturn(time3)
    installListener()
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackPositionChanged(time1, -1, time1, source)
      actor ! PlaybackPositionChanged(time2, -1, time2, source)
      actor ! PlaybackPositionChanged(time3, -1, time3, source)
      shutdownActor(actor)
    }
    listener.expectMessage(PlaybackTimeChanged(time1))
    listener.expectMessage(PlaybackTimeChanged(time3))
    listener.ensureNoMessages()
  }

  /**
   * Tests whether a time action can be executed.
   */
  @Test def testTimeAction() {
    val actor = createActor()
    clock.reset()
    clock.start()
    val time = 2222
    EasyMock.expect(clock.getTime()).andReturn(time)
    val timeValue = new AtomicLong
    val action = TimeAction(timeValue.set(_))
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(TestTimingActor.SourceNoSkip)
      actor ! action
      shutdownActor(actor)
    }
    assert(time === timeValue.get())
  }

  /**
   * Tests whether a time action can be executed before the timer was started.
   */
  @Test def testTimeActionNotStarted() {
    val actor = createActor()
    val timeValue = new AtomicLong(20120330205510L)
    val action = TimeAction(timeValue.set(_))
    clock.reset()
    whenExecuting(clock) {
      actor ! PlaybackSourceStart(TestTimingActor.Source)
      actor ! action
      shutdownActor(actor)
    }
    assert(0 === timeValue.get())
  }
}

object TestTimingActor {
  /** Constant for a default audio source. */
  val Source = AudioSource("source", 1, 10000, 1000, 100)

  /** Constant for a audio source without skip information. */
  val SourceNoSkip = AudioSource("sourceNoSkip", 2, 20000, 0, 0)

  @BeforeClass def setUpBeforeClass() {
    Gateway.start()
  }
}
