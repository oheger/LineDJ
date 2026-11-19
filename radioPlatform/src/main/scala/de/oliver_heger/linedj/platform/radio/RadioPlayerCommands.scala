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

package de.oliver_heger.linedj.platform.radio

/**
  * An enumeration defining the commands supported by the radio player engine
  * provided by the radio platform.
  *
  * Concrete elements of this enum can be published on the event bus. They
  * are processed by a special listener component which updates the central
  * radio player accordingly.
  */
enum RadioPlayerCommands:
  /**
    * Command to start radio playback. If playback was paused, it starts again
    * for the same radio source. If playback is already active or if there is
    * no current source, this command has no effect.
    */
  case StartPlayback

  /**
    * Command to stop (pause) radio playback. If playback is currently active,
    * it is stopped. Otherwise, this command has no effect. The current source
    * is not changed.
    */
  case StopPlayback

  /**
    * Command to switch to another radio source. The ID of the desired source
    * needs to be provided. If the ID is unknown, this command has no effect.
    * The playback status is not changed.
    *
    * @param sourceID the ID of the source to become the new current
    */
  case SelectRadioSource(sourceID: String)