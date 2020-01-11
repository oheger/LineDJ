/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.platform.ui.EmptyListModel
import de.oliver_heger.linedj.player.engine.RadioSource

/**
  * The list model class for the combo box with radio sources.
  *
  * This model is empty initially. It is then filled when the radio sources
  * available are read from the application's configuration.
  */
class EmptyRadioSourcesListModel extends EmptyListModel {
  override val getType = classOf[RadioSource]
}
