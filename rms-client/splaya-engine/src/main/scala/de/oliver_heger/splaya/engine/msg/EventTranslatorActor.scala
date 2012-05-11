package de.oliver_heger.splaya.engine.msg

import scala.actors.Actor
import de.oliver_heger.splaya.AudioPlayerListener
import de.oliver_heger.splaya.AudioPlayerEvent
import de.oliver_heger.splaya.AudioPlayerEventType
import scala.reflect.BeanProperty
import scala.reflect.BooleanBeanProperty
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackPositionChanged
import de.oliver_heger.splaya.PlaybackSourceStart
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.PlaybackTimeChanged
import de.oliver_heger.splaya.PlaybackError
import de.oliver_heger.splaya.PlaybackStarts
import de.oliver_heger.splaya.PlaybackStops
import de.oliver_heger.splaya.PlaylistListener
import de.oliver_heger.splaya.PlaylistEvent
import de.oliver_heger.splaya.PlaylistData
import de.oliver_heger.splaya.PlaylistEventType
import de.oliver_heger.splaya.PlaylistUpdate
import de.oliver_heger.splaya.PlaylistEnd

/**
 * Implementation of an actor which translates messages sent by the audio player
 * engine to calls of event listener methods.
 *
 * Important messages related to state changes in the audio player engine are
 * published as messages through the
 * [[de.oliver_heger.splaya.engine.msg.Gateway]] class. This makes it possible
 * to create custom actors, register them at the gateway, and to process
 * messages sent by the player.
 *
 * However, not all parties interested in audio player notifications can provide
 * actors as message listeners. Therefore, an alternative approach based on
 * typical Java event listener mechanisms is supported, too. In order to serve
 * registered event listeners, there must be a translation between the
 * notification messages for registered actors and event listener method calls.
 * This is done by this actor class. An instance is registered at the gateway
 * and thus receives all notification messages. Based on these messages it
 * creates corresponding [[de.oliver_heger.splaya.AudioPlayerEvent]] instances
 * and passes them to the corresponding methods of the event listeners
 * registered. The registration of event listeners is done through specific
 * messages sent to this actor.
 */
class EventTranslatorActor extends Actor {
  /** Constant for a dummy position changed event setting all positions to 0. */
  private val InitPositionChanged = PlaybackPositionChanged(0, 1, 0, null)

  /** The audio player listeners. */
  private val playerListeners =
    new Listeners[AudioPlayerListener, AudioPlayerEvent]

  /** The playlist listeners. */
  private val playlistListeners =
    new Listeners[PlaylistListener, PlaylistEvent]

  /** Stores the last position changed event. */
  private var lastPositionEvent: PlaybackPositionChanged = InitPositionChanged

  /** Stores the last playback time. */
  private var lastPlaybackTime: Long = 0

  /**
   * The main message loop of this actor.
   */
  def act() {
    var running = true

    while (running) {
      receive {
        case ex: Exit =>
          ex.confirmed(this)
          running = false

        case AddAudioPlayerEventListener(l) =>
          playerListeners += l

        case RemoveAudioPlayerEventListener(l) =>
          playerListeners -= l

        case AddPlaylistEventListener(l) =>
          playlistListeners += l

        case RemovePlaylistEventListener(l) =>
          playlistListeners -= l

        case PlaylistEnd =>
          playerListeners.fire(() =>
            AudioPlayerEventImpl(AudioPlayerEventType.PLAYLIST_END),
            _.playlistEnds(_))

        case ps: PlaybackSourceStart =>
          lastPositionEvent = InitPositionChanged
          lastPlaybackTime = 0
          playerListeners.fire(() =>
            AudioPlayerEventImpl(getType = AudioPlayerEventType.START_SOURCE,
              source = ps.source), _.sourceStarts(_))

        case pe: PlaybackSourceEnd =>
          playerListeners.fire(() =>
            createEventWithPosition(AudioPlayerEventType.END_SOURCE, pe.source,
              pe.skipped), _.sourceEnds(_))

        case pc: PlaybackPositionChanged =>
          lastPositionEvent = pc

        case PlaybackTimeChanged(time) =>
          if (time != lastPlaybackTime) {
            lastPlaybackTime = time
            playerListeners.fire(() =>
              createEventWithPosition(AudioPlayerEventType.POSITION_CHANGED,
                lastPositionEvent.source), _.positionChanged(_))
          }

        case pe: PlaybackError =>
          val evtype = if (pe.fatal) AudioPlayerEventType.FATAL_EXCEPTION
          else AudioPlayerEventType.EXCEPTION
          playerListeners.fire(() =>
            AudioPlayerEventImpl(getType = evtype, exception = pe.exception),
            _.playbackError(_))

        case PlaybackStarts =>
          playerListeners.fire(() =>
            createEventWithPosition(AudioPlayerEventType.START_PLAYBACK,
              lastPositionEvent.source), _.playbackStarts(_))

        case PlaybackStops =>
          playerListeners.fire(() =>
            createEventWithPosition(AudioPlayerEventType.STOP_PLAYBACK,
              lastPositionEvent.source), _.playbackStops(_))

        case pd: PlaylistData =>
          playlistListeners.fire(() =>
            PlaylistEventImpl(PlaylistEventType.PLAYLIST_CREATED, pd, -1),
            _.playlistCreated(_))

        case PlaylistUpdate(pd, idx) =>
          playlistListeners.fire(() =>
            PlaylistEventImpl(PlaylistEventType.PLAYLIST_UPDATED, pd, idx),
            _.playlistUpdated(_))

        case _ =>
      }
    }
  }

