/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.reorder

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import net.sf.jguiraffe.gui.builder.components.model.ListComponentHandler
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormChangeEvent}
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent}
import net.sf.jguiraffe.gui.forms.ComponentHandler
import org.mockito.Matchers.{any, anyInt, anyString}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Promise

object ReorderControllerSpec {
  /** Start index of songs to be sorted. */
  private val StartIndex = 4
}

/**
  * Test class for ''ReorderController''.
  */
class ReorderControllerSpec extends FlatSpec with Matchers with MockitoSugar {
  import ReorderControllerSpec._

  /**
    * Helper method for testing a dummy implementation of a window event
    * listener method.
    *
    * @param f a function that invokes the listener method
    */
  private def checkDummyWindowListenerMethod(f: (ReorderController, WindowEvent) => Unit): Unit = {
    val event = mock[WindowEvent]
    val helper = new ReorderControllerTestHelper

    f(helper.controller, event)
    helper.verifyNoInteractions()
    verifyZeroInteractions(event)
  }

  "A ReorderController" should "do nothing when the window is deiconified" in {
    checkDummyWindowListenerMethod(_.windowDeiconified(_))
  }

  it should "do nothing when the window is closing" in {
    checkDummyWindowListenerMethod(_.windowClosing(_))
  }

  it should "do nothing when the window is closed" in {
    checkDummyWindowListenerMethod(_.windowClosed(_))
  }

  it should "do nothing when the window is activated" in {
    checkDummyWindowListenerMethod(_.windowActivated(_))
  }

  it should "do nothing when the window is deactivated" in {
    checkDummyWindowListenerMethod(_.windowDeactivated(_))
  }

  it should "do nothing when the window is iconified" in {
    checkDummyWindowListenerMethod(_.windowIconified(_))
  }

  /**
    * Creates a mock window event that is associated with a mock window.
    *
    * @param optWindow an optional window to be returned by the event
    * @return the window event
    */
  private def createWindowEventWithWindow(optWindow: Option[Window] = None): WindowEvent = {
    val event = mock[WindowEvent]
    val window = optWindow getOrElse mock[Window]
    doReturn(window).when(event).getSourceWindow
    event
  }

  /**
    * Expects an invocation of ''windowOpened()''. Prepares mocks for querying
    * the available reorder services.
    *
    * @param helper    the test helper
    * @param optWindow an optional window to be returned by the event
    * @return the window event
    */
  private def expectWindowOpened(helper: ReorderControllerTestHelper, optWindow: Option[Window] =
  None): WindowEvent = {
    val event = createWindowEventWithWindow(optWindow)
    val future = Promise[Seq[(PlaylistReorderer, String)]].future
    when(helper.reorderService.loadAvailableReorderServices()).thenReturn(future)
    event
  }

  it should "query reorder services when the window is opened" in {
    val helper = new ReorderControllerTestHelper
    val event: WindowEvent = expectWindowOpened(helper)

    helper.controller windowOpened event
    verify(helper.buttonHandler).setEnabled(false)
    verify(helper.reorderService).loadAvailableReorderServices()
    verifyZeroInteractions(helper.listHandler)
  }

  it should "populate the list model with the available reorder services" in {
    val event = createWindowEventWithWindow()
    val promise = Promise[Seq[(PlaylistReorderer, String)]]
    val services = List((mock[PlaylistReorderer], "B"), (mock[PlaylistReorderer], "A"),
      (mock[PlaylistReorderer], "C"))
    val helper = new ReorderControllerTestHelper
    when(helper.reorderService.loadAvailableReorderServices()).thenReturn(promise.future)

    helper.controller windowOpened event
    promise success services
    helper.syncedRunnable.run()
    val verInOrder = Mockito.inOrder(helper.listHandler)
    verInOrder.verify(helper.listHandler).addItem(0, "A", services(1)._1)
    verInOrder.verify(helper.listHandler).addItem(1, "B", services.head._1)
    verInOrder.verify(helper.listHandler).addItem(2, "C", services(2)._1)
    verInOrder.verify(helper.listHandler).setData(services(1)._1)
  }

