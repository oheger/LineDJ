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

package de.oliver_heger.linedj.apps.archive.browser

import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, ListModel}

import scala.collection.mutable.ArrayBuffer
import scala.compiletime.uninitialized

object ListComponentHandlerStub:
  /** A data class describing a single item managed by the stub. */
  private case class ListItem(display: AnyRef, value: AnyRef)
end ListComponentHandlerStub

/**
  * A stub implementation of [[ListComponentHandler]] for testing purposes.
  *
  * This class manages the content of a list or combo box in an in-memory list.
  * The [[ListComponentHandler.addItem()]] and [[ListComponentHandler.removeItem()]]
  * methods add or remove objects in this list; the current content is exposed
  * via the returned [[ListModel]].
  */
class ListComponentHandlerStub extends ListComponentHandler:
  import ListComponentHandlerStub.ListItem

  /** The items managed by this handler. */
  private val items = ArrayBuffer.empty[ListItem]

  /** The current data of this handler. */
  private var data: AnyRef = uninitialized

  /** The enabled state of this handler. */
  private var enabled = true

  /** The list model exposing the managed items. */
  private val model: ListModel = new ListModel:
    override def size(): Int = items.size

    override def getDisplayObject(index: Int): AnyRef = items(index).display

    override def getValueObject(index: Int): AnyRef = items(index).value

    override val getType: Class[AnyRef] = classOf[AnyRef]

  override def getListModel: ListModel = model

  override def addItem(index: Int, display: AnyRef, value: AnyRef): Unit =
    items.insert(index, ListItem(display, value))

  override def removeItem(index: Int): Unit =
    items.remove(index)

  override def getComponent: AnyRef = null

  override def getOuterComponent: AnyRef = null

  override def getData: AnyRef = data

  override def setData(data: AnyRef): Unit =
    this.data = data

  override val getType: Class[AnyRef] = classOf[AnyRef]

  override def isEnabled: Boolean = enabled

  override def setEnabled(f: Boolean): Unit =
    enabled = f

  /**
    * Returns the display objects in the order they occur in the list.
    *
    * @return the display objects managed by this handler
    */
  def displayObjects: List[AnyRef] = items.map(_.display).toList