  /**
   * Returns a string representation for this object. This implementation
   * returns the name of this actor.
   * @return a string for this object
   */
  override def toString = "EventTranslatorActor"

  /**
   * Creates an audio player event with position information.
   * @param evType the type of the event
   * @param src the current audio source
   * @param skipFlag the skip flag
   * @return the event
   */
  private def createEventWithPosition(evType: AudioPlayerEventType,
    src: AudioSource, skipFlag: Boolean = false): AudioPlayerEvent =
    AudioPlayerEventImpl(getType = evType, source = src,
      position = lastPositionEvent.audioStreamPosition,
      relativePosition = lastPositionEvent.relativePosition,
      playbackTime = lastPlaybackTime, skipped = skipFlag)

  /**
   * An internally used helper class for managing a list of event listeners of
   * a specific type. The class provides methods for adding and removing
   * listeners and for firing events.
   * @tparam L the event listener interface
   * @tparam E the event type
   */
  private class Listeners[L, E] {
    /** The list with the event listeners. */
    var listeners = List.empty[L]

    /**
     * Adds the given event listener to the internal list.
     * @param listener the listener to be added
     */
    def +=(listener: L) {
      listeners = listener :: listeners
    }

    /**
     * Removes the given event listener from the internal list. If the listener
     * was registered multiple times, only one instance is removed.
     * @param listener the listener to be removed
     */
    def -=(listener: L) {
      var found = false
      listeners = listeners.filterNot { l =>
        if (found) false
        else {
          if (l == listener) {
            found = true
            true
          } else false
        }
      }
    }

    /**
     * Fires an event by calling all event listeners. The event function is
     * invoked if there is at least one listener. Then on all listeners the
     * invoke function is called passing in the listener and the event object.
     * @param fEv the function for creating the event object
     * @param fInv the function for invoking the event listener
     */
    def fire(fEv: () => E, fInv: (L, E) => Unit) {
      if (!listeners.isEmpty) {
        val event = fEv()
        listeners foreach (fInv(_, event))
      }
    }
  }
}

/**
 * A message class for registering a new ''AudioPlayerListener''. This message
 * is used to pass a new listener to the ''EventTranslatorActor''.
 * @param listener the listener to be registered
 */
case class AddAudioPlayerEventListener(listener: AudioPlayerListener)

/**
 * A message class for removing an ''AudioPlayerListener'' from the
 * ''EventTranslatorActor''.
 * @param listener the listener to be removed
 */
case class RemoveAudioPlayerEventListener(listener: AudioPlayerListener)

/**
 * A message class for registering a new ''PlaylistListener'' at the
 * ''EventTranslator'' actor.
 * @param listener the listener to be registered
 */
case class AddPlaylistEventListener(listener: PlaylistListener)

/**
 * A message class for removing a ''PlaylistListener'' from the
 * ''EventTranslatorActor''.
 * @param listener the listener to be removed
 */
case class RemovePlaylistEventListener(listener: PlaylistListener)

/**
 * An implementation of the ''AudioPlayerEventTrait''. The event properties
 * are mapped directly to constructor arguments.
 */
private case class AudioPlayerEventImpl(getType: AudioPlayerEventType,
  @BeanProperty source: AudioSource = null,
  @BeanProperty exception: Throwable = null,
  @BeanProperty position: Long = 0,
  @BeanProperty relativePosition: Int = 0,
  @BeanProperty playbackTime: Long = 0,
  @BooleanBeanProperty skipped: Boolean = false) extends AudioPlayerEvent

private case class PlaylistEventImpl(getType: PlaylistEventType,
  @BeanProperty playlistData: PlaylistData,
  @BeanProperty updateIndex: Int) extends PlaylistEvent
