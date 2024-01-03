/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.browser.media

import de.oliver_heger.linedj.platform.ui.EmptyListModel

/**
 * A class representing the initial empty list model passed to the combo box
 * for media.
 *
 * The content of the media combo box is calculated dynamically and added to
 * the handler object. Nevertheless, an initial model is needed. This class
 * implements this initial model. It is merely a dummy.
 */
class EmptyMediumListModel extends EmptyListModel:
  override val getType = classOf[String]
