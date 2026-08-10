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

import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message

/**
  * A helper class for managing the status line of the archive browser
  * application.
  *
  * The status line shows information when the application loads data from the
  * archive. During a load operation, a loading indicator widget is visible.
  * When no interaction with the archive takes place, the status line displays
  * the name of the currently selected medium.
  *
  * @param applicationContext the application context
  * @param statusText         the handler for the status line text
  * @param progressIndicator  the widget indicating a load operation
  */
class StatusLineController(applicationContext: ApplicationContext,
                           statusText: StaticTextHandler,
                           progressIndicator: WidgetHandler):
  /** Stores the title of the selected medium if any. */
  private var mediumTitle: Option[String] = None

  /** A counter for the load operations currently in progress. */
  private var loadOperationsInProgress = 0

  /**
    * Updates the title of the currently selected medium. This is an empty
    * [[Option]] if no medium is selected.
    *
    * @param optTitle the optional title of the current medium
    */
  def setMediumTitle(optTitle: Option[String]): Unit =
    mediumTitle = optTitle
    resetStatusText()

  /**
    * Notifies this controller that a request to the archive was triggered.
    * There can be multiple, interleaving requests. This controller keeps track
    * on the requests in progress and sets the visibility of the progress
    * indicator widget accordingly.
    */
  def loadOperationStarts(): Unit =
    if loadOperationsInProgress == 0 then
      progressIndicator.setVisible(true)
    loadOperationsInProgress += 1

  /**
    * Notifies this controller that a request to the archive has finished. If
    * this was the last ongoing request, the default status line text is
    * restored.
    */
  def loadOperationEnds(): Unit =
    if loadOperationsInProgress > 0 then
      loadOperationsInProgress -= 1
      if loadOperationsInProgress == 0 then
        progressIndicator.setVisible(false)
        resetStatusText()

  /**
    * Sets a temporary message to be displayed in the status line. This is
    * typically used to display information about an archive request. When the
    * request completes, the default status line text is displayed again.
    *
    * @param message the message to be displayed
    */
  def setStatusMessage(message: Message): Unit =
    statusText.setText(applicationContext.getResourceText(message))

  /**
    * Resets the text in the status line to the currently selected medium if
    * any.
    */
  private def resetStatusText(): Unit =
    statusText.setText(mediumTitle.getOrElse(""))