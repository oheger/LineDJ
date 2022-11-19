/*
 * Copyright 2015-2022 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.player.engine.actors

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