  it should "handle an empty list of reorder services" in {
    val event = createWindowEventWithWindow()
    val promise = Promise[Seq[(PlaylistReorderer, String)]]
    val helper = new ReorderControllerTestHelper
    when(helper.reorderService.loadAvailableReorderServices()).thenReturn(promise.future)

    helper.controller windowOpened event
    promise success Nil
    helper.syncedRunnable.run()
    verify(helper.listHandler, never()).addItem(anyInt(), anyString(), any())
    verify(helper.listHandler, never()).setData(any())
  }

  /**
    * Creates an action event with the given command.
    *
    * @param command the command
    * @return the mock action event
    */
  private def createActionEvent(command: String): FormActionEvent = {
    val event = mock[FormActionEvent]
    when(event.getCommand).thenReturn(command)
    event
  }

  it should "trigger the reorder operation when OK is clicked" in {
    val window = mock[Window]
    val service = mock[PlaylistReorderer]
    val helper = new ReorderControllerTestHelper
    val windowEvent = expectWindowOpened(helper, Some(window))
    val event = createActionEvent("OK")
    doReturn(service).when(helper.listHandler).getData
    helper.controller windowOpened windowEvent

    helper.controller actionPerformed event
    verify(helper.reorderService).reorder(service, helper.songs, StartIndex)
    verify(window).close(true)
  }

  it should "close the window when CANCEL is clicked" in {
    val window = mock[Window]
    val helper = new ReorderControllerTestHelper
    val event = expectWindowOpened(helper, Some(window))
    helper.controller windowOpened event

    helper.controller actionPerformed createActionEvent("CANCEL")
    verify(window).close(true)
  }

  it should "disable the OK button if there is no selection" in {
    val helper = new ReorderControllerTestHelper
    doReturn(null).when(helper.listHandler).getData

    helper.controller elementChanged mock[FormChangeEvent]
    verify(helper.buttonHandler).setEnabled(false)
  }

  it should "enable the OK if a selection exists" in {
    val helper = new ReorderControllerTestHelper
    doReturn(mock[PlaylistReorderer]).when(helper.listHandler).getData

    helper.controller elementChanged mock[FormChangeEvent]
    verify(helper.buttonHandler).setEnabled(true)
  }

  /**
    * Test helper class which collects mock objects for dependencies.
    */
  private class ReorderControllerTestHelper {
    /** Stores the runnable passed to the sync object. */
    private val syncRunnable = new LinkedBlockingQueue[Runnable](2)

    /** The songs to be ordered. */
    val songs = createTestSongs()

    /** The mock list handler. */
    val listHandler = mock[ListComponentHandler]

    /** The mock button handler. */
    val buttonHandler = mock[ComponentHandler[_]]

    /** The mock reorder service. */
    val reorderService = mock[ReorderService]

    /** Mock for the sync object. */
    val sync = createSynchronizer()

    /** The controller to be tested. */
    val controller = new ReorderController(listHandler = listHandler, buttonHandler =
      buttonHandler, reorderService = reorderService, sync = sync, songs = songs, startIndex =
      StartIndex)

    /**
      * Checks that the mock objects have not been accessed.
      */
    def verifyNoInteractions(): Unit = {
      verifyZeroInteractions(listHandler, reorderService)
    }

    /**
      * Returns the ''Runnable'' that has been passed to the sync object. Fails
      * if there is none.
      *
      * @return the ''Runnable'' passed to the sync object
      */
    def syncedRunnable: Runnable = {
      val r = syncRunnable.poll(3, TimeUnit.SECONDS)
      r should not be null
      r
    }

    /**
      * Creates a mock for a ''GUISynchronizer''.
      *
      * @return the mock
      */
    private def createSynchronizer(): GUISynchronizer = {
      val sync = mock[GUISynchronizer]
      when(sync.asyncInvoke(any(classOf[Runnable]))).thenAnswer(new Answer[AnyRef] {
        override def answer(invocation: InvocationOnMock): AnyRef = {
          syncRunnable should have size 0
          syncRunnable.offer(invocation.getArguments.head.asInstanceOf[Runnable]) shouldBe true
          null
        }
      })
      sync
    }
  }

  /**
    * Creates a request for a reorder operation.
    *
    * @return the test request
    */
  private def createTestSongs(): Seq[SongData] =
    List(mock[SongData], mock[SongData])
}
