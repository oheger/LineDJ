package de.oliver_heger.splaya.playback

/**
 * A message class sent by actors in the ''playback'' package to indicate a
 * protocol violation.
 *
 * The actors responsible for audio playback typically exchange a large number
 * of messages in order to transfer audio data through various stages until it
 * can be actually played. If a message is received which is invalid in an
 * actor's current state, a message of this type is sent as answer. This should
 * normally not happen in a running system. However, having such error messages
 * simplifies debugging when something goes wrong.
 *
 * This message contains the original message causing the protocol violation.
 * There is also an error string containing further information why the related
 * message was not allowed.
 * @param msg the invalid message
 * @param errorText an error text providing additional information
 */
case class PlaybackProtocolViolation(msg: Any, errorText: String)
