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

package de.oliver_heger.linedj.archiveadmin

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.platform.app.ConsumerRegistrationProviderTestHelper
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport.ActorRequest
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.platform.mediaifc.ext.{ArchiveAvailabilityExtension, StateListenerExtension}
import de.oliver_heger.linedj.shared.archive.metadata._
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, ListModel, StaticTextData}
import net.sf.jguiraffe.gui.forms.Form
import net.sf.jguiraffe.transform.{Transformer, TransformerContext}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object ArchiveAdminControllerSpec {
  /** A list with test archive names. */
  private val ArchiveNames = List("Arc1", "Arc2", "Arc3")

  /** A test archive state. */
  private val ArchiveState = MetaDataState(mediaCount = 28,
    songCount = 1088, size = 20161020213108L, duration = ((7 * 24 * 60 * 60 + 3 * 60 * 60 + 25 * 60) + 14) * 1000L,
    scanInProgress = false, updateInProgress = false, archiveCompIDs = ArchiveNames.toSet)

  /** A test state object with data from the media archive. */
  private val CurrentState = MetaDataStateUpdated(ArchiveState)

  /** The name to be displayed for the union archive */
  private val UnionArchiveName = "The union"

  /** Text constant for an archive not available. */
  private val TextArchiveUnavailable = "No archive"

  /** Text constant for a scan in progress. */
  private val TextScanInProgress = "Scanning..."

  /** Text constant for no scan in progress. */
  private val TextNoScanInProgress = "Idle..."

  /** The unit for days */
  private val UnitDays = "Days"

  /** The unit for hours. */
  private val UnitHours = "Hours"

  /** The unit for minutes. */
  private val UnitMinutes = "Minutes"

  /** The unit for seconds. */
  private val UnitSeconds = "Seconds"

  /** Constant for the expected formatted duration. */
  private val ExpDuration = s"7$UnitDays 3$UnitHours 25$UnitMinutes 14$UnitSeconds"

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
  private def transformedString(o: Any): String = s"${o}_transformed"

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

  /**
    * Generates a (long) duration string based on the default duration, but
    * with the given number of seconds.
    *
    * @param seconds the seconds
    * @return the resulting duration string
    */
  private def durationWithSeconds(seconds: Int): String = {
    val posSeconds = ExpDuration.lastIndexOf(' ')
    ExpDuration.substring(0, posSeconds + 1) + seconds + UnitSeconds
  }
}

/**
  * Test class for ''ArchiveAdminController''.
  */
class ArchiveAdminControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("ArchiveAdminControllerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

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
    verifyNoInteractions(helper.form)
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
      .verifyFormUpdate().playbackDuration, ExpDuration)
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
      .verifyAction("metaDataFilesAction", enabled = false)
  }

  it should "update action states if an update operation is complete" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataUpdateCompleted)
      .verifyAction("startScanAction", enabled = true)
      .verifyAction("cancelScanAction", enabled = false)
      .verifyAction("metaDataFilesAction", enabled = false)
  }

  it should "disable the archives combo box when a new scan starts" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataScanStarted)
    verify(helper.comboArchives).setEnabled(false)
    verify(helper.comboArchives, never()).setData(any())
  }

  it should "select the union archive when a new scan starts" in {
    val FirstArchive = "ArchiveToSelect"
    val helper = new ArchiveAdminControllerTestHelper

    helper.initArchivesComboModel(FirstArchive, "Arc1", "Arc2")
      .sendMetaDataStateEvent(MetaDataScanStarted)
    verify(helper.comboArchives).setData(FirstArchive)
  }

  it should "initialize the archives combo box at the end of an update operation" in {
    val state = ArchiveState.copy(updateInProgress = true)
    val helper = new ArchiveAdminControllerTestHelper

    helper.initArchivesComboModel("foo", "bar", "baz")
      .sendMetaDataStateEvent(MetaDataStateUpdated(state))
      .sendMetaDataStateEvent(MetaDataUpdateCompleted)
      .verifyArchivesComboCleared()
      .verifyArchivesComboInitialized(UnionArchiveName :: ArchiveNames: _*)
      .verifyArchiveSelected(UnionArchiveName)
    verify(helper.comboArchives).setEnabled(true)
  }

  it should "initialize the archive combo box when receiving an update event outside an update operation" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.initArchivesComboModel("foo", "bar", "baz")
      .sendMetaDataStateEvent(CurrentState)
      .verifyArchivesComboCleared()
      .verifyArchivesComboInitialized(UnionArchiveName :: ArchiveNames: _*)
  }

  it should "handle an update event that does not contain archive components" in {
    val state = ArchiveState.copy(archiveCompIDs = Set.empty)
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataStateUpdated(state))
      .verifyArchivesComboInitialized(UnionArchiveName)
  }

  it should "not add the union archive entry to the combo if there is only a single archive component" in {
    val ArchiveName = "The one and only archive"
    val state = ArchiveState.copy(archiveCompIDs = Set(ArchiveName))
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataStateUpdated(state))
      .verifyArchivesComboInitialized(ArchiveName)
      .verifyArchiveSelected(ArchiveName)
  }

  it should "display an indicator while requesting statistics for an archive" in {
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .verifyFormUpdate()
    data.mediaCount.getText should be(ArchiveAdminController.LoadingIndicator)
    data.songCount.getText should be(ArchiveAdminController.LoadingIndicator)
    data.fileSize.getText should be(ArchiveAdminController.LoadingIndicator)
    data.playbackDuration.getText should be(ArchiveAdminController.LoadingIndicator)
  }

  it should "fetch and display statistics of an archive" in {
    val stats = ArchiveComponentStatistics(ArchiveNames.head, mediaCount = 12, songCount = 512,
      size = 20200106175620L, duration = CurrentState.state.duration + 3000L)
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .resetFormMock()
      .provideStatistics(Success(stats))
      .verifyFormUpdate()
    checkTransformedText(data.mediaCount, stats.mediaCount)
    checkTransformedText(data.songCount, stats.songCount)
    checkTransformedText(data.fileSize, stats.size / 1024.0 / 1024.0)
    checkTransformedText(data.playbackDuration, durationWithSeconds(17))
  }

  it should "handle a null selection of the archives combo box" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.selectArchiveComponent(null)
    verifyNoInteractions(helper.application)
  }

  it should "cache the statistics of an archive" in {
    val stats = ArchiveComponentStatistics(ArchiveNames.head, mediaCount = 12, songCount = 512,
      size = 20200106192747L, duration = CurrentState.state.duration + 5000L)
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .resetFormMock()
      .initCurrentArchiveComponent("otherArchive")
      .provideStatistics(Success(stats))
      .resetFormMock()
      .selectArchiveComponent(ArchiveNames.head)
      .verifyFormUpdate()
    checkTransformedText(data.mediaCount, stats.mediaCount)
    checkTransformedText(data.songCount, stats.songCount)
    checkTransformedText(data.fileSize, stats.size / 1024.0 / 1024.0)
    checkTransformedText(data.playbackDuration, durationWithSeconds(19))
  }

  it should "handle an error when requesting archive statistics" in {
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .resetFormMock()
      .provideStatistics(Failure(new IOException("Failed")))
      .verifyFormUpdate()
    data.mediaCount.getText should be(ArchiveAdminController.ErrorIndicator)
    data.songCount.getText should be(ArchiveAdminController.ErrorIndicator)
    data.fileSize.getText should be(ArchiveAdminController.ErrorIndicator)
    data.playbackDuration.getText should be(ArchiveAdminController.ErrorIndicator)
  }

  it should "only update retrieved archive statistics if the selection is still correct" in {
    val stats = ArchiveComponentStatistics(ArchiveNames.head, mediaCount = 21, songCount = 272,
      size = 20200106204143L, duration = CurrentState.state.duration + 7000L)
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .initCurrentArchiveComponent("anotherArchive")
      .provideStatistics(Success(stats))
      .verifyFormUpdate()
    data.mediaCount.getText should be(ArchiveAdminController.LoadingIndicator)
  }

  it should "only update the UI for failed statistics if the selection is still correct" in {
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .initCurrentArchiveComponent("anotherArchive")
      .provideStatistics(Failure(new IOException("Another failure")))
      .verifyFormUpdate()
    data.mediaCount.getText should be(ArchiveAdminController.LoadingIndicator)
  }

  it should "handle the selection of the union archive correctly" in {
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.sendMetaDataStateEvent(CurrentState)
      .resetFormMock()
      .prepareArchiveStatsRequestAndSelect(UnionArchiveName)
      .verifyFormUpdate()
    checkTransformedText(data.mediaCount, CurrentState.state.mediaCount)
    checkTransformedText(data.songCount, CurrentState.state.songCount)
    checkTransformedText(data.fileSize, CurrentState.state.size / 1024.0 / 1024.0)
    checkTransformedText(data.playbackDuration, ExpDuration)
  }

  it should "handle the selection of the union archive if there is only a single archive" in {
    val ArchiveName = "SingleArchive"
    val state = CurrentState.state.copy(archiveCompIDs = Set(ArchiveName))
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.sendMetaDataStateEvent(MetaDataStateUpdated(state))
      .resetFormMock()
      .prepareArchiveStatsRequestAndSelect(ArchiveName)
      .verifyFormUpdate()
    checkTransformedText(data.mediaCount, CurrentState.state.mediaCount)
  }

  it should "reset the meta data cache when the archive combo box model is updated" in {
    val stats1 = ArchiveComponentStatistics(ArchiveNames.head, mediaCount = 4, songCount = 123,
      size = 20200107192500L, duration = CurrentState.state.duration + 8000L)
    val stats2 = ArchiveComponentStatistics(ArchiveNames.head, mediaCount = 5, songCount = 234,
      size = 20200107192708L, duration = CurrentState.state.duration + 9000L)
    val helper = new ArchiveAdminControllerTestHelper

    val data = helper.sendMetaDataStateEvent(CurrentState)
      .prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .provideStatistics(Success(stats1))
      .sendMetaDataStateEvent(CurrentState)
      .prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .resetFormMock()
      .provideStatistics(Success(stats2))
      .verifyFormUpdate()
    checkTransformedText(data.mediaCount, stats2.mediaCount)
    checkTransformedText(data.songCount, stats2.songCount)
    checkTransformedText(data.fileSize, stats2.size / 1024.0 / 1024.0)
    checkTransformedText(data.playbackDuration, durationWithSeconds(23))
  }

  it should "update the reference of the selected archive" in {
    val ArchiveName = "CurrentSelection"
    val helper = new ArchiveAdminControllerTestHelper

    helper.prepareArchiveStatsRequestAndSelect(ArchiveName)
      .verifySelectionReference(ArchiveName)
  }

  it should "enable actions if the union archive is selected" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.selectArchiveComponent(UnionArchiveName)
      .verifyAction("metaDataFilesAction", enabled = false)
  }

  it should "enable actions if the union archive is not selected" in {
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(CurrentState)
      .prepareArchiveStatsRequestAndSelect(ArchiveNames.head)
      .verifyAction("metaDataFilesAction", enabled = true)
  }

  it should "enable the meta data files action if the single archive is selected" in {
    val ArchiveName = "The one and only archive"
    val state = ArchiveState.copy(archiveCompIDs = Set(ArchiveName))
    val helper = new ArchiveAdminControllerTestHelper

    helper.sendMetaDataStateEvent(MetaDataStateUpdated(state))
      .selectArchiveComponent(ArchiveName)
      .verifyAction("metaDataFilesAction", enabled = true)
  }

  /**
    * A test helper managing a test instance and all of its dependencies.
    */
  private class ArchiveAdminControllerTestHelper {
    /** A mock for the form. */
    val form: Form = mock[Form]

    /** A test probe for the union meta data manager actor. */
    private val probeMetaDataActor = TestProbe()

    /** A mock for the main application. */
    val application: ArchiveAdminApp = createApplication()

    /** A mock for the transformer context. */
    val transformerContext: TransformerContext = mock[TransformerContext]

    /** The mock for the list model of the archives combo box. */
    private val archivesListModel = mock[ListModel]

    /** A mock for the combo box. */
    val comboArchives: ListComponentHandler = createComboHandler()

    /** A map with mocks for the actions managed by the controller. */
    val actions: Map[String, FormAction] = createActions()

    /** The reference for the currently selected archive. */
    private val refArchive = new AtomicReference[String]

    /** The controller to be tested. */
    val controller: ArchiveAdminController = createController()

    /** Stores a mock actor request sent by the controller. */
    private var actorRequest: ActorClientSupport.ActorRequest = _

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
      * Prepares the list model for the archives combo box to return the given
      * archive names.
      *
      * @param archives the archives in the combo box
      * @return this test helper
      */
    def initArchivesComboModel(archives: String*): ArchiveAdminControllerTestHelper = {
      when(archivesListModel.size()).thenReturn(archives.length)
      archives.zipWithIndex.foreach { t =>
        doReturn(t._1).when(archivesListModel).getValueObject(t._2)
      }
      this
    }

    /**
      * Resets the mock for the form. This can be necessary if there are
      * multiple updates of UI elements.
      *
      * @return this test helper
      */
    def resetFormMock(): ArchiveAdminControllerTestHelper = {
      reset(form)
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
      * Verifies that all items have been removed from the list model of the
      * archives combo box.
      *
      * @return this test helper
      */
    def verifyArchivesComboCleared(): ArchiveAdminControllerTestHelper = {
      (0 until archivesListModel.size()) foreach { idx =>
        verify(comboArchives).removeItem(idx)
      }
      this
    }

    /**
      * Verifies that the given archive names were added to the combo box with
      * archives.
      *
      * @param archives the (ordered) archive names
      * @return this test helper
      */
    def verifyArchivesComboInitialized(archives: String*): ArchiveAdminControllerTestHelper = {
      archives.zipWithIndex.foreach { t =>
        verify(comboArchives).addItem(t._2, t._1, t._1)
      }
      this
    }

    /**
      * Verifies that the archive with the given ID has been selected in the
      * combo box.
      *
      * @param archiveID the archive ID
      * @return this test helper
      */
    def verifyArchiveSelected(archiveID: String): ArchiveAdminControllerTestHelper = {
      verify(comboArchives).setData(archiveID)
      this
    }

    /**
      * Prepares the mock for the application for a request for the statistics
      * of a specific archive. The mock returns a special request object mock
      * that can be used to obtain the executed callback later.
      *
      * @param archiveID the ID of the archive that is requested
      * @return this test helper
      */
    def prepareArchiveStatsRequest(archiveID: String): ArchiveAdminControllerTestHelper = {
      actorRequest = mock[ActorRequest]
      when(application.invokeActor(probeMetaDataActor.ref, GetArchiveComponentStatistics(archiveID)))
        .thenReturn(actorRequest)
      this
    }

    /**
      * Prepares the mock for the combo box handler to return the given current
      * archive component.
      *
      * @param archiveID the archive ID
      * @return this test helper
      */
    def initCurrentArchiveComponent(archiveID: String): ArchiveAdminControllerTestHelper = {
      doReturn(archiveID).when(comboArchives).getData
      this
    }

    /**
      * Simulates the selection of the given archive ID in the combo box.
      *
      * @param archiveID the archive ID
      * @return this test helper
      */
    def selectArchiveComponent(archiveID: String): ArchiveAdminControllerTestHelper = {
      initCurrentArchiveComponent(archiveID)
      controller.archiveSelectionChanged()
      this
    }

    /**
      * Prepares the mock for the application for a request for the statistics
      * of a specific archive and simulates a selection change for this
      * archive.
      *
      * @param archiveID the archive ID
      * @return this test helper
      */
    def prepareArchiveStatsRequestAndSelect(archiveID: String): ArchiveAdminControllerTestHelper = {
      prepareArchiveStatsRequest(archiveID)
      selectArchiveComponent(archiveID)
    }

    /**
      * Verifies that a request for archive statistics is correctly processed
      * and passes the given result to the controller.
      *
      * @param result the result
      * @return this test helper
      */
    def provideStatistics(result: Try[ArchiveComponentStatistics]): ArchiveAdminControllerTestHelper = {
      val callback = verifyArchiveStatsRequestHandled()
      callback(result)
      this
    }

    /**
      * Checks whether the expected archive ID has been passed to the reference
      * for the current selection.
      *
      * @param archiveID the archive ID
      * @return this test helper
      */
    def verifySelectionReference(archiveID: String): ArchiveAdminControllerTestHelper = {
      refArchive.get() should be(archiveID)
      this
    }

    /**
      * Verifies that the response of a request for an archive's statistics is
      * handled on the UI thread and returns the callback function for
      * processing the result.
      *
      * @return the callback function for result processing
      */
    private def verifyArchiveStatsRequestHandled(): Try[ArchiveComponentStatistics] => Unit = {
      val captor = ArgumentCaptor.forClass(classOf[Try[ArchiveComponentStatistics] => Unit])
      verify(actorRequest).executeUIThread(captor.capture())(any(classOf[ClassTag[ArchiveComponentStatistics]]))
      val res = captor.getValue
      res
    }

    /**
      * Creates a mock for the main application and prepares it to return media
      * facade actors.
      *
      * @return the initialized mock
      */
    private def createApplication(): ArchiveAdminApp = {
      val facadeActors = MediaFacadeActors(metaDataManager = probeMetaDataActor.ref, mediaManager = null)
      val app = mock[ArchiveAdminApp]
      when(app.mediaFacadeActors).thenReturn(facadeActors)
      val appCtx = mock[ApplicationContext]
      when(appCtx.getResourceText(ArchiveAdminController.ResUnitDays)).thenReturn(UnitDays)
      when(appCtx.getResourceText(ArchiveAdminController.ResUnitHours)).thenReturn(UnitHours)
      when(appCtx.getResourceText(ArchiveAdminController.ResUnitMinutes)).thenReturn(UnitMinutes)
      when(appCtx.getResourceText(ArchiveAdminController.ResUnitSeconds)).thenReturn(UnitSeconds)
      when(app.getApplicationContext).thenReturn(appCtx)
      app
    }

    /**
      * Creates a dummy transformer. The transformer converts the passed in
      * object to a string and appends the suffix ''_transformed''.
      *
      * @return the dummy transformer
      */
    private def createTransformer(): Transformer = {
      (o: scala.Any, ctx: TransformerContext) => {
        ctx should be(transformerContext)
        transformedString(o)
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
      when(store.getAction(anyString())).thenAnswer((invocation: InvocationOnMock) =>
        actions(invocation.getArguments.head.asInstanceOf[String]))
      store
    }

    /**
      * Creates the mock for the combo box handler for the archives and
      * prepares it to return a mock list model.
      *
      * @return the mock combo box handler
      */
    private def createComboHandler(): ListComponentHandler = {
      val handler = mock[ListComponentHandler]
      when(handler.getListModel).thenReturn(archivesListModel)
      handler
    }

    /**
      * Creates the test controller instance.
      *
      * @return the test controller
      */
    private def createController(): ArchiveAdminController = {
      val ctrl = new ArchiveAdminController(application, createBuilderData(), createTransformer(), comboArchives,
        UnionArchiveName, refArchive)
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
