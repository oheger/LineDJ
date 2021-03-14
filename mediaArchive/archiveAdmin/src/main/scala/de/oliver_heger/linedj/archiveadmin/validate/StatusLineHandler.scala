/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archiveadmin.validate

import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.gui.builder.components.tags.StaticTextDataImpl
import net.sf.jguiraffe.resources.Message

object StatusLineHandler {
  /** Resource ID for the status ''fetching media''. */
  val ResFetchingMedia = "validation_status_fetch_media"

  /**
    * Resource ID for the (temporary) validation result. Here a text is
    * expected that displays the number of warnings and errors. The text is
    * used while the validation is still in progress and for the final result.
    */
  val ResResult = "validation_status_result"
}

/**
  * A class that manages the status line of the validation results dialog.
  *
  * This class offers some life-cycle methods that are called during the
  * validation process. This leads to updates of the status line, so that the
  * user is informed about the progress.
  *
  * All methods defined by this class are expected to be called on the UI
  * thread.
  *
  * @param applicationContext the application context
  * @param textHandler        the handler of the status line text control
  * @param iconProgress       the icon indicating an ongoing operation
  * @param iconSuccess        the icon indicating a successful validation
  * @param iconError          the icon indicating a complete validation with errors
  */
class StatusLineHandler(applicationContext: ApplicationContext, textHandler: StaticTextHandler,
                        iconProgress: AnyRef, iconSuccess: AnyRef, iconError: AnyRef) {

  import StatusLineHandler._

  /**
    * Notification that the available media are now fetched. This is the
    * initial state of the validation process.
    */
  def fetchingMedia(): Unit = {
    updateStatusLine(applicationContext.getResourceText(ResFetchingMedia), iconProgress)
  }

  /**
    * Notification about an update of the validation progress. The given number
    * of errors and warnings have been detected so far.
    *
    * @param errors   the number of validation errors
    * @param warnings the number of validation warnings
    */
  def updateProgress(errors: Int, warnings: Int): Unit = {
    updateResults(errors, warnings, iconProgress)
  }

  /**
    * Notification that the validation process is now complete and the final
    * results can be displayed. If there was an error during the validation
    * process, this is indicated via a flag. In this case, the numbers of
    * errors and warnings shown may not be reliable.
    *
    * @param errors     the number of validation errors
    * @param warnings   the number of validation warnings
    * @param successful flag whether the process was successful
    */
  def validationResults(errors: Int, warnings: Int, successful: Boolean): Unit = {
    updateResults(errors, warnings,
      if (successful) iconSuccess else iconError)
  }

  /**
    * Updates the status line with a message regarding validation results and
    * the given icon.
    *
    * @param errors   the number of validation errors
    * @param warnings the number of validation warnings
    * @param icon     the icon to be displayed
    */
  private def updateResults(errors: Int, warnings: Int, icon: AnyRef): Unit = {
    val msg = new Message(null, ResResult, errors, warnings)
    updateStatusLine(applicationContext.getResourceText(msg), icon)
  }

  /**
    * Updates the status line with the given text and icon.
    *
    * @param text the text
    * @param icon the icon
    */
  private def updateStatusLine(text: String, icon: AnyRef): Unit = {
    val data = new StaticTextDataImpl
    data setIcon icon
    data setText text
    textHandler setData data
  }
}
