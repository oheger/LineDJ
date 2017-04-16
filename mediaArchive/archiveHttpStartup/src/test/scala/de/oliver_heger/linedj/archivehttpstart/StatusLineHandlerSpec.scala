/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart

import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates._
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object StatusLineHandlerSpec {
  /** A prefix for generating texts for states of the archive. */
  private val PrefixStateText = "Text for state "

  /** An icon for an active archive. */
  private val IconActive = new Object

  /** An icon for an inactive archive. */
  private val IconInactive = new Object

  /**
    * Generates a text for the state of the HTTP archive.
    *
    * @param state the state
    * @return the resulting text for this state
    */
  private def stateText(state: HttpArchiveState): String =
    PrefixStateText + state.name
}

/**
  * Test class for ''StatusLineHandler''.
  */
class StatusLineHandlerSpec extends FlatSpec with Matchers with MockitoSugar {

  import StatusLineHandlerSpec._

  "A StatusLineHandler" should "handle the archive not available state" in {
    val helper = new StatusLineHandlerTestHelper

    helper.initState(HttpArchiveStateNoUnionArchive)
      .verifyStateText(HttpArchiveStateNoUnionArchive)
      .verifyStateIcon(IconInactive)
  }

  it should "handle the not logged in state" in {
    val helper = new StatusLineHandlerTestHelper

    helper.initState(HttpArchiveStateNotLoggedIn)
      .verifyStateText(HttpArchiveStateNotLoggedIn)
      .verifyStateIcon(IconInactive)
  }

  it should "handle the available state" in {
    val helper = new StatusLineHandlerTestHelper

    helper.initState(HttpArchiveStateAvailable)
      .verifyStateText(HttpArchiveStateAvailable)
      .verifyStateIcon(IconActive)
  }

  it should "handle the invalid configuration state" in {
    val helper = new StatusLineHandlerTestHelper

    helper.initState(HttpArchiveStateInvalidConfig)
      .verifyStateText(HttpArchiveStateInvalidConfig)
      .verifyStateIcon(IconInactive)
  }

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class StatusLineHandlerTestHelper {
    /** Mock for the status line component handler. */
    private val staticHandler = mock[StaticTextHandler]

    /** The status line handler to be tested. */
    private val handler = createHandler()

    /**
      * Initializes the test instance with the given state.
      *
      * @param state the archive state
      * @return this test helper
      */
    def initState(state: HttpArchiveState): StatusLineHandlerTestHelper = {
      handler archiveStateChanged state
      this
    }

    /**
      * Verifies that the correct text has been set for the given state.
      *
      * @param state the state
      * @return this test helper
      */
    def verifyStateText(state: HttpArchiveState): StatusLineHandlerTestHelper = {
      verify(staticHandler).setText(stateText(state))
      this
    }

    /**
      * Verifies that the correct icon has been set for the given state.
      *
      * @param icon the icon
      * @return this test helper
      */
    def verifyStateIcon(icon: AnyRef): StatusLineHandlerTestHelper = {
      verify(staticHandler).setIcon(icon)
      this
    }

    /**
      * Creates a handler instance to be tested.
      *
      * @return the test instance
      */
    private def createHandler(): StatusLineHandler = {
      new StatusLineHandler(staticHandler,
        stateText(HttpArchiveStateInvalidConfig),
        stateText(HttpArchiveStateNoUnionArchive),
        stateText(HttpArchiveStateNotLoggedIn),
        stateText(HttpArchiveStateAvailable), IconInactive, IconActive)
    }
  }

}
