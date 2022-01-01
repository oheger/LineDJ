/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import akka.http.scaladsl.model.StatusCodes
import de.oliver_heger.linedj.archivehttp.{HttpArchiveStateDisconnected, HttpArchiveStateFailedRequest, HttpArchiveStateServerError}
import de.oliver_heger.linedj.archivehttpstart.app.HttpArchiveStates._
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ArchiveStatusHelperSpec {
  /** A test text to be displayed in the status line. */
  private val StatusText = "Correct state of archive"

  /** An icon for an active archive. */
  private val IconActive = new Object

  /** An icon for an inactive archive. */
  private val IconInactive = new Object

  /** An icon for the pending state. */
  private val IconPending = new Object

  /** An icon for the locked state. */
  private val IconLocked = new Object

  /** An icon for the unlocked state. */
  private val IconUnlocked = new Object
}

/**
  * Test class for ''StatusLineHandler''.
  */
class ArchiveStatusHelperSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import ArchiveStatusHelperSpec._

  "An ArchiveStatusHelper" should "handle the union archive not available state" in {
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(HttpArchiveStateNoUnionArchive, new Message("state_no_archive"))
  }

  it should "handle the not logged in state" in {
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(HttpArchiveStateNotLoggedIn, new Message("state_no_login"))
  }

  it should "handle the locked state" in {
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(HttpArchiveStateLocked, new Message("state_locked"))
  }

  it should "handle the protocol unavailable state" in {
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(HttpArchiveStateNoProtocol, new Message("state_no_protocol"))
  }

  it should "handle the available state" in {
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(HttpArchiveStateAvailable, new Message("state_active"))
  }

  it should "handle the error state for a failed request" in {
    val statusCode = StatusCodes.Unauthorized
    val state = HttpArchiveErrorState(HttpArchiveStateFailedRequest(statusCode))
    val message = new Message(null, "state_failed_request", statusCode.toString)
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(state, message)
  }

  it should "handle the error state for a server error" in {
    val exception = new Exception("Some internal error!")
    val state = HttpArchiveErrorState(HttpArchiveStateServerError(exception))
    val message = new Message(null, "state_server_error", exception.getMessage)
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(state, message)
  }

  it should "handle an unexpected state" in {
    val state = HttpArchiveErrorState(HttpArchiveStateDisconnected)
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(state, new Message("state_invalid"))
  }

  it should "handle the initializing state" in {
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineText(HttpArchiveStateInitializing, new Message("state_initializing"))
  }

  it should "clear the status line" in {
    val helper = new ArchiveStatusTestHelper

    helper.checkStatusLineCleared()
  }

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class ArchiveStatusTestHelper {
    /** Mock for the application context. */
    private val appContext = mock[ApplicationContext]

    /** Mock for the status line component handler. */
    private val staticHandler = mock[StaticTextHandler]

    /** The status line handler to be tested. */
    private val handler = createHandler()

    /**
      * Checks that a correct message for the status line is produced for
      * the specified state.
      *
      * @param state   the archive state
      * @param message the expected message
      */
    def checkStatusLineText(state: HttpArchiveState, message: Message): Unit = {
      when(appContext.getResourceText(message)).thenReturn(StatusText)
      handler.updateStatusLine(state)
      verify(staticHandler).setText(StatusText)
    }

    /**
      * Checks that the status line can be cleared.
      */
    def checkStatusLineCleared(): Unit = {
      handler.clearStatusLine()
      verify(staticHandler).setText(null)
    }

    /**
      * Creates a handler instance to be tested.
      *
      * @return the test instance
      */
    private def createHandler(): ArchiveStatusHelper =
      new ArchiveStatusHelper(appContext, staticHandler, IconInactive, IconActive, IconPending,
        IconLocked, IconUnlocked)
  }

}
