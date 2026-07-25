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

package de.oliver_heger.linedj.apps.archive.login

import de.oliver_heger.linedj.apps.archive.login.ArchiveStatusHelperSpec.TestLoginState
import de.oliver_heger.linedj.archive.server.cloud.model.CloudArchiveModel
import de.oliver_heger.linedj.platform.archiveclient.LoginService
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ArchiveStatusHelperSpec:
  /** The test login state. */
  private val TestLoginState = LoginService.ArchiveLoginState(
    waitingArchives = Set("waiting"),
    loadedArchives = Set("loaded"),
    failedArchives = Map(
      "failed" -> CloudArchiveModel.FailedArchive("failed", "wrong credentials", 3)
    ),
    fileCredentials = Set.empty,
    archiveCredentials = Set.empty
  )

  /** An object simulating the waiting icon. */
  private val WaitingIcon = "waiting"

  /** An object simulating the loaded icon. */
  private val LoadedIcon = "loaded"

  /** An object simulating the error icon. */
  private val ErrorIcon = "error"
end ArchiveStatusHelperSpec

/**
  * Test class for [[ArchiveStatusHelper]].
  */
class ArchiveStatusHelperSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import ArchiveStatusHelperSpec.*

  "stateFor" should "return the state for a loaded archive" in :
    ArchiveStatusHelper.stateFor("loaded", TestLoginState) should be(ArchiveStatusHelper.ArchiveState.Loaded)

  it should "return the state of a failed archive" in :
    val expectedState = ArchiveStatusHelper.ArchiveState.Failed("wrong credentials", 3)

    ArchiveStatusHelper.stateFor("failed", TestLoginState) should be(expectedState)

  it should "return the state of a waiting archive" in :
    ArchiveStatusHelper.stateFor("waiting", TestLoginState) should be(ArchiveStatusHelper.ArchiveState.Waiting)

  it should "return the state for an unknown archive" in :
    ArchiveStatusHelper.stateFor("unknown-archive", TestLoginState) should be(ArchiveStatusHelper.ArchiveState.Waiting)

  "An ArchiveStatusHelper" should "return the icon for the waiting state" in :
    val helper = new ArchiveStatusHelper(mock, mock, WaitingIcon, LoadedIcon, ErrorIcon)

    helper.iconForState(ArchiveStatusHelper.ArchiveState.Waiting) should be(WaitingIcon)

  it should "return the icon for the loaded state" in :
    val helper = new ArchiveStatusHelper(mock, mock, WaitingIcon, LoadedIcon, ErrorIcon)

    helper.iconForState(ArchiveStatusHelper.ArchiveState.Loaded) should be(LoadedIcon)

  it should "return the icon for the error state" in :
    val state = ArchiveStatusHelper.ArchiveState.Failed("someFailure", 42)
    val helper = new ArchiveStatusHelper(mock, mock, WaitingIcon, LoadedIcon, ErrorIcon)

    helper.iconForState(state) should be(ErrorIcon)

  /**
    * Checks whether the status line text is correctly set for a specific
    * archive state.
    *
    * @param state           the archive state
    * @param expectedMessage the expected message
    */
  private def checkStatusLineUpdate(state: ArchiveStatusHelper.ArchiveState, expectedMessage: Message): Unit =
    val appContext = mock[ApplicationContext]
    val statusHandler = mock[StaticTextHandler]
    val resourceText = "Some resource text"
    when(appContext.getResourceText(expectedMessage)).thenReturn(resourceText)

    val helper = new ArchiveStatusHelper(
      appContext,
      statusHandler,
      WaitingIcon,
      LoadedIcon,
      ErrorIcon
    )
    helper.updateStatusLine(state)

    verify(statusHandler).setText(resourceText)

  it should "update the status line for a waiting state" in :
    val expectedMessage = new Message("state_waiting")
    checkStatusLineUpdate(ArchiveStatusHelper.ArchiveState.Waiting, expectedMessage)

  it should "update the status line for a loaded state" in :
    val expectedMessage = new Message("state_loaded")
    checkStatusLineUpdate(ArchiveStatusHelper.ArchiveState.Loaded, expectedMessage)

  it should "update the status line for a failed state" in :
    val state = ArchiveStatusHelper.ArchiveState.Failed("some error", 3)
    val expectedMessage = new Message(null, "state_failed", "some error", 3)
    checkStatusLineUpdate(state, expectedMessage)

  it should "clear the status text" in :
    val statusHandler = mock[StaticTextHandler]

    val helper = new ArchiveStatusHelper(mock, statusHandler, WaitingIcon, LoadedIcon, ErrorIcon)
    helper.clearStatusLine()

    verify(statusHandler).setText(null)
