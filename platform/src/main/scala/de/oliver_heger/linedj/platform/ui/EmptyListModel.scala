/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.platform.ui

import net.sf.jguiraffe.gui.builder.components.model.ListModel

/**
  * A trait representing an empty list model.
  *
  * Lists and combo boxes in JGUIraffe have a list model which must be
  * defined in the builder script. For some use cases, models are empty
  * initially and are then filled dynamically. This trait simplifies the
  * creation of such an empty model. A concrete implementation just has to
  * define the model's data type.
  */
trait EmptyListModel extends ListModel {
  override def getDisplayObject(i: Int): AnyRef = null

  override def size(): Int = 0

  override def getValueObject(i: Int): AnyRef = null
}
