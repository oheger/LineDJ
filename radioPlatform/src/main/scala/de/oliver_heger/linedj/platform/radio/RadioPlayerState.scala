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

import de.oliver_heger.linedj.player.server.model.RadioModel

/**
  * A data class representing the current status of the radio player engine.
  * An instance contains information if radio playback is currently active,
  * which radio source is selected, which radio sources are available, if there
  * is currently a replacement source active due to exclusion rules,
  * information about the current title, etc. Whenever there is a change in the
  * status, a new instance is published on the message bus. This can be used by
  * consumers for instance to update a radio player UI accordingly.
  *
  * @param radioSources       the list of available radio sources
  * @param optCurrentSourceID the ID of the current source if available
  * @param optReplacementID   the ID of a replacement source if available
  * @param playbackEnabled    flag whether playback is active
  * @param optTitle           title information if available
  */
case class RadioPlayerState(radioSources: List[RadioModel.RadioSource],
                            optCurrentSourceID: Option[String],
                            optReplacementID: Option[String],
                            playbackEnabled: Boolean,
                            optTitle: Option[String]):
  /** A map allowing fast access to radio sources by their ID. */
  private lazy val sourcesMap = radioSources.map(src => src.id -> src).toMap

  /**
    * Returns the [[RadioModel.RadioSource]] for the given ID or an undefined
    * [[Option]] if the ID cannot be resolved.
    *
    * @param sourceID the ID of the radio source in question
    * @return an [[Option]] with the resolved radio source
    */
  def sourceByID(sourceID: String): Option[RadioModel.RadioSource] = sourcesMap.get(sourceID)

  /**
    * Returns an [[Option]] for the currently selected
    * [[RadioModel.RadioSource]].
    *
    * @return an [[Option]] for the currently selected source
    */
  def optCurrentSource: Option[RadioModel.RadioSource] = optCurrentSourceID.flatMap(sourceByID)

  /**
    * Returns an [[Option]] for the current replacement source if any.
    *
    * @return an [[Option]] for the current replacement source
    */
  def optReplacementSource: Option[RadioModel.RadioSource] = optReplacementID.flatMap(sourceByID)
