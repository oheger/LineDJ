/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio2

/**
  * An enumeration defining the commands supported by the audio player engine
  * provided by the audio platform.
  *
  * Concrete elements of this enum can be published on the event bus. They
  * are processed by a special listener component which updates the central
  * audio player accordingly.
  */
enum AudioPlayerCommands:
  /**
    * A command which appends a list of songs to the current playlist.
    *
    * If the playlist has not yet been closed, all songs referenced by this
    * message are appended to the current playlist. Optionally, the playlist
    * can be closed then.
    *
    * With the _activate_ flag a hint can be given to the platform whether the
    * current playlist is expected to be played immediately. In this case, the
    * new songs can be passed directly to the player engine, which might trigger
    * some actions, like initiating downloads for song files. A value of
    * *false* means that the playlist may be changed again before it is
    * finalized; in this case, no actions need to be taken yet. It is, however,
    * up to the audio platform to decide how to handle this flag. Typically, if
    * the playlist has already been activated, the flag is ignored, and new songs
    * will be propagated to the player engine.
    *
    * @param songIDs       list of songs to be appended to the playlist
    * @param closePlaylist flag whether the playlist is to be closed
    * @param activate      a hint whether new songs should become active
    */
  case AppendPlaylist(songIDs: Iterable[String],
                      closePlaylist: Boolean = false,
                      activate: Boolean = true)
