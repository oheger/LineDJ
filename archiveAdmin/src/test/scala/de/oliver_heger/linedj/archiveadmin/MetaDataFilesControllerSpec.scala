/*
 * Copyright 2015-2016 The Developers Team.
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

import java.util

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.app.ConsumerRegistrationProviderTestHelper._
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension
.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension
.{AvailableMediaRegistration, AvailableMediaUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.StateListenerExtension
.{StateListenerRegistration, StateListenerUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata._
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent}
import net.sf.jguiraffe.resources.Message
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import collection.JavaConverters._

object MetaDataFilesControllerSpec {
  /** The number of test media. */
  private val MediaCount = 4

  /** Constant for the checksum of an orphaned file. */
  private val OrphanedChecksum1 = "1st unassigned checksum"

  /** Constant for the checksum of another orphaned file. */
  private val OrphanedChecksum2 = "2nd unassigned checksum"

  /** A message bus registration ID. */
  private val MessageBusRegistrationID = 20163010

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
    mediumID = mediumID(idx), orderMode = "", orderParams = "", checksum = checksum(idx))

  /**
    * Generates an object with available media. It contains all the test media.
    *
    * @return the object with available media
    */
  private def availableMedia(): AvailableMedia = {
    val mediaData = (1 to MediaCount) map (i => (mediumID(i), mediumInfo(i)))
    AvailableMedia(mediaData.toMap)
  }

  /**
    * Generates a ''MetaDataFileInfo'' object with test data.
    *
    * @return the ''MetaDataFileInfo''
    */
  private def metaDataFileInfo(): MetaDataFileInfo = {
    val mediaData = (1 to MediaCount) map (i => (mediumID(i), checksum(i)))
    MetaDataFileInfo(mediaData.toMap, Set(OrphanedChecksum1, OrphanedChecksum2))
  }

  /**
    * Generates a meta data state update event with the specified flag for
    * scan in progress.
    *
    * @param scanInProgress the scan in progress flag
    * @return the update event
    */
  private def updateEvent(scanInProgress: Boolean): MetaDataStateUpdated =
  MetaDataStateUpdated(MetaDataState(scanInProgress = scanInProgress,
    mediaCount = 0, songCount = 0, size = 0, duration = 0))
}

/**
  * Test class for ''MetaDataFilesController''.
  */
class MetaDataFilesControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import MetaDataFilesControllerSpec._

  "A MetaDataFilesController" should "register consumers on startup" in {
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow()

    val registrations = List(helper.findArchiveAvailableRegistration(),
      helper.findAvailableMediaRegistration(),
      helper.findStateListenerRegistration())
    checkRegistrationIDs(registrations)
  }

  it should "cancel consumer registrations on shutdown" in {
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow().closeWindow()

    val messages = helper.publishedMessages
    val archiveReg = helper.findArchiveAvailableRegistration()
    messages should contain(ArchiveAvailabilityUnregistration(archiveReg.id))
    val listenerReg = helper.findStateListenerRegistration()
    messages should contain(StateListenerUnregistration(listenerReg.id))
    val mediaReg = helper.findAvailableMediaRegistration()
    messages should contain(AvailableMediaUnregistration(mediaReg.id))
  }

  it should "register itself as message bus listener on startup" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
    helper.findMessageBusRegistration()
  }

  it should "remove the message bus listener registration at shutdown" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().closeWindow()
    verify(helper.messageBus).removeListener(MessageBusRegistrationID)
  }

  it should "update its state for an archive unavailable message" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendArchiveAvailability(MediaFacade.MediaArchiveUnavailable)
      .verifyDisabledState("files_state_disconnected")
  }

  it should "ignore an archive available event" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendArchiveAvailability(MediaFacade.MediaArchiveAvailable)
    verifyZeroInteractions(helper.statusHandler)
  }

  it should "update its state for a scan started event" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(MetaDataScanStarted)
      .verifyDisabledState("files_state_scanning")
  }

  it should "update its state for a scan completed event" in {
    val helper = new MetaDataFilesControllerTestHelper
    helper.tableModelList add "some data"

    helper.openWindow()
      .sendStateEvent(MetaDataScanCompleted)
      .verifyDisabledState("files_state_loading", verifyFacade = false)
      .expectDataRequest()
    helper.tableModelList.isEmpty shouldBe true
  }

  it should "update its state for a state update indicating scan in progress" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(updateEvent(scanInProgress = true))
      .verifyDisabledState("files_state_scanning")
  }

  it should "update its state for a state update indicating no scan in progress" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(updateEvent(scanInProgress = false))
      .verifyDisabledState("files_state_loading", verifyFacade = false)
      .expectDataRequest()
  }

  it should "ignore other meta data state events" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(MetaDataScanCanceled)
      .expectNoDataRequest()
  }

  it should "ignore state transitions to the same target state" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendStateEvent(MetaDataScanStarted)
      .sendStateEvent(updateEvent(scanInProgress = true))
      .verifyDisabledState("files_state_scanning")
  }

  /**
    * Checks whether the table for meta data files has been correctly filled.
    *
    * @param helper the helper
    */
  private def checkTableModel(helper: MetaDataFilesControllerTestHelper): Unit = {
    val mediaData = (1 to MediaCount) map { i =>
      val info = mediumInfo(i)
      MetaDataFilesController.MetaDataFileData(info.name, info.checksum)
    }
    val unassignedData = List(
      MetaDataFilesController.MetaDataFileData(null, OrphanedChecksum1),
      MetaDataFilesController.MetaDataFileData(null, OrphanedChecksum2)
    )
    val expected = mediaData ++ unassignedData
    helper.tableModelList.asScala should be(expected)
  }

  it should "populate the table when data becomes available" in {
    val helper = new MetaDataFilesControllerTestHelper
    helper.tableModelList.add("some initial data")

    helper.openWindow().sendAvailableMedia().sendMessage(metaDataFileInfo())
    verify(helper.tableHandler).tableDataChanged()
    checkTableModel(helper)
  }

  it should "update its state when data becomes available" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendAvailableMedia().sendMessage(metaDataFileInfo())
      .verifyAction("refreshAction", enabled = true)
      .verifyAction("removeFilesAction", enabled = false)
      .verifyProgressIndicator(enabled = false)
      .verifyStatusText(new Message(null, "files_state_ready", MediaCount, 2))
  }

  it should "filter out media for which no meta data file exists" in {
    val helper = new MetaDataFilesControllerTestHelper
    val avMedia = availableMedia()
    val moreMedia = avMedia.media + (mediumID(42) -> mediumInfo(42))

    helper.openWindow().sendAvailableMedia(avMedia.copy(media = moreMedia))
      .sendMessage(metaDataFileInfo())
    checkTableModel(helper)
  }

  it should "handle data arriving in another order" in {
    val helper = new MetaDataFilesControllerTestHelper
    helper.tableModelList.add("some initial data")

    helper.openWindow().sendMessage(metaDataFileInfo()).sendAvailableMedia()
    verify(helper.tableHandler).tableDataChanged()
    checkTableModel(helper)
  }

  it should "reset file data if the archive becomes unavailable" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendMessage(metaDataFileInfo())
      .sendArchiveAvailability(MediaFacade.MediaArchiveUnavailable)
      .sendAvailableMedia()
    verifyZeroInteractions(helper.tableHandler)
  }

  it should "reset file data if a scan starts" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendMessage(metaDataFileInfo())
      .sendStateEvent(MetaDataScanStarted)
      .sendAvailableMedia()
    verifyZeroInteractions(helper.tableHandler)
  }

  it should "enable the remove action if files are selected" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendMessage(metaDataFileInfo()).sendAvailableMedia()
      .resetMocks()
      .triggerTableSelection(Array(0))
      .verifyAction("removeFilesAction", enabled = true)
  }

  it should "disable the remove action if no files are selected" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow().sendMessage(metaDataFileInfo()).sendAvailableMedia()
      .resetMocks()
      .triggerTableSelection(Array.empty)
      .verifyAction("removeFilesAction", enabled = false)
  }

  it should "only update the remove action if allowed by the state" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendArchiveAvailability(MediaFacade.MediaArchiveUnavailable)
      .resetMocks()
      .triggerTableSelection(Array(0, 1, 2))
    verifyNoMoreInteractions(helper.actions("removeFilesAction"))
  }

  it should "handle a selection change event if no state is set" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.triggerTableSelection(Array(1))
    verifyNoMoreInteractions(helper.actions("removeFilesAction"))
  }

  it should "react on the remove action" in {
    val helper = new MetaDataFilesControllerTestHelper
    val selection = Array(0, 2)
    helper.openWindow().sendMessage(metaDataFileInfo()).sendAvailableMedia()
      .triggerTableSelection(selection).resetMocks()

    helper.controller.removeFiles()
    helper.verifyDisabledState("files_state_removing", verifyFacade = false)
    verify(helper.mediaFacade).send(MediaActors.MetaDataManager,
      RemovePersistentMetaData(Set(checksum(1), checksum(3))))
  }

  it should "react on the refresh action" in {
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow().sendMessage(metaDataFileInfo()).sendAvailableMedia()
      .resetMocks()

    helper.controller.refresh()
    helper.verifyDisabledState("files_state_loading", verifyFacade = false)
      .expectDataRequest()
  }

  it should "handle the result of a remove files operation" in {
    val helper = new MetaDataFilesControllerTestHelper
    val checksumSet = Set(OrphanedChecksum1, OrphanedChecksum2, checksum(2))
    val request = RemovePersistentMetaData(checksumSet)
    val result = RemovePersistentMetaDataResult(request, checksumSet)
    helper.openWindow().sendAvailableMedia().sendMessage(metaDataFileInfo()).resetMocks()

    helper.sendMessage(result)
      .verifyAction("refreshAction", enabled = true)
      .verifyStatusText(new Message(null, "files_state_ready", MediaCount - 1, 0))
    val remainingChecksumSet = helper.tableModelList.asScala
      .map(_.asInstanceOf[MetaDataFilesController.MetaDataFileData].checksum).toSet
    val expected = metaDataFileInfo().metaDataFiles.values.toSet - checksum(2)
    remainingChecksumSet should be(expected)
    verify(helper.appContext, never()).messageBox(any(), any(), anyInt(), anyInt())
  }

  it should "ignore a remove result if no file info is available" in {
    val helper = new MetaDataFilesControllerTestHelper

    helper.openWindow()
      .sendMessage(RemovePersistentMetaDataResult(RemovePersistentMetaData(Set("test")),
        Set.empty))
    verify(helper.statusHandler, never()).setText(anyString())
  }

  it should "display a warning for an incomplete remove operation" in {
    val helper = new MetaDataFilesControllerTestHelper
    val checksumSet = Set(OrphanedChecksum1, OrphanedChecksum2, checksum(2))
    val request = RemovePersistentMetaData(checksumSet)
    val result = RemovePersistentMetaDataResult(request, checksumSet - OrphanedChecksum2)
    helper.openWindow().sendAvailableMedia().sendMessage(metaDataFileInfo()).resetMocks()

    helper.sendMessage(result)
    verify(helper.appContext).messageBox("files_err_remove_msg", "files_err_remove_tit",
      MessageOutput.MESSAGE_WARNING, MessageOutput.BTN_OK)
  }

  it should "support closing the window" in {
    val helper = new MetaDataFilesControllerTestHelper
    helper.openWindow()

    helper.controller.close()
    verify(helper.window).close(false)
  }

  it should "ignore a window de-iconified event" in {
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowDeiconified(event)
    verifyZeroInteractions(event)
  }

  it should "ignore a window closed event" in {
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowClosed(event)
    verifyZeroInteractions(event)
  }

  it should "ignore a window activated event" in {
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowActivated(event)
    verifyZeroInteractions(event)
  }

  it should "ignore a window de-activated event" in {
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowDeactivated(event)
    verifyZeroInteractions(event)
  }

  it should "ignore a window iconified event" in {
    val helper = new MetaDataFilesControllerTestHelper
    val event = helper.windowEvent()

    helper.controller.windowIconified(event)
    verifyZeroInteractions(event)
  }

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class MetaDataFilesControllerTestHelper {
    /** A mock for the message bus. */
    val messageBus = createMessageBus()

    /** A mock for the media facade. */
    val mediaFacade = mock[MediaFacade]

    /** A mock for the application context. */
    val appContext = createApplicationContext()

    /** The list serving as table model. */
    val tableModelList = new util.ArrayList[AnyRef]

    /** A mock for the table handler. */
    val tableHandler = createTableHandler()

    /** A mock for the status line. */
    val statusHandler = mock[StaticTextHandler]

    /** A mock for the progress indicator. */
    val progressIndicator = mock[WidgetHandler]

    /** A mock for the window associated with the controller. */
    val window = mock[Window]

    /** A map with mock actions to be managed by the controller. */
    val actions = createActions()

    /** The test controller instance. */
    val controller = new MetaDataFilesController(messageBus, mediaFacade, appContext,
      createActionStore(actions), tableHandler, statusHandler, progressIndicator)

    /** Stores the messages passed to the message bus. */
    private var busMessages = List.empty[Any]

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
    def windowEvent(): WindowEvent = {
      val event = mock[WindowEvent]
      doReturn(window).when(event).getSourceWindow
      event
    }

    /**
      * Resets the mocks for actions and controls. This can be necessary in
      * some cases if they are manipulated multiple times.
      *
      * @return this test helper
      */
    def resetMocks(): MetaDataFilesControllerTestHelper = {
      reset(actions.values.toArray: _*)
      reset(statusHandler, progressIndicator)
      this
    }

    /**
      * Sends an event about a newly opened window to the test controller.
      *
      * @return this test helper
      */
    def openWindow(): MetaDataFilesControllerTestHelper = {
      controller windowOpened windowEvent()
      this
    }

    /**
      * Sends an event to the test controller that the window is closing. This
      * should trigger shutdown.
      *
      * @return this test helper
      */
    def closeWindow(): MetaDataFilesControllerTestHelper = {
      controller windowClosing windowEvent()
      this
    }

    /**
      * Verifies that a request for meta data files information has been
      * sent via the media facade.
      *
      * @return this test helper
      */
    def expectDataRequest(): MetaDataFilesControllerTestHelper = {
      verify(mediaFacade).send(MediaActors.MetaDataManager, GetMetaDataFileInfo)
      this
    }

    /**
      * Verifies that no request for meta data files information has been
      * sent.
      *
      * @return this test helper
      */
    def expectNoDataRequest(): MetaDataFilesControllerTestHelper = {
      verify(mediaFacade, never()).send(any(classOf[MediaActor]), any())
      this
    }

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
    def findMessageBusRegistration(): Receive = {
      val captor = ArgumentCaptor.forClass(classOf[Receive])
      verify(messageBus).registerListener(captor.capture())
      captor.getValue
    }

    /**
      * Sends a message about available media to the test controller.
      *
      * @param avMedia the message to be sent
      * @return this test helper
      */
    def sendAvailableMedia(avMedia: AvailableMedia = availableMedia()):
    MetaDataFilesControllerTestHelper = {
      findAvailableMediaRegistration().callback(avMedia)
      this
    }

    /**
      * Sends a message on the message bus to the test controller.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def sendMessage(msg: Any): MetaDataFilesControllerTestHelper = {
      findMessageBusRegistration().apply(msg)
      this
    }

    /**
      * Sends an archive availability message.
      *
      * @param message the message
      * @return this test helper
      */
    def sendArchiveAvailability(message: MediaFacade.MediaArchiveAvailabilityEvent):
    MetaDataFilesControllerTestHelper = {
      findArchiveAvailableRegistration().callback(message)
      this
    }

    /**
      * Sends an event about a meta data state change.
      *
      * @param event the event
      * @return this test helper
      */
    def sendStateEvent(event: MetaDataStateEvent): MetaDataFilesControllerTestHelper = {
      findStateListenerRegistration().callback(event)
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
    def verifyAction(name: String, enabled: Boolean): MetaDataFilesControllerTestHelper = {
      verify(actions(name)).setEnabled(enabled)
      this
    }

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
    def verifyStatusText(message: Message): MetaDataFilesControllerTestHelper = {
      val text = message.toString
      verify(statusHandler).setText(text)
      this
    }

    /**
      * Verifies that the progress indicator has been set correctly.
      *
      * @param enabled the expected enabled flag
      * @return this test helper
      */
    def verifyProgressIndicator(enabled: Boolean): MetaDataFilesControllerTestHelper = {
      verify(progressIndicator).setVisible(enabled)
      this
    }

    /**
      * Verifies that a state has been set which disables the controller's
      * actions. This is a convenience method invoking a number of other check
      * methods.
      *
      * @param statusResID  the expected resource ID for the status line
      * @param verifyFacade flag whether the facade mock should be checked
      *                     for zero interaction
      * @return this test helper
      */
    def verifyDisabledState(statusResID: String, verifyFacade: Boolean = true):
    MetaDataFilesControllerTestHelper = {
      verifyAction("refreshAction", enabled = false)
      verifyAction("removeFilesAction", enabled = false)
      verifyStatusText(statusResID)
      verifyProgressIndicator(enabled = true)
      if (verifyFacade) {
        expectNoDataRequest()
      }
      this
    }

    /**
      * Sends a table selection change event to the test controller for the
      * specified table selection.
      *
      * @param indices the selected indices of the table
      * @return this test helper
      */
    def triggerTableSelection(indices: Array[Int]): MetaDataFilesControllerTestHelper = {
      when(tableHandler.getSelectedIndices).thenReturn(indices)
      controller.elementChanged(null)
      this
    }

    /**
      * Creates a mock message bus. All messages passed to the bus are stored
      * in a list where they can be checked.
      *
      * @return the mock message bus
      */
    private def createMessageBus(): MessageBus = {
      val bus = mock[MessageBus]
      doAnswer(new Answer[AnyRef] {
        override def answer(invocation: InvocationOnMock): AnyRef = {
          busMessages = invocation.getArguments.head :: busMessages
          null
        }
      }).when(bus).publish(any())
      when(bus.registerListener(any(classOf[Receive]))).thenReturn(MessageBusRegistrationID)
      bus
    }

    /**
      * Creates a mock for the table handler.
      *
      * @return the mock table handler
      */
    private def createTableHandler(): TableHandler = {
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(tableModelList)
      handler
    }

    /**
      * Generates a map with mock actions.
      *
      * @return the map with mock actions
      */
    private def createActions(): Map[String, FormAction] =
    List("refreshAction", "removeFilesAction").map((_, mock[FormAction])).toMap

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
      * Creates a mock application context. The mock is prepared to answer
      * queries for resources; it then returns the string representation of the
      * passed in ''Message'' object.
      *
      * @return the mock application context
      */
    private def createApplicationContext(): ApplicationContext = {
      val context = mock[ApplicationContext]
      when(context.getResourceText(any(classOf[Message]))).thenAnswer(new Answer[String] {
        override def answer(invocation: InvocationOnMock): String =
          invocation.getArguments.head.toString
      })
      context
    }
  }

}
