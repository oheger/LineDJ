package de.oliver_heger.splaya.engine

import java.io.Closeable

import scala.actors.Actor

import org.apache.commons.lang3.time.StopWatch

import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.engine.msg.TimeAction
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackPositionChanged
import de.oliver_heger.splaya.PlaybackSourceStart
import de.oliver_heger.splaya.PlaybackStarts
import de.oliver_heger.splaya.PlaybackStops
import de.oliver_heger.splaya.PlaybackTimeChanged

/**
 * A specialized ''Actor'' implementation which is responsible for timing
 * aspects.
 *
 * The audio player engine has to measure the time when a song is played so
 * that client code can display it. This is done by this actor. This actor
 * is registered as event listener for events fired by the audio player engine.
 * It interprets the events received to correctly manage a timer for measuring
 * playback time. It sends out corresponding timer events which can be
 * evaluated by a client application.
 *
 * This actor is the only instance in the audio player engine which has access
 * to the current playback time. In order to provide this information to other
 * components in a thread-safe manner, it supports a special message which
 * contains a function to be invoked with the playback time as parameter. Thus
 * specific actions requiring the current time can be executed by this actor on
 * behalf of other components.
 *
 * @param gateway the gateway object
 * @param clock the timer to be used by this actor
 */
class TimingActor(gateway: Gateway, private[engine] val clock: StopWatch)
  extends Actor {
  /**
   * Constant for the threshold for time events. An event is only fired if the
   * last event took place before this time (in milliseconds).
   */
  private val Threshold = 500L

  /** The last time a ''PlaybackTimeChanged'' event was fired. */
  private var lastEventTime: Long = -1

  /** The skip time for the current source. */
  private var skipTime: Long = _

  /** A flag whether the timer has already been started for the current source. */
  private var sourceStarted = false

  /** A flag whether the clock is currently running. */
  private var clockRunning = false

  /**
   * Creates a new instance of ''TimingActor'' and initializes it with a newly
   * created timer instance.
   * @param gateway the gateway object
   */
  def this(gateway: Gateway) = this(gateway, new StopWatch)

  /**
   * The main message loop of this actor. It receives messages sent by the
   * audio engine to keep track on the playback status. The internal timer is
   * updated correspondingly.
   */
  def act {
    var running = true

    while (running) {
      receive {
        case cl: Closeable =>
          cl.close()
          running = false

        case PlaybackSourceStart(src) =>
          handleSourceStart(src)

        case posMsg: PlaybackPositionChanged =>
          handlePositionChanged(posMsg)

        case PlaybackStarts =>
          handlePlaybackStart()

        case PlaybackStops =>
          handlePlaybackStop()

        case TimeAction(f) =>
          handleTimeAction(f)

        case _ => // ignore
      }
    }
  }

  /**
   * Returns a string representation for this actor. This is just the short name
   * of this actor.
   * @return a string for this actor
   */
  override def toString = "TimingActor"

  /**
   * Starts the clock if it is not yet running. It can be either started or
   * resumed. If the clock is currently not running, this method has no effect.
   * @param start '''true''' if the clock is to be started, '''false''' if the
   * clock is to be resumed
   */
  private def startClock(start: Boolean) {
    if (!clockRunning) {
      if (start) clock.start()
      else clock.resume()
      clockRunning = true
    }
  }

  /**
   * Pauses the clock if it is currently running. Otherwise, this method has no
   * effect.
   */
  private def pauseClock() {
    if (clockRunning) {
      clock.suspend()
      clockRunning = false
    }
  }

  /**
   * Returns the current time. This is the current time returned by the timer
   * plus the skip time.
   */
  private def time() =
    if (sourceStarted) clock.getTime() + skipTime else 0

  /**
   * Fires a ''PlaybackTimeChanged'' event. The event is only fired if the
   * threshold is not violated.
   */
  private def firePlaybackTimeChanged() {
    val eventTime = time()
    if (lastEventTime < 0 || eventTime - lastEventTime > Threshold) {
      gateway.publish(PlaybackTimeChanged(eventTime))
      lastEventTime = eventTime
    }
  }

  /**
   * Handles a message about the start of an audio source. This resets the
   * timer. If the new source does not define a skip position, the timer is
   * started immediately.
   * @param src the ''AudioSource''
   */
  private def handleSourceStart(src: AudioSource) {
    clock.reset()
    sourceStarted = false
    clockRunning = false

    if (src.skip == 0) {
      startClock(true)
      sourceStarted = true
      skipTime = 0
      lastEventTime = -1
    } else {
      skipTime = src.skipTime
    }
  }

  /**
   * Handles a message about a position change in the current audio stream. If
   * the source has a skip position, and this position is now reached, the
   * timer is started. If we are after the skip position, a corresponding
   * time event is generated.
   * @param msg the position changed message
   */
  private def handlePositionChanged(msg: PlaybackPositionChanged) {
    if (sourceStarted) {
      firePlaybackTimeChanged()
    } else {
      if (msg.audioStreamPosition >= msg.source.skip) {
        startClock(true)
        sourceStarted = true
      }
    }
  }

  /**
   * Handles a playback stop message. The timer is paused if it is running.
   */
  private def handlePlaybackStop() {
    pauseClock()
  }

  /**
   * Handles a playback start message. If the timer is not running, it is
   * resumed.
   */
  private def handlePlaybackStart() {
    if (sourceStarted) {
      startClock(false)
    }
  }

  /**
   * Executes a time action. The passed in method is called with the current
   * time. This works always, even if the current source has not been started.
   * In this case, the time is 0.
   */
  private def handleTimeAction(f: Long => Unit) {
    f(time())
  }
}
