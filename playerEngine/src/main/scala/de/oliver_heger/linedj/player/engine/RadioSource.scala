/*
 * Copyright 2015-2016 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine

/**
  * A data class identifying a radio stream to be played.
  *
  * Instances of this class are processed by a radio player. When this message
  * is received, playback of the current stream - if any - stops, and a source
  * reader actor is created for the new stream. The default extension is of use
  * if the resolved stream URI does not have an extension. However, this is
  * required to select the correct audio codec when creating the playback
  * context.
  *
  * @param uri              the URI of the stream to be played
  * @param defaultExtension an optional default extension
  */
case class RadioSource(uri: String, defaultExtension: Option[String] = None)
