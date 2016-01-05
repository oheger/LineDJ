/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.playlist.export

import java.nio.file.Paths

import akka.actor._
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import de.oliver_heger.linedj.client.model.SongData
import de.oliver_heger.linedj.io.ScanResult
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.MediaMetaData
import de.oliver_heger.linedj.client.remoting.{ActorFactory, MessageBus, RemoteMessageBus}
import de.oliver_heger.linedj.utils.ChildActorFactory
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, ProgressBarHandler}
import net.sf.jguiraffe.gui.builder.event.FormActionEvent
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.gui.builder.window.{WindowEvent, Window}
import net.sf.jguiraffe.gui.forms.ComponentHandler
import net.sf.jguiraffe.resources.Message
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.Matchers.{eq => eqArg}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, BeforeAndAfterAll, FlatSpecLike}
import scala.concurrent.duration._

object ExportControllerSpec {
  /** The number of test songs to be exported. */
  private val SongCount = 10

  /** The data object for the export. */
  private val TestExportData = ExportActor.ExportData(createTestSongs(), ScanResult(Nil, Nil),
    Paths get "somePath", clearTarget = false, overrideFiles = true)

  /** A bus listener ID. */
  private val ListenerID = 20151009

  /** A test path to be used as current file. */
  private val TestPath = Paths get "CurrentFile.tst"

  /**
   * Creates a list with a number of test songs.
   * @return the list with test songs
   */
  private def createTestSongs(): Seq[SongData] = {
    val medium = MediumID("TestMedium", None)
    1 to SongCount map (i => SongData(medium, "song://" + i, MediaMetaData(title = Some("Song" +
      i)), null))
  }
}

/**
 * Test class for ''ExportController''.
 */
class ExportControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ExportControllerSpec._

  def this() = this(ActorSystem("ExportControllerSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "An ExportController" should "have dummy implementations for window listener methods" in {
    val event = mock[WindowEvent]
    val helper = new ExportControllerTestHelper

    helper.controller.windowActivated(event)
    helper.controller.windowClosed(event)
    helper.controller.windowDeactivated(event)
    helper.controller.windowDeiconified(event)
    helper.controller.windowIconified(event)
  }

  it should "register itself as message bus listener" in {
    val helper = new ExportControllerTestHelper

    helper.openWindow().expectBusListenerRegistration()
  }

  it should "remove the message bus listener registration when the window is closing" in {
    val helper = new ExportControllerTestHelper
    helper.openWindow()

    helper.controller.windowClosing(mock[WindowEvent])
    verify(helper.remoteMessageBus.bus).removeListener(ListenerID)
  }

  it should "create the export actor and start the export" in {
    val helper = new ExportControllerTestHelper

    helper.openWindow().expectExportMessage()
  }

  it should "update controls for a remove progress message" in {
    val progressMsg = ExportActor.ExportProgress(totalOperations = 100 + SongCount, totalSize =
      1000,
      currentOperation = 5, currentSize = 0, currentPath = TestPath, operationType = ExportActor
        .OperationType.Remove)
    val helper = new ExportControllerTestHelper

    helper.openWindow().sendMessage(progressMsg)
    verify(helper.textFile).setText(TestPath.toString)
    verify(helper.progressRemove).setValue(5)
  }

  it should "update controls for a copy progress message" in {
    val progressMsg = ExportActor.ExportProgress(totalOperations = 100 + SongCount, totalSize =
      1000,
      currentOperation = 5, currentSize = 100, currentPath = TestPath, operationType =
        ExportActor.OperationType.Copy)
    val helper = new ExportControllerTestHelper

    helper.openWindow().sendMessage(progressMsg)
    verify(helper.textFile).setText(TestPath.toString)
    verify(helper.progressRemove).setValue(100)
    verify(helper.progressCopy).setValue(10)
  }

  it should "handle the successful end of the export" in {
    val helper = new ExportControllerTestHelper

    helper.openWindow().sendMessage(ExportActor.ExportResult(None)).expectShutdown()
  }

  it should "handle the end of an export that failed when removing a file" in {
    val error = ExportActor.ExportError(TestPath, ExportActor.OperationType.Remove)
    val helper = new ExportControllerTestHelper

    helper.openWindow().sendMessage(ExportActor.ExportResult(Some(error)))
      .expectErrorMessage("exp_failure_remove").expectShutdown()
  }

  it should "handle the end of an export that failed when copying a file" in {
    val error = ExportActor.ExportError(TestPath, ExportActor.OperationType.Copy)
    val helper = new ExportControllerTestHelper

    helper.openWindow().sendMessage(ExportActor.ExportResult(Some(error)))
      .expectErrorMessage("exp_failure_copy").expectShutdown()
  }

  it should "react on the cancel button" in {
    val handler = mock[ComponentHandler[_]]
    val actionEvent = new FormActionEvent(this, handler, "someControl", "someCommand")
    val helper = new ExportControllerTestHelper
    helper.openWindow().expectExportMessage()

    helper.controller actionPerformed actionEvent
    verify(handler).setEnabled(false)
    helper.exportActor expectMsg ExportActor.CancelExport
  }

  /**
   * A test helper class managing the dependencies of the test controller.
   */
  private class ExportControllerTestHelper {
    /** Mock for the remote message bus. */
    val remoteMessageBus = createRemoteMessageBus()

    /** Test probe for the export actor. */
    val exportActor = TestProbe()

    /** Mock for the actor factory. */
    val factory = createActorFactory(exportActor, remoteMessageBus)

    /** Mock for the remove progress bar handler. */
    val progressRemove = mock[ProgressBarHandler]

    /** Mock for the copy progress bar handler. */
    val progressCopy = mock[ProgressBarHandler]

    /** Mock for the current file text handler. */
    val textFile = mock[StaticTextHandler]

    /** Mock for the window. */
    val window = mock[Window]

    /** Mock for the application context. */
    val applicationContext = mock[ApplicationContext]

    /** The test controller. */
    val controller = new ExportController(applicationContext, remoteMessageBus, factory,
      TestExportData, progressRemove, progressCopy, textFile)

    /**
     * Simulates the opening of the window. At this time the controller does
     * most of its initialization.
     * @return this helper object
     */
    def openWindow(): ExportControllerTestHelper = {
      val event = new WindowEvent(this, window, WindowEvent.Type.WINDOW_OPENED)
      controller windowOpened event
      this
    }

    /**
     * Expects that the controller registers itself as message bus listener.
     * @return this test helper
     */
    def expectBusListenerRegistration(): ExportControllerTestHelper = {
      fetchMessageBusListener()
      this
    }

    /**
     * Expects that a message for starting the export is passed to the export
     * actor.
     * @return this test helper
     */
    def expectExportMessage(): ExportControllerTestHelper = {
      exportActor.expectMsg(TestExportData)
      this
    }

    /**
     * Sends a message to the test controller via the message bus.
     * @param msg the message
     * @return this test helper
     */
    def sendMessage(msg: Any): ExportControllerTestHelper = {
      controller receive msg
      this
    }

    /**
     * Expects shutdown after an export operation is done.
     * @return this test helper
     */
    def expectShutdown(): ExportControllerTestHelper = {
      verify(window).close(true)
      val probe = TestProbe()
      probe watch exportActor.ref
      probe.expectMsgType[Terminated].actor should be(exportActor.ref)
      this
    }

    /**
     * Expects an error message to be displayed for a failed export.
     * @param resKey the resource key of the error message
     * @return this test helper
     */
    def expectErrorMessage(resKey: String): ExportControllerTestHelper = {
      verify(applicationContext).messageBox(new Message(null, resKey, TestPath.toString),
        "exp_failure_title", MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK)
      this
    }

    /**
     * Obtains the message bus listener that was registered by the controller.
     * @return the message bus listener
     */
    private def fetchMessageBusListener(): Actor.Receive = {
      val captor = ArgumentCaptor.forClass(classOf[Actor.Receive])
      verify(remoteMessageBus.bus).registerListener(captor.capture())
      captor.getValue
    }

    /**
     * Creates a mock for the remote message bus.
     * @return the remote message bus
     */
    private def createRemoteMessageBus(): RemoteMessageBus = {
      val remoteBus = mock[RemoteMessageBus]
      val bus = mock[MessageBus]
      when(remoteBus.bus).thenReturn(bus)
      when(bus.registerListener(org.mockito.Matchers.any[Actor.Receive])).thenReturn(ListenerID)
      remoteBus
    }

    /**
     * Creates a mock actor factory. The factory returns the test probe for the
     * export actor. It also verifies the creation properties.
     * @param actor the probe for the export actor
     * @param remoteBus the remote message bus
     * @return the mock for the actor factory
     */
    private def createActorFactory(actor: TestProbe, remoteBus: RemoteMessageBus): ActorFactory = {
      val factory = mock[ActorFactory]
      when(factory.actorSystem).thenReturn(system)
      when(factory.createActor(org.mockito.Matchers.any[Props], eqArg("playlistExportActor")))
        .thenAnswer(new Answer[ActorRef] {
        override def answer(invocationOnMock: InvocationOnMock): ActorRef = {
          val props = invocationOnMock.getArguments.head.asInstanceOf[Props]
          classOf[ExportActor].isAssignableFrom(props.actorClass()) shouldBe true
          classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
          props.args should contain only remoteBus
          actor.ref
        }
      })
      factory
    }
  }

}
