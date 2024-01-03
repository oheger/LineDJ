/*
 * Copyright 2015-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import de.oliver_heger.linedj.archiveadmin.MetaDataFilesController.MetaDataFileData
import de.oliver_heger.linedj.platform.ActionTestHelper
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.app.ConsumerRegistrationProviderTestHelper.{checkRegistrationIDs, findRegistration}
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport.ActorRequest
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.{AvailableMediaRegistration, AvailableMediaUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.StateListenerExtension.{StateListenerRegistration, StateListenerUnregistration}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.*
import de.oliver_heger.linedj.shared.archive.union.GetArchiveMetaDataFileInfo
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent}
import net.sf.jguiraffe.resources.Message
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq as eqArg, *}
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util
import scala.collection.immutable.IndexedSeq
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object MetaDataFilesControllerSpec:
  /** The number of test media. */
  private val MediaCount = 4

  /** Constant for the checksum of an orphaned file. */
  private val OrphanedChecksum1 = "1st unassigned checksum"

  /** Constant for the checksum of another orphaned file. */
  private val OrphanedChecksum2 = "2nd unassigned checksum"

  /** A test archive component ID. */
  private val ArchiveComponentID = "The-Test-Archive"

  /** A message bus registration ID. */
  private val MessageBusRegistrationID = 20163010

  /** A sequence with test data expected to be added to the table model. */
  private val TableModelData = createModelData()

  /**
    * Generates a checksum for a test medium.
    *
    * @param idx the index of the test medium
    * @return the generated checksum
    */
  private def checksum(idx: Int): String = "check_" + idx

  /**
    * Generates the ID for a test medium.
    *
    * @param idx the index of the test medium
    * @return the medium ID
    */
  private def mediumID(idx: Int): MediumID = MediumID("mediumID_" + idx, None)

  /**
    * Generates a ''MediumInfo'' object for a test medium.
    *
    * @param idx the index of the test medium
    * @return the medium info
    */
  private def mediumInfo(idx: Int): MediumInfo =
    MediumInfo(name = "Medium_" + idx, description = "Test medium" + idx,
      mediumID = mediumID(idx), orderMode = "", checksum = checksum(idx))

  /**
    * Generates an object with available media. It contains all the test media.
    *
    * @return the object with available media
    */
  private def availableMedia(): AvailableMedia =
    val mediaData = (1 to MediaCount) map (i => (mediumID(i), mediumInfo(i)))
    AvailableMedia(mediaData.toList)

  /**
    * Generates a meta data state update event with the specified flag for
    * scan in progress.
    *
    * @param scanInProgress the scan in progress flag
    * @return the update event
    */
  private def updateEvent(scanInProgress: Boolean): MetaDataStateUpdated =
    MetaDataStateUpdated(MetaDataState(scanInProgress = scanInProgress,
      mediaCount = 0, songCount = 0, size = 0, duration = 0, updateInProgress = false, archiveCompIDs = Set.empty))

  /**
    * Generates a sequence with model data which should be contained in the
    * table model after test data has been processed.
    *
    * @return the sequence with test model data
    */
  private def createModelData(): IndexedSeq[MetaDataFileData] =
    val mediaData = (1 to MediaCount) map { i =>
      val info = mediumInfo(i)
      MetaDataFilesController.MetaDataFileData(info.name, info.checksum)
    }
    val unassignedData = List(
      MetaDataFilesController.MetaDataFileData(null, OrphanedChecksum1),
      MetaDataFilesController.MetaDataFileData(null, OrphanedChecksum2)
    )
    mediaData ++ unassignedData

/**
  * Test class for ''MetaDataFilesController''.
  */
class MetaDataFilesControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers:

  import MetaDataFilesControllerSpec.*

  def this() = this(ActorSystem("MetaDataFilesControllerSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A MetaDataFilesController" should "register consumers on startup" in:
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow()

    val registrations = List(helper.findArchiveAvailableRegistration(),
      helper.findAvailableMediaRegistration(),
      helper.findStateListenerRegistration())
    checkRegistrationIDs(registrations)

  it should "cancel consumer registrations on shutdown" in:
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow().closeWindow()

    val messages = helper.publishedMessages
    val archiveReg = helper.findArchiveAvailableRegistration()
    messages should contain(ArchiveAvailabilityUnregistration(archiveReg.id))
    val listenerReg = helper.findStateListenerRegistration()
    messages should contain(StateListenerUnregistration(listenerReg.id))
    val mediaReg = helper.findAvailableMediaRegistration()
    messages should contain(AvailableMediaUnregistration(mediaReg.id))

  it should "update its state for an archive unavailable message" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendArchiveAvailability(MediaFacade.MediaArchiveUnavailable)
      .verifyDisabledState("files_state_disconnected")

  it should "ignore an archive available event" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendArchiveAvailability(MediaFacade.MediaArchiveAvailable)

  it should "update its state for a scan started event" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(MetaDataScanStarted)
      .verifyDisabledState("files_state_scanning")

  it should "update its state for a scan completed event" in:
    val helper = new MetaDataFilesControllerTestHelper
    helper.tableModelList add "some data"

    helper.openWindow()
      .sendStateEvent(MetaDataScanCompleted)
      .verifyDisabledState("files_state_loading", verifyRequest = false)
      .verifyActorRequestExecuted[MetaDataFileInfo]
    helper.tableModelList.isEmpty shouldBe true

  it should "update its state for a state update indicating scan in progress" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(updateEvent(scanInProgress = true))
      .verifyDisabledState("files_state_scanning")

  it should "update its state for a state update indicating no scan in progress" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(updateEvent(scanInProgress = false))
      .verifyDisabledState("files_state_loading", verifyRequest = false)
      .verifyActorRequestExecuted[MetaDataFileInfo]

  it should "ignore other meta data state events" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(MetaDataScanCanceled)
      .expectNoDataRequest()

  it should "ignore state transitions to the same target state" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(MetaDataScanStarted)
      .sendStateEvent(updateEvent(scanInProgress = true))
      .verifyDisabledState("files_state_scanning")

  /**
    * Checks whether the table for meta data files has been correctly filled.
    *
    * @param helper the helper
    */
  private def checkTableModel(helper: MetaDataFilesControllerTestHelper): Unit =
    helper.tableModelList.asScala should be(TableModelData)

  it should "populate the table when data becomes available" in:
    val helper = new MetaDataFilesControllerTestHelper
    helper.tableModelList.add("some initial data")

    helper.openWindow().sendAvailableMedia().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
    verify(helper.tableHandler).tableDataChanged()
    checkTableModel(helper)

  it should "handle a failed request for file information" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest[MetaDataFileInfo](Failure(new Exception))
      .verifyDisabledState("files_state_error_actor_access", verifyRequest = false)

  it should "update its state when data becomes available" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendAvailableMedia().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .verifyAction("refreshAction", enabled = true)
      .verifyAction("removeFilesAction", enabled = false)
      .verifyProgressIndicator(enabled = false)
      .verifyStatusText(new Message(null, "files_state_ready", MediaCount, 2))

  it should "filter out media for which no meta data file exists" in:
    val helper = new MetaDataFilesControllerTestHelper
    val avMedia = availableMedia()
    val moreMedia = (mediumID(42), mediumInfo(42)) :: avMedia.mediaList

    helper.openWindow().sendAvailableMedia(avMedia.copy(mediaList = moreMedia))
      .sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
    checkTableModel(helper)

  it should "handle data arriving in another order" in:
    val helper = new MetaDataFilesControllerTestHelper
    helper.tableModelList.add("some initial data")

    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .sendAvailableMedia()
    verify(helper.tableHandler).tableDataChanged()
    checkTableModel(helper)

  it should "reset file data if the archive becomes unavailable" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .sendArchiveAvailability(MediaFacade.MediaArchiveUnavailable)
      .sendAvailableMedia()
    verify(helper.tableHandler, never()).tableDataChanged()

  it should "reset file data if a scan starts" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .sendStateEvent(MetaDataScanStarted)
      .sendAvailableMedia()
    verify(helper.tableHandler, never()).tableDataChanged()

  it should "enable the remove action if files are selected" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .sendAvailableMedia()
      .resetMocks()
      .triggerTableSelection(Array(0))
      .verifyAction("removeFilesAction", enabled = true)

  it should "disable the remove action if no files are selected" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .sendAvailableMedia()
      .resetMocks()
      .triggerTableSelection(Array.empty)
      .verifyAction("removeFilesAction", enabled = false)

  it should "disable the remove action if no update actor is available" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo().copy(optUpdateActor = None)))
      .sendAvailableMedia()
      .resetMocks()
      .triggerTableSelection(Array(0))
      .verifyAction("removeFilesAction", enabled = false)

  it should "only update the remove action if allowed by the state" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendArchiveAvailability(MediaFacade.MediaArchiveUnavailable)
      .resetMocks()
      .triggerTableSelection(Array(0, 1, 2))
      .verifyAction("removeFilesAction", enabled = false)

  it should "handle a selection change event if no state is set" in:
    val helper = new MetaDataFilesControllerTestHelper

    helper.triggerTableSelection(Array(1))
    verifyNoMoreInteractions(helper.actions("removeFilesAction"))

  it should "react on the remove action" in:
    val helper = new MetaDataFilesControllerTestHelper
    val selection = Array(0, 2)
    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .sendAvailableMedia()
      .triggerTableSelection(selection).resetMocks()

    helper.prepareMetaDataActorRequest(RemovePersistentMetaData(Set(checksum(1), checksum(3))))
    helper.controller.removeFiles()
    helper.verifyDisabledState("files_state_removing", verifyRequest = false)
      .verifyActorRequestExecuted[RemovePersistentMetaDataResult]

  it should "react on the refresh action" in:
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .sendAvailableMedia()

    helper.prepareFileInfoRequest()
    helper.controller.refresh()
    helper.verifyDisabledState("files_state_loading", verifyRequest = false)
      .verifyActorRequestExecuted[MetaDataFileInfo]

  it should "handle the result of a remove files operation" in:
    val helper = new MetaDataFilesControllerTestHelper
    val checksumSet = Set(OrphanedChecksum1, OrphanedChecksum2, checksum(2))
    val request = RemovePersistentMetaData(checksumSet)
    val result = RemovePersistentMetaDataResult(request, checksumSet)
    helper.openWindow().sendAvailableMedia().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .resetMocks()

    helper.prepareMetaDataActorRequest(request)
      .prepareTableSelection(checksumSet)
    helper.controller.removeFiles()
    helper.expectAndAnswerActorRequest(Success(result))
      .verifyAction("refreshAction", enabled = true)
      .verifyStatusText(new Message(null, "files_state_ready", MediaCount - 1, 0))
    val remainingChecksumSet = helper.tableModelList.asScala
      .map(_.asInstanceOf[MetaDataFilesController.MetaDataFileData].checksum).toSet
    val expected = helper.metaDataFileInfo().metaDataFiles.values.toSet - checksum(2)
    remainingChecksumSet should be(expected)
    verify(helper.appContext, never()).messageBox(any(), any(), anyInt(), anyInt())

  it should "display a warning for an incomplete remove operation" in:
    val helper = new MetaDataFilesControllerTestHelper
    val checksumSet = Set(OrphanedChecksum1, OrphanedChecksum2, checksum(2))
    val request = RemovePersistentMetaData(checksumSet)
    val result = RemovePersistentMetaDataResult(request, checksumSet - OrphanedChecksum2)
    helper.openWindow().sendAvailableMedia().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .resetMocks()

    helper.prepareMetaDataActorRequest(request)
      .prepareTableSelection(checksumSet)
    helper.controller.removeFiles()
    helper.expectAndAnswerActorRequest(Success(result))
    verify(helper.appContext).messageBox("files_err_remove_msg", "files_err_remove_tit",
      MessageOutput.MESSAGE_WARNING, MessageOutput.BTN_OK)

  it should "handle a failed remove operation" in:
    val helper = new MetaDataFilesControllerTestHelper
    val checksumSet = Set(OrphanedChecksum1, OrphanedChecksum2, checksum(2))
    val request = RemovePersistentMetaData(checksumSet)
    helper.openWindow().sendAvailableMedia().sendStateEvent(MetaDataScanCompleted)
      .expectAndAnswerActorRequest(Success(helper.metaDataFileInfo()))
      .resetMocks()

    helper.prepareMetaDataActorRequest(request)
      .prepareTableSelection(checksumSet)
    helper.controller.removeFiles()
    helper.expectAndAnswerActorRequest(Failure[MetaDataFileInfo](new Exception))
      .verifyDisabledState("files_state_error_actor_access")

  it should "support closing the window" in:
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow()

    helper.controller.close()
    verify(helper.window).close(false)

  it should "ignore a window de-iconified event" in:
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowDeiconified(event)
    verifyNoInteractions(event)

  it should "ignore a window closed event" in:
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowClosed(event)
    verifyNoInteractions(event)

  it should "ignore a window activated event" in:
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowActivated(event)
    verifyNoInteractions(event)

  it should "ignore a window de-activated event" in:
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowDeactivated(event)
    verifyNoInteractions(event)

  it should "ignore a window iconified event" in:
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowIconified(event)
    verifyNoInteractions(event)

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class MetaDataFilesControllerTestHelper extends ActionTestHelper with MockitoSugar:
    /** A mock for the message bus. */
    val messageBus: MessageBus = createMessageBus()

    /** A mock for the application context. */
    val appContext: ApplicationContext = createApplicationContext()

    /** The list serving as table model. */
    val tableModelList = new util.ArrayList[AnyRef]

    /** A mock for the table handler. */
    val tableHandler: TableHandler = createTableHandler()

    /** A mock for the status line. */
    val statusHandler: StaticTextHandler = createStatusTextHandler()

    /** A mock for the progress indicator. */
    val progressIndicator: WidgetHandler = mock[WidgetHandler]

    /** A mock for the window associated with the controller. */
    val window: Window = mock[Window]

    /** The current text for the status line. */
    private var textStatus: String = _

    /** A mock for the meta data manager actor. */
    private val metaDataManager = TestProbe().ref

    /** A mock for the union media manager actor. */
    private val unionMediaManager = TestProbe().ref

    /** The mock actor client support object. */
    private val application = createApplication(messageBus)

    /** The test controller instance. */
    val controller = new MetaDataFilesController(application, appContext,
      initActionStore(), tableHandler, statusHandler, progressIndicator, ArchiveComponentID)

    /** Stores the messages passed to the message bus. */
    private var busMessages = List.empty[Any]

    /** Stores an actor request sent by the controller. */
    private var actorRequest: ActorClientSupport.ActorRequest = _

    /**
      * Returns a list with all messages (in order) that have been published
      * to the message bus.
      *
      * @return the list with published messages
      */
    def publishedMessages: List[Any] = busMessages.reverse

    /**
      * Creates a window event that references the mock window managed by this
      * helper object.
      *
      * @return the window event
      */
    def windowEvent(): WindowEvent =
      val event = mock[WindowEvent]
      doReturn(window).when(event).getSourceWindow
      event

    /**
      * Returns the configuration used by the application.
      *
      * @return the configuration
      */
    def config: Configuration = application.getUserConfiguration

    /**
      * Resets the mocks for actions and controls. This can be necessary in
      * some cases if they are manipulated multiple times.
      *
      * @return this test helper
      */
    def resetMocks(): MetaDataFilesControllerTestHelper =
      reset(progressIndicator, application)
      resetActionStates()
      this

    /**
      * Sends an event about a newly opened window to the test controller.
      * This should also trigger a request to the meta data manager actor for
      * information about meta data files.
      *
      * @return this test helper
      */
    def openWindow(): MetaDataFilesControllerTestHelper =
      prepareFileInfoRequest()
      controller windowOpened windowEvent()
      this

    /**
      * Sends an event to the test controller that the window is closing. This
      * should trigger shutdown.
      *
      * @return this test helper
      */
    def closeWindow(): MetaDataFilesControllerTestHelper =
      controller windowClosing windowEvent()
      this

    /**
      * Generates a ''MetaDataFileInfo'' object with test data.
      *
      * @return the ''MetaDataFileInfo''
      */
    def metaDataFileInfo(): MetaDataFileInfo =
      val mediaData = (1 to MediaCount) map (i => (mediumID(i), checksum(i)))
      MetaDataFileInfo(mediaData.toMap, Set(OrphanedChecksum1, OrphanedChecksum2), Some(metaDataManager))

    /**
      * Verifies that the prepared actor request has been executed.
      *
      * @param c the class tag for the result type
      * @tparam R the result type of the callback
      * @return this test helper
      */
    def verifyActorRequestExecuted[R](implicit c: ClassTag[R]): MetaDataFilesControllerTestHelper =
      verifyDataRequest(c)
      this

    /**
      * Prepares mock objects for a request to the meta data manager actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def prepareMetaDataActorRequest(msg: Any): MetaDataFilesControllerTestHelper =
      prepareActorRequest(metaDataManager, msg)

    /**
      * Prepares mock objects for a request to the union archive media manager
      * actor to query meta data file info.
      *
      * @return this test helper
      */
    def prepareFileInfoRequest(): MetaDataFilesControllerTestHelper =
      prepareActorRequest(unionMediaManager, GetArchiveMetaDataFileInfo(ArchiveComponentID))

    /**
      * Processes a request to the meta data actor and passes the provided
      * result to the test controller.
      *
      * @param result the result
      * @param c      the class tag for the result type
      * @tparam R the result type of the callback
      * @return this test helper
      */
    def expectAndAnswerActorRequest[R](result: Try[R])(implicit c: ClassTag[R]):
    MetaDataFilesControllerTestHelper =
      val callback = verifyDataRequest(c)
      callback(result)
      this

    /**
      * Verifies that no request for meta data files information has been
      * sent.
      *
      * @return this test helper
      */
    def expectNoDataRequest(): MetaDataFilesControllerTestHelper =
      verify(application, never()).actorRequest(eqArg(metaDataManager),
        eqArg(GetMetaDataFileInfo))(any(classOf[Timeout]))
      this

    /**
      * Returns the registration for the archive available extension or fails
      * if it cannot be found.
      *
      * @return the ''ArchiveAvailabilityRegistration''
      */
    def findArchiveAvailableRegistration(): ArchiveAvailabilityRegistration =
      findRegistration[ArchiveAvailabilityRegistration](busMessages)

    /**
      * Returns the registration for the state listener extension or fails
      * if it cannot be found.
      *
      * @return the ''StateListenerRegistration''
      */
    def findStateListenerRegistration(): StateListenerRegistration =
      findRegistration[StateListenerRegistration](busMessages)

    /**
      * Returns the registration for the available media extension or fails
      * if it cannot be found.
      *
      * @return the ''AvailableMediaRegistration''
      */
    def findAvailableMediaRegistration(): AvailableMediaRegistration =
      findRegistration[AvailableMediaRegistration](busMessages)

    /**
      * Verifies that the controller has registered itself at the message bus
      * and returns the corresponding ''Receive'' function.
      *
      * @return the ''Receive'' function
      */
    def findMessageBusRegistration(): Receive =
      val captor = ArgumentCaptor.forClass(classOf[Receive])
      verify(messageBus).registerListener(captor.capture())
      captor.getValue

    /**
      * Sends a message about available media to the test controller.
      *
      * @param avMedia the message to be sent
      * @return this test helper
      */
    def sendAvailableMedia(avMedia: AvailableMedia = availableMedia()):
    MetaDataFilesControllerTestHelper =
      findAvailableMediaRegistration().callback(avMedia)
      this

    /**
      * Checks that no interactions with the message bus took place to register
      * the controller at extensions.
      *
      * @return this test helper
      */
    def checkNoExtensionRegistration(): MetaDataFilesControllerTestHelper =
      busMessages.isEmpty shouldBe true
      this

    /**
      * Sends an archive availability message.
      *
      * @param message the message
      * @return this test helper
      */
    def sendArchiveAvailability(message: MediaFacade.MediaArchiveAvailabilityEvent):
    MetaDataFilesControllerTestHelper =
      findArchiveAvailableRegistration().callback(message)
      this

    /**
      * Sends an event about a meta data state change.
      *
      * @param event the event
      * @return this test helper
      */
    def sendStateEvent(event: MetaDataStateEvent): MetaDataFilesControllerTestHelper =
      findStateListenerRegistration().callback(event)
      this

    /**
      * Verifies that the specified action has been set to the given enabled
      * state.
      *
      * @param name    the name of the action
      * @param enabled the expected enabled state
      * @return this test helper
      */
    def verifyAction(name: String, enabled: Boolean): MetaDataFilesControllerTestHelper =
      isActionEnabled(name) should be(enabled)
      this

    /**
      * Verifies that the status line has been set to the specified resource
      * text.
      *
      * @param resID the resource ID
      * @return this test helper
      */
    def verifyStatusText(resID: String): MetaDataFilesControllerTestHelper =
      verifyStatusText(new Message(resID))

    /**
      * Verifies that the status line has been set to the specified ''Message''
      * object.
      *
      * @param message the ''Message''
      * @return this test helper
      */
    def verifyStatusText(message: Message): MetaDataFilesControllerTestHelper =
      textStatus should be(message.toString)
      this

    /**
      * Verifies that the progress indicator has been set correctly.
      *
      * @param enabled the expected enabled flag
      * @return this test helper
      */
    def verifyProgressIndicator(enabled: Boolean): MetaDataFilesControllerTestHelper =
      verify(progressIndicator, atLeastOnce()).setVisible(enabled)
      this

    /**
      * Verifies that a state has been set which disables the controller's
      * actions. This is a convenience method invoking a number of other check
      * methods.
      *
      * @param statusResID           the expected resource ID for the status line
      * @param verifyRequest         flag whether it should be checked that no request
      *                              for file information has been sent
      * @param expectedProgressState expected state of the progress indicator
      * @return this test helper
      */
    def verifyDisabledState(statusResID: String, verifyRequest: Boolean = true,
                            expectedProgressState: Boolean = true):
    MetaDataFilesControllerTestHelper =
      verifyAction("refreshAction", enabled = false)
      verifyAction("removeFilesAction", enabled = false)
      verifyStatusText(statusResID)
      verifyProgressIndicator(expectedProgressState)
      if verifyRequest then
        expectNoDataRequest()
      this

    /**
      * Sends a table selection change event to the test controller for the
      * specified table selection.
      *
      * @param indices the selected indices of the table
      * @return this test helper
      */
    def triggerTableSelection(indices: Array[Int]): MetaDataFilesControllerTestHelper =
      when(tableHandler.getSelectedIndices).thenReturn(indices)
      controller.elementChanged(null)
      this

    /**
      * Prepares the table handler mock to return a selection that corresponds
      * to the specified checksum set.
      *
      * @param checksumSet the set with checksum values to be selected
      * @return this test helper
      */
    def prepareTableSelection(checksumSet: Set[String]): MetaDataFilesControllerTestHelper =
      val selection = checksumSet.map(s => TableModelData.indexWhere(s == _.checksum)).toArray
      when(tableHandler.getSelectedIndices).thenReturn(selection)
      this

    /**
      * Prepares mocks to expect an actor request from the controller.
      *
      * @param actor the actor to be invoked
      * @param msg   the message to be sent
      * @return this test helper
      */
    private def prepareActorRequest(actor: ActorRef, msg: Any): MetaDataFilesControllerTestHelper =
      actorRequest = mock[ActorRequest]
      when(application.invokeActor(actor, msg)).thenReturn(actorRequest)
      this

    /**
      * Verifies that a request to the meta data actor has been sent and
      * returns the callback function for processing the result.
      *
      * @param c the class tag of the result
      * @tparam R the result type of the request
      * @return the callback function for result processing
      */
    private def verifyDataRequest[R](implicit c: ClassTag[R]): Try[R] => Unit =
      val captor = ArgumentCaptor.forClass(classOf[Try[R] => Unit])
      verify(actorRequest).executeUIThread(captor.capture())(any(classOf[ClassTag[R]]))
      val res = captor.getValue
      res

    /**
      * Creates a mock message bus. All messages passed to the bus are stored
      * in a list where they can be checked.
      *
      * @return the mock message bus
      */
    private def createMessageBus(): MessageBus =
      val bus = mock[MessageBus]
      doAnswer((invocation: InvocationOnMock) => {
        busMessages = invocation.getArguments.head :: busMessages
        null
      }).when(bus).publish(any())
      when(bus.registerListener(any(classOf[Receive]))).thenReturn(MessageBusRegistrationID)
      bus

    /**
      * Creates a mock for the main application.
      *
      * @param bus the message bus
      * @return the mock application
      */
    private def createApplication(bus: MessageBus): ArchiveAdminApp =
      val app = mock[ArchiveAdminApp]
      val clientApplicationContext = mock[ClientApplicationContext]
      val facadeActors = MediaFacadeActors(mediaManager = unionMediaManager, metaDataManager = null)
      when(app.clientApplicationContext).thenReturn(clientApplicationContext)
      when(clientApplicationContext.messageBus).thenReturn(bus)
      when(app.getUserConfiguration).thenReturn(new PropertiesConfiguration)
      when(app.mediaFacadeActors).thenReturn(facadeActors)
      app

    /**
      * Creates a mock for the table handler.
      *
      * @return the mock table handler
      */
    private def createTableHandler(): TableHandler =
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(tableModelList)
      handler

    /**
      * Creates a mock for the status line text handler which records the
      * current status text.
      *
      * @return the mock status handler
      */
    private def createStatusTextHandler(): StaticTextHandler =
      val handler = mock[StaticTextHandler]
      doAnswer((invocation: InvocationOnMock) => {
        textStatus = invocation.getArguments.head.toString
        null
      }).when(handler).setText(anyString())
      handler

    /**
      * Initializes the mock actions used by this test class and creates a mock
      * action store object that supports them.
      *
      * @return the mock action store
      */
    private def initActionStore(): ActionStore =
      createActions("refreshAction", "removeFilesAction")
      createActionStore()

    /**
      * Creates a mock application context. The mock is prepared to answer
      * queries for resources; it then returns the string representation of the
      * passed in ''Message'' object.
      *
      * @return the mock application context
      */
    private def createApplicationContext(): ApplicationContext =
      val context = mock[ApplicationContext]
      when(context.getResourceText(any(classOf[Message]))).thenAnswer((invocation: InvocationOnMock) => invocation.getArguments.head.toString)
      context

