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

package de.oliver_heger.linedj.archiveadmin.validate

import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextData, StaticTextHandler}
import net.sf.jguiraffe.resources.Message
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object StatusLineHandlerSpec {
  /** The icon for in progress. */
  private val IconProgress = new Object

  /** The icon for a successful operation. */
  private val IconSuccess = new Object

  /** The icon for an operation with errors. */
  private val IconError = new Object

  /**
    * A text for the status line. The mock for the application context is
    * prepared to return this text for the correct parameters.
    */
  private val TextExpected = "The expected text for the status line"

  /**
    * Another text for the status line. This text is returned by the
    * application context mock per default.
    */
  private val TextOther = "Wrong text"

  /** Test number of errors. */
  private val ErrorCount = 11

  /** Test number of warnings. */
  private val WarningCount = 42

  /** The message for the result status line. */
  private val StatusMessage = new Message(null, StatusLineHandler.ResResult, ErrorCount, WarningCount)
}

/**
  * Test class for ''StatusLineHandler''.
  */
class StatusLineHandlerSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import StatusLineHandlerSpec._

  "A StatusLineHandler" should "switch to the fetching media state" in {
    val helper = new StatusHandlerTestHelper

    helper.expectResource(StatusLineHandler.ResFetchingMedia)
      .invokeHandler(_.fetchingMedia())
      .verifyStatusLine(IconProgress)
  }

  it should "update the validation progress" in {
    val helper = new StatusHandlerTestHelper

    helper.expectResource(StatusMessage)
      .invokeHandler(_.updateProgress(ErrorCount, WarningCount))
      .verifyStatusLine(IconProgress)
  }

  it should "display validation results if everything was successful" in {
    val helper = new StatusHandlerTestHelper

    helper.expectResource(StatusMessage)
      .invokeHandler(_.validationResults(ErrorCount, WarningCount, successful = true))
      .verifyStatusLine(IconSuccess)
  }

  it should "display validation results if there were errors" in {
    val helper = new StatusHandlerTestHelper

    helper.expectResource(StatusMessage)
      .invokeHandler(_.validationResults(ErrorCount, WarningCount, successful = false))
      .verifyStatusLine(IconError)
  }

  /**
    * Test helper class managing a handler to be tested and its dependencies.
    */
  private class StatusHandlerTestHelper {
    /** Mock for the application context. */
    private val applicationContext = createApplicationContext()

    /** Mock for the static text handler representing the status line. */
    private val textHandler = createTextHandler()

    /** The status handler to be tested. */
    private val statusHandler = createStatusHandler()

    /** Stores the latest data that was set for the status line. */
    private var currentData: StaticTextData = _

    /**
      * Triggers an invocation of the status line handler. The given invocation
      * function is called with the managed handler.
      *
      * @param inv the invocation function
      * @return this test helper
      */
    def invokeHandler(inv: StatusLineHandler => Unit): StatusHandlerTestHelper = {
      inv(statusHandler)
      this
    }

    /**
      * Prepares the mock application context to expect a resource request for
      * the given resource ID. For this ID then the correct text is returned.
      *
      * @param resID the resource ID
      * @return this test helper
      */
    def expectResource(resID: AnyRef): StatusHandlerTestHelper = {
      when(applicationContext.getResourceText(resID)).thenReturn(TextExpected)
      this
    }

    /**
      * Prepares the mock application context to expect a resource request for
      * the given message. For this message then the correct text is returned.
      *
      * @param message the message
      * @return this test helper
      */
    def expectResource(message: Message): StatusHandlerTestHelper = {
      when(applicationContext.getResourceText(message)).thenReturn(TextExpected)
      this
    }

    /**
      * Checks whether the status line has been updated correctly. It must
      * have the given icon and the expected text.
      *
      * @param expIcon the expected icon
      * @return this test helper
      */
    def verifyStatusLine(expIcon: AnyRef): StatusHandlerTestHelper = {
      currentData.getText should be(TextExpected)
      currentData.getIcon should be(expIcon)
      this
    }

    /**
      * Creates a mock application context. The mock is prepared to answer
      * resource requests (with false texts) per default.
      *
      * @return the mock for the application context
      */
    private def createApplicationContext(): ApplicationContext = {
      val context = mock[ApplicationContext]
      when(context.getResourceText(any(classOf[AnyRef]))).thenReturn(TextOther)
      when(context.getResourceText(any(classOf[Message]))).thenReturn(TextOther)
      context
    }

    /**
      * Creates a mock for the static text handler. The mock is prepared to
      * store the data that was passed to it.
      *
      * @return the mock for the static text handler
      */
    private def createTextHandler(): StaticTextHandler = {
      val handler = mock[StaticTextHandler]
      when(handler.setData(any(classOf[StaticTextData]))).thenAnswer((invocation: InvocationOnMock) => {
        currentData = invocation.getArguments.head.asInstanceOf[StaticTextData]
      })
      handler
    }

    /**
      * Creates the status line handler instance to be tested.
      *
      * @return the instance to be tested
      */
    private def createStatusHandler(): StatusLineHandler =
      new StatusLineHandler(applicationContext, textHandler, IconProgress, IconSuccess, IconError)
  }

}
