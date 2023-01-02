/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import net.sf.jguiraffe.gui.builder.components.model.ListComponentHandler
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener, FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener, WindowUtils}
import net.sf.jguiraffe.gui.forms.ComponentHandler

import scala.concurrent.ExecutionContext.Implicits.global

object ReorderController {
  /** The command key for the OK button. */
  private val CommandOK = "OK"
}

/**
  * The controller class for a dialog allowing the user to reorder parts of the
  * current playlist.
  *
  * The dialog displays a list with all currently available reorder services.
  * The user can select one and trigger the reorder operation. This controller
  * class communicates with a [[ReorderService]] instance.
  *
  * @param listHandler    the handler for the list component
  * @param buttonHandler  the handler to the OK button
  * @param reorderService the ''ReorderService'' to be used
  * @param sync           the object for synchronizing with the UI thread
  * @param songs          the sequence with the songs to be sorted
  * @param startIndex     the index of the first song in the playlist
  */
class ReorderController(listHandler: ListComponentHandler, buttonHandler: ComponentHandler[_],
                        reorderService: ReorderService, sync: GUISynchronizer,
                        songs: Seq[SongData], startIndex: Int)
  extends WindowListener with FormActionListener with FormChangeListener {
  import ReorderController._

  /** Stores the window associated with this controller. */
  private var window: Window = _

  override def windowDeiconified(windowEvent: WindowEvent): Unit = {}

  override def windowClosing(windowEvent: WindowEvent): Unit = {}

  override def windowClosed(windowEvent: WindowEvent): Unit = {}

  override def windowActivated(windowEvent: WindowEvent): Unit = {}

  override def windowDeactivated(windowEvent: WindowEvent): Unit = {}

  override def windowIconified(windowEvent: WindowEvent): Unit = {}

  /**
    * @inheritdoc This implementation starts an asynchronous request for the
    *             available reorder services. When the response arrives the
    *             list model is updated.
    */
  override def windowOpened(windowEvent: WindowEvent): Unit = {
    window = WindowUtils windowFromEvent windowEvent
    buttonHandler setEnabled false
    reorderService.loadAvailableReorderServices() foreach { list =>
      sync.asyncInvoke(new Runnable {
        override def run(): Unit = {
          fillListModel(list)
        }
      })
    }
  }

  /**
    * Reacts on a click on one of the buttons. For the OK button the selected
    * reorder service is obtained and invoked. In any case the window is
    * closed.
    *
    * @param formActionEvent the action event
    */
  override def actionPerformed(formActionEvent: FormActionEvent): Unit = {
    if (CommandOK == formActionEvent.getCommand) {
      val service = listHandler.getData.asInstanceOf[PlaylistReorderer]
      reorderService.reorder(service, songs, startIndex)
    }
    window.close(true)
  }

  /**
    * Reacts on changes of the list selection. Depending on the presence of a
    * selection, the enabled state of the OK button has to be updated.
    *
    * @param formChangeEvent the change event
    */
  override def elementChanged(formChangeEvent: FormChangeEvent): Unit = {
    buttonHandler.setEnabled(listHandler.getData != null)
  }

  /**
    * Fills the model of the list component with the specified services.
    *
    * @param list the list with information about reorder services
    * @return a flag whether there is at least one service to choose
    */
  private def fillListModel(list: Seq[(PlaylistReorderer, String)]): Boolean = {
    val sortedList = list.sortWith(_._2 < _._2)
    sortedList.zipWithIndex.foreach { e =>
      val index = e._2
      val name = e._1._2
      val service = e._1._1
      listHandler.addItem(index, name, service)
    }
    sortedList.headOption foreach (listHandler setData _._1)
    list.nonEmpty
  }
}
