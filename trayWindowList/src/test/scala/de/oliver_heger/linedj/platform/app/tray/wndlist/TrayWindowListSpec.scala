/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.tray.wndlist

import java.awt.event.ItemEvent
import java.awt.{CheckboxMenuItem, Image, PopupMenu}
import java.util.Locale
import de.oliver_heger.linedj.platform.app.hide.*
import de.oliver_heger.linedj.platform.app.{ApplicationManager, ClientApplication, ClientApplicationContext, ClientApplicationContextImpl}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.osgi.service.component.ComponentContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''TrayWindowList''.
  */
class TrayWindowListSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A TrayWindowList" should "create a default tray handler" in:
    val list = new TrayWindowList

    list.trayHandler should be(TrayHandlerImpl)

  it should "create a default sync object" in:
    val list = new TrayWindowList

    list.sync should not be null

  it should "add a tray icon on its activation" in:
    val helper = new TrayWindowListTestHelper
    val oldLocale = Locale.getDefault
    Locale setDefault Locale.GERMAN

    try
      val (image, tip) = helper.prepareAndActivate()
        .verifyTrayIconAdded()
      image should not be null
      tip should be("LineDJ Platform")
      helper.numberOfTrayActions should be(1)
    finally
      Locale setDefault oldLocale

  it should "register a consumer at the hiding window manager" in:
    val helper = new TrayWindowListTestHelper

    helper.prepareAndActivate().verifyConsumerRegistration()

  it should "skip the consumer registration if no tray icon could be added" in:
    val helper = new TrayWindowListTestHelper

    helper.prepareTrayIcon(success = false).activateWindowList()
    helper.publishedMessages should have size 0

  it should "remove the tray icon on deactivation" in:
    val helper = new TrayWindowListTestHelper

    helper.prepareAndActivate().deactivateWindowList()
      .verifyTrayIconRemoved()
      .numberOfTrayActions should be(2)

  it should "remove the consumer on deactivation" in:
    val helper = new TrayWindowListTestHelper

    helper.prepareAndActivate().deactivateWindowList()
      .verifyConsumerUnregistration()

  it should "deal with a failed handler registration on deactivation" in:
    val helper = new TrayWindowListTestHelper

    helper.prepareTrayIcon(success = false).activateWindowList()
      .deactivateWindowList()

  it should "create checked menu items for all application titles" in:
    val appA, appB, appC = mock[ClientApplication]
    val titles = List("A-App", "b-App", "C-App")
    val appsWithTitles = List((appB, titles(1)), (appC, titles(2)),
      (appA, titles.head))
    val state = ApplicationWindowState(appsWithTitles, Set(appB, appC))
    val helper = new TrayWindowListTestHelper

    val menu = helper.updateWindowStateTest(state)
    helper.numberOfTrayActions should be(2)
    val items = (0 to 2) map (i => menu.getItem(i).asInstanceOf[CheckboxMenuItem])
    items.map(_.getLabel) should be(titles)
    items.map(_.getState) should be(List(false, true, true))

  it should "create a menu item to exit the platform" in:
    val oldLocale = Locale.getDefault
    Locale setDefault Locale.GERMAN
    val state = ApplicationWindowState(List((mock[ClientApplication], "foo")), Set.empty)
    val helper = new TrayWindowListTestHelper

    try
      val menu = helper updateWindowStateTest state
      val exitItem = menu.getItem(menu.getItemCount - 1)
      exitItem.getLabel should be("Beenden")
    finally
      Locale setDefault oldLocale

  /**
    * Searches for a registered event listeners. As we cannot be sure which
    * internal listeners have been registered at menu items, this method
    * searches for a listener in the implementation package.
    *
    * @param listeners the array of listeners to be sarched for
    * @tparam T the type of listeners
    * @return the found listener
    */
  private def findListener[T](listeners: Array[T]): T =
    listeners.find(_.getClass.getName.startsWith(getClass.getPackage.getName)).get

  it should "add listeners to change an app's visibility state" in:
    val app = mock[ClientApplication]
    val state = ApplicationWindowState(List((app, "foo")), Set(app))
    val helper = new TrayWindowListTestHelper
    val menu = helper updateWindowStateTest state

    val item = menu.getItem(0).asInstanceOf[CheckboxMenuItem]
    val listener = findListener(item.getItemListeners)
    listener.itemStateChanged(new ItemEvent(item, 0, item, ItemEvent.DESELECTED))
    listener.itemStateChanged(new ItemEvent(item, 0, item, ItemEvent.SELECTED))
    helper.publishedMessages.tail should be(List(HideApplicationWindow(app),
      ShowApplicationWindow(app)))

  it should "add an action listener to exit the platform" in:
    val state = ApplicationWindowState(List((mock[ClientApplication], "foo")), Set.empty)
    val helper = new TrayWindowListTestHelper
    val menu = helper updateWindowStateTest state

    val item = menu.getItem(menu.getItemCount - 1)
    val listener = findListener(item.getActionListeners)
    listener.actionPerformed(null)
    helper.publishedMessages.tail should be(List(ExitPlatform(helper.appManager)))

  /**
    * A test helper class managing dependencies of an instance under test.
    */
  private class TrayWindowListTestHelper:
    /** A mock for the application manager. */
    val appManager: ApplicationManager = mock[ApplicationManager]

    /** The client application context passed to the test instance. */
    private val clientContext = createClientAppCtx()

    /** A mock tray handler. */
    private val trayHandler = mock[TrayHandler]

    /** A mock sync object. */
    private val traySync = createSync()

    /** A mock tray icon handler. */
    private val trayIconHandler = mock[TrayIconHandler]

    /** The window list to be tested. */
    private val windowList: TrayWindowList = createWindowList()

    /** The messages published to the message bus. */
    private var publishedMessagesList = List.empty[Any]

    /** A counter for the invocations of tray actions. */
    private var syncCount = 0

    /**
      * Prepares mock objects to expect a tray icon to be added to the system
      * tray. Depending on the success flag, either an icon handler or
      * ''None'' is returned by this operation.
      *
      * @param success the success flag
      * @return this test helper
      */
    def prepareTrayIcon(success: Boolean = true): TrayWindowListTestHelper =
      val result = if success then Some(trayIconHandler) else None
      when(trayHandler.addIcon(any(classOf[Image]), anyString()))
        .thenReturn(result)
      this

    /**
      * Verifies that a tray icon has been added and returns its properties.
      *
      * @return a tuple with the image and the tool tip string
      */
    def verifyTrayIconAdded(): (Image, String) =
      val captImage = ArgumentCaptor.forClass(classOf[Image])
      val captTip = ArgumentCaptor.forClass(classOf[String])
      verify(trayHandler).addIcon(captImage.capture(), captTip.capture())
      (captImage.getValue, captTip.getValue)

    /**
      * Returns the number of actions executed by the tray sync object.
      *
      * @return the number of tray actions
      */
    def numberOfTrayActions: Int = syncCount

    /**
      * Returns the messages that have been published to the message bus.
      *
      * @return a list with the published messages
      */
    def publishedMessages: List[Any] =
      publishedMessagesList.reverse

    /**
      * Activates the window list component.
      *
      * @return this test helper
      */
    def activateWindowList(): TrayWindowListTestHelper =
      windowList activate mock[ComponentContext]
      this

    /**
      * Verifies that the test instance added a window state consumer
      * registration.
      *
      * @return this test helper
      */
    def verifyConsumerRegistration(): TrayWindowListTestHelper =
      publishedMessages.head should be(windowList.Registration)
      windowList.Registration.id should not be null
      this

    /**
      * Verifies that the consumer registration has been removed again.
      *
      * @return this test helper
      */
    def verifyConsumerUnregistration(): TrayWindowListTestHelper =
      publishedMessages should have size 2
      publishedMessages.last should be(WindowStateConsumerUnregistration(
        windowList.Registration.id))
      this

    /**
      * Prepares to mocks to expect that the tray icon was added and activates
      * the test component.
      *
      * @return this test helper
      */
    def prepareAndActivate(): TrayWindowListTestHelper =
      prepareTrayIcon()
      activateWindowList()

    /**
      * Invokes the deactivate() method of the test component.
      *
      * @return this test helper
      */
    def deactivateWindowList(): TrayWindowListTestHelper =
      windowList deactivate mock[ComponentContext]
      this

    /**
      * Verifies that the tray icon has been removed from the system tray.
      *
      * @return this test helper
      */
    def verifyTrayIconRemoved(): TrayWindowListTestHelper =
      verify(trayIconHandler).remove()
      this

    /**
      * Executes a test with an updated window state. Mocks are prepared, the
      * test component is activated, and the specified state is passed to the
      * consumer function. It is then expected that a popup menu is passed to
      * the tray icon handler.
      *
      * @param state the state to be passed to the consumer function
      * @return the popup menu passed to the tray icon handler
      */
    def updateWindowStateTest(state: ApplicationWindowState): PopupMenu =
      prepareAndActivate()
      windowList.Registration.callback(state)
      val captor = ArgumentCaptor.forClass(classOf[PopupMenu])
      verify(trayIconHandler).updateMenu(captor.capture())
      captor.getValue

    /**
      * Creates the window list test instance
      *
      * @return the test window list instance
      */
    private def createWindowList(): TrayWindowList =
      val list = new TrayWindowList(trayHandler, traySync)
      list initClientApplicationContext clientContext
      list initApplicationManager appManager
      list

    /**
      * Creates the client application context. The message bus is prepared to
      * expect messages.
      *
      * @return the context
      */
    private def createClientAppCtx(): ClientApplicationContext =
      val ctx = new ClientApplicationContextImpl
      doAnswer((invocation: InvocationOnMock) => {
        publishedMessagesList = invocation.getArguments.head :: publishedMessagesList
        null
      }).when(ctx.messageBus).publish(any)
      ctx

    /**
      * Creates a stub sync object which immediately executes the passed in
      * action.
      *
      * @return the sync object
      */
    private def createSync(): TraySynchronizer =
      new TraySynchronizer:
        override def schedule(f: => Unit): Unit =
          syncCount += 1
          f

