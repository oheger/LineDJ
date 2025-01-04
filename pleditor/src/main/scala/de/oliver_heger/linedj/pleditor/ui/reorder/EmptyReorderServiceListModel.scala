/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.reorder

import de.oliver_heger.linedj.platform.ui.EmptyListModel
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer

/**
  * The list model for the list with available reorder services.
  *
  * This model is initially empty. It is filled at runtime when information
  * about available reorder services becomes available. Therefore, this
  * model is just a dummy; the required functionality is implemented by the
  * base trait.
  */
class EmptyReorderServiceListModel extends EmptyListModel:
  override val getType = classOf[PlaylistReorderer]
