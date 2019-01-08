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

package de.oliver_heger.linedj.archiveadmin

import de.oliver_heger.linedj.platform.app.ConsumerRegistrationProviderTestHelper
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.{ArchiveAvailabilityExtension, StateListenerExtension}
import de.oliver_heger.linedj.shared.archive.metadata._
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.components.model.StaticTextData
import net.sf.jguiraffe.gui.forms.Form
import net.sf.jguiraffe.transform.{Transformer, TransformerContext}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object ArchiveAdminControllerSpec {
  /** A test state object with data from the media archive. */
  private val CurrentState = MetaDataStateUpdated(MetaDataState(mediaCount = 28,
    songCount = 1088, size = 20161020213108L, duration = ((3 * 60 * 60 + 25 * 60) + 14) * 1000L,
    scanInProgress = false, updateInProgress = false))

  /** Text constant for an archive not available. */
  private val TextArchiveUnavailable = "No archive"

  /** Text constant for a scan in progress. */
  private val TextScanInProgress = "Scanning..."

  /** Text constant for no scan in progress. */
  private val TextNoScanInProgress = "Idle..."

  /** Icon constant for an archive not available. */
  private val IconArchiveUnavailable = new Object

  /** Icon constant for a scan in progress. */
  private val IconScanInProgress = new Object

  /** Icon constant for no scan in progress. */
  private val IconNoScanInProgress = new Object

  /**
    * Produces a transformed string from the given object.
    *
    * @param o the object
    * @return the transformed string
    */
  private def transformedString(o: Any): String = o + "_transformed"

  /**
    * Produces a state updated event with the specified operation in progress
    * flag.
    *
    * @param inProgress the in progress flag
    * @return the update event
    */
  private def stateWithInProgressFlag(inProgress: Boolean): MetaDataStateUpdated =
  if (CurrentState.state.scanInProgress == inProgress) CurrentState
  else MetaDataStateUpdated(CurrentState.state.copy(updateInProgress = inProgress))
}

/**
  * Test class for ''ArchiveAdminController''.
  */
class ArchiveAdminControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import ArchiveAdminControllerSpec._
  import ConsumerRegistrationProviderTestHelper._

  /**
    * Tests whether the text property of the specified text data object
    * contains the expected transformed value.
    *
    * @param data  the text data
    * @param value the expected value
    */
  private def checkTransformedText(data: StaticTextData, value: Any): Unit = {
    data.getText should be(transformedString(value))
  }

  "An ArchiveAdminController" should "use correct consumer IDs" in {
    val helper = new ArchiveAdminControllerTestHelper

    checkRegistrationIDs(helper.controller)
  }

  it should "set the property for the media count" in {
    val helper = new ArchiveAdminControllerTestHelper

    checkTransformedText(helper.sendMetaDataStateEvent(CurrentState)
      .verifyFormUpdate().mediaCount, CurrentState.state.mediaCount)
  }

  it should "ignore irrelevant meta data state events" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataScanCanceled)
        .sendMetaDataStateEvent(MetaDataScanCompleted)
    verifyZeroInteractions(helper.form)
  }

  it should "set the property for the song count" in {
    val helper = new ArchiveAdminControllerTestHelper

    checkTransformedText(helper.sendMetaDataStateEvent(CurrentState)
      .verifyFormUpdate().songCount, CurrentState.state.songCount)
  }

  it should "set the property for the file size" in {
    val helper = new ArchiveAdminControllerTestHelper
    val SizeInMegaBytes = CurrentState.state.size / 1024.0 / 1024.0

    checkTransformedText(helper.sendMetaDataStateEvent(CurrentState)
      .verifyFormUpdate().fileSize, SizeInMegaBytes)
  }

  it should "set the property for the playback duration" in {
    val helper = new ArchiveAdminControllerTestHelper

    checkTransformedText(helper.sendMetaDataStateEvent(CurrentState)
      .verifyFormUpdate().playbackDuration, "3:25:14")
  }

  it should "set the archive state if the archive becomes unavailable" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendAvailabilityEvent(MediaFacade.MediaArchiveUnavailable)
      .verifyArchiveState(TextArchiveUnavailable, IconArchiveUnavailable)
  }

  it should "set the archive state if a media scan is in progress" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(stateWithInProgressFlag(inProgress = true))
      .verifyArchiveState(TextScanInProgress, IconScanInProgress)
  }

  it should "set the archive state if no media scan is in progress" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(stateWithInProgressFlag(inProgress = false))
      .verifyArchiveState(TextNoScanInProgress, IconNoScanInProgress)
  }

  it should "set the archive state if an update operation starts" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataUpdateInProgress)
      .verifyArchiveState(TextScanInProgress, IconScanInProgress)
  }

  it should "not update the archive status when the archive becomes available" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendAvailabilityEvent(MediaFacade.MediaArchiveAvailable)
    verify(helper.form, never()).initFields(any())
  }

  it should "update the archive state if an update operation is complete" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataUpdateCompleted)
      .verifyArchiveState(TextNoScanInProgress, IconNoScanInProgress)
  }

  it should "disable actions if the archive is not available" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendAvailabilityEvent(MediaFacade.MediaArchiveUnavailable)
      .verifyAction("startScanAction", enabled = false)
      .verifyAction("cancelScanAction", enabled = false)
      .verifyAction("metaDataFilesAction", enabled = false)
  }

  it should "update action states if an update operation starts" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataUpdateInProgress)
      .verifyAction("startScanAction", enabled = false)
      .verifyAction("cancelScanAction", enabled = true)
      .verifyAction("metaDataFilesAction", enabled = false)
  }

  it should "update action states if a scan is in progress" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(stateWithInProgressFlag(inProgress = true))
      .verifyAction("startScanAction", enabled = false)
      .verifyAction("cancelScanAction", enabled = true)
      .verifyAction("metaDataFilesAction", enabled = false)
  }

  it should "update action states if no scan is in progress" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(stateWithInProgressFlag(inProgress = false))
      .verifyAction("startScanAction", enabled = true)
      .verifyAction("cancelScanAction", enabled = false)
      .verifyAction("metaDataFilesAction", enabled = true)
  }

  it should "update action states if an update operation is complete" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataUpdateCompleted)
      .verifyAction("startScanAction", enabled = true)
      .verifyAction("cancelScanAction", enabled = false)
      .verifyAction("metaDataFilesAction", enabled = true)
  }

  /**
    * A test helper managing a test instance and all of its dependencies.
    */
  private class ArchiveAdminControllerTestHelper {
    /** A mock for the form. */
    val form = mock[Form]

    /** A mock for the transformer context. */
    val transformerContext = mock[TransformerContext]

    /** A map with mocks for the actions managed by the controller. */
    val actions = createActions()

    /** The controller to be tested. */
    val controller = createController()

    /**
      * Sends the specified availability event to the test controller.
      *
      * @param event the event to be sent
      * @return this test helper
      */
    def sendAvailabilityEvent(event: MediaFacade.MediaArchiveAvailabilityEvent):
    ArchiveAdminControllerTestHelper = {
      findRegistration[ArchiveAvailabilityExtension.ArchiveAvailabilityRegistration](controller)
        .callback(event)
      this
    }

    /**
      * Sends the specified meta data state event to the test controller.
      *
      * @param event the event to be sent
      * @return this test helper
      */
    def sendMetaDataStateEvent(event: MetaDataStateEvent): ArchiveAdminControllerTestHelper = {
      findRegistration[StateListenerExtension.StateListenerRegistration](controller)
        .callback(event)
      this
    }

    /**
      * Verifies that the form was updated and returns the passed in form
      * bean.
      *
      * @return the form bean
      */
    def verifyFormUpdate(): ArchiveAdminUIData = {
      val captor = ArgumentCaptor forClass classOf[ArchiveAdminUIData]
      verify(form).initFields(captor.capture())
      captor.getValue
    }

    /**
      * Verifies that the archive state label has been set correctly.
      *
      * @param text the expected text
      * @param icon the expected icon
      * @return this test helper
      */
    def verifyArchiveState(text: String, icon: AnyRef): ArchiveAdminControllerTestHelper = {
      val stateData = verifyFormUpdate().archiveStatus
      stateData.getText should be(text)
      stateData.getIcon should be(icon)
      this
    }

    /**
      * Verifies that the specified action has been set to the given enabled
      * state.
      *
      * @param name    the name of the action
      * @param enabled the expected enabled state
      * @return this test helper
      */
    def verifyAction(name: String, enabled: Boolean): ArchiveAdminControllerTestHelper = {
      verify(actions(name)).setEnabled(enabled)
      this
    }

    /**
      * Creates a dummy transformer. The transformer converts the passed in
      * object to a string and appends the suffix ''_transformed''.
      *
      * @return the dummy transformer
      */
    private def createTransformer(): Transformer = {
      new Transformer {
        override def transform(o: scala.Any, ctx: TransformerContext): AnyRef = {
          ctx should be(transformerContext)
          transformedString(o)
        }
      }
    }

    /**
      * Generates a map with mock actions.
      *
      * @return the map with mock actions
      */
    private def createActions(): Map[String, FormAction] =
    List("startScanAction", "cancelScanAction", "metaDataFilesAction")
      .map((_, mock[FormAction])).toMap

    /**
      * Creates a mock action store object that supports the specified actions.
      *
      * @param actions a map with mock actions
      * @return the mock action store
      */
    private def createActionStore(actions: Map[String, FormAction]): ActionStore = {
      val store = mock[ActionStore]
      when(store.getAction(anyString())).thenAnswer(new Answer[FormAction] {
        override def answer(invocation: InvocationOnMock): FormAction =
          actions(invocation.getArguments.head.asInstanceOf[String])
      })
      store
    }

    /**
      * Creates the test controller instance.
      *
      * @return the test controller
      */
    private def createController(): ArchiveAdminController = {
      val ctrl = new ArchiveAdminController(createBuilderData(), createTransformer())
      ctrl setStateUnavailableText TextArchiveUnavailable
      ctrl setStateUnavailableIcon IconArchiveUnavailable
      ctrl setStateScanInProgressText TextScanInProgress
      ctrl setStateScanInProgressIcon IconScanInProgress
      ctrl setStateNoScanInProgressText TextNoScanInProgress
      ctrl setStateNoScanInProgressIcon IconNoScanInProgress
      ctrl
    }

    /**
      * Creates a mock builder data object.
      *
      * @return the mock builder data
      */
    private def createBuilderData(): ComponentBuilderData = {
      val beanContext = mock[BeanContext]
      val data = mock[ComponentBuilderData]
      when(data.getForm).thenReturn(form)
      when(data.getTransformerContext).thenReturn(transformerContext)
      when(data.getBeanContext).thenReturn(beanContext)
      doReturn(createActionStore(actions)).when(beanContext).getBean("ACTION_STORE")
      data
    }
  }

}
