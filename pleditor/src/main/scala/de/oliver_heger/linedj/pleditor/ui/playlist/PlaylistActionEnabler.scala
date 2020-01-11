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

package de.oliver_heger.linedj.pleditor.ui.playlist

import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}

/**
  * A class which keeps track on the enabled state of actions that manipulate
  * the current playlist.
  *
  * This class is registered for selection change events at the table with the
  * playlist. It controls the enabled state of a list of
  * [[PlaylistManipulator]] objects by manipulating the associated actions. A
  * map with the manipulators under control is passed to the constructor. The
  * keys of the map are the names of the corresponding actions.
  *
  * @param actionStore the ''ActionStore''
  * @param tableHandler the handler for table with playlist items
  * @param manipulators the map with the managed manipulators
  */
class PlaylistActionEnabler(actionStore: ActionStore, tableHandler: TableHandler, manipulators:
java.util.Map[String, PlaylistManipulator]) extends FormChangeListener {
  /** The map with the managed manipulators. */
  lazy val manipulatorMap = createManipulatorMap()

  override def elementChanged(formChangeEvent: FormChangeEvent): Unit = {
    val context = PlaylistSelectionContext(tableHandler)
    manipulatorMap.toList foreach { e =>
      val action = actionStore getAction e._1
      action setEnabled e._2.isEnabled(context)
    }
  }

  /**
    * Converts the Java map passed to the constructor to a Scala immutable map.
    * @return the scala map
    */
  private def createManipulatorMap(): Map[String, PlaylistManipulator] = {
    import scala.collection.JavaConversions._
    val scalaMap: Map[String, PlaylistManipulator] = manipulators.toMap
    scalaMap
  }
}
