package de.oliver_heger.splaya

/**
 * A trait defining events sent by the audio player engine.
 *
 * Objects implementing this interface are passed to
 * [[de.oliver_heger.splaya.AudioPlayerListener]] implementations.
 *
 * This is an alternative way to get notifications from the audio player engine
 * for clients that do not use actors. It resembles the typical Java event
 * listener approach and is therefore suitable for Java clients.
 *
 * This trait defines many access methods for event properties. However, the
 * properties actually available depend on the concrete event type. The method
 * documentation contains information under which circumstances this data is
 * defined.
 */
trait AudioPlayerEvent {
  /**
   * Returns the type of this event.
   * @return the event type
   */
  def getType: AudioPlayerEventType

  /**
   * Returns information about the audio source this event is associated with.
   * This information is available for event types that are related to a
   * current audio source (all except for a playlist end event).
   * @return the associated audio source
   */
  def getSource: AudioSource

  /**
   * Returns the absolute position in the current audio source. This property
   * is defined for events of type ''POSITION_CHANGED''.
   * @return the absolute playback position
   */
  def getPosition: Long

  /**
   * Returns the relative playback position in the current audio source. This
   * is a numeric value in the range from 0 to 100. This property is defined
   * for events of type ''POSITION_CHANGED''.
   * @return the relative position in the current audio source
   */
  def getRelativePosition: Int

  /**
   * Returns the playback time in the current audio source (in milliseconds).
   * This property is defined for events of type ''POSITION_CHANGED''.
   * @return the playback time
   */
  def getPlaybackTime: Long

  /**
   * Returns the exception associated with this event. This property is defined
   * for events of type ''EXCEPTION'' or ''FATAL_EXCEPTION'' only.
   * @return the exception which caused this event
   */
  def getException: Throwable

  /**
   * Returns a flag whether the current audio source has been skipped. This
   * property is defined for events of type ''END_SOURCE'' only.
   * @return a flag whether the audio source has been skipped
   */
  def isSkipped: Boolean
}
