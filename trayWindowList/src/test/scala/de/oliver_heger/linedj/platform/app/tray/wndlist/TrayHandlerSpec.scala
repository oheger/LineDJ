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

package de.oliver_heger.linedj.platform.app.tray.wndlist

import java.awt.{Image, PopupMenu, SystemTray, TrayIcon}
import javax.swing.ImageIcon

import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object TrayHandlerSpec {
  /** A test tool tip. */
  private val Tip = "Tray Tool Tip"

  /**
    * Returns an image to be used for the tray icon.
    *
    * @return the image
    */
  private def createImage(): Image = {
    val url = classOf[TrayHandler].getResource("/icon.png")
    new ImageIcon(url).getImage
  }
}

/**
  * Test class for ''TrayHandler''.
  */
class TrayHandlerSpec extends FlatSpec with Matchers with MockitoSugar {

  import TrayHandlerSpec._

  "TrayHandlerImpl" should "create a correct icon handler" in {
    val tray = mock[SystemTray]
    val image = createImage()

    val handler = TrayHandlerImpl.addIconToTray(tray, image, Tip).get
    val captor = ArgumentCaptor.forClass(classOf[TrayIcon])
    verify(tray).add(captor.capture())
    handler.tray should be(tray)
    handler.icon should be(captor.getValue)
    handler.icon.getImage should be(image)
    handler.icon.getToolTip should be(Tip)
  }

  it should "handle an exception when adding the icon to the tray" in {
    val tray = mock[SystemTray]
    when(tray.add(any(classOf[TrayIcon]))).thenThrow(new IllegalArgumentException)

    TrayHandlerImpl.addIconToTray(tray, createImage(), Tip) shouldBe 'empty
  }

  it should "do a complete add icon operation" in {
    // as the tray may not be available, we can only check that no exception
    // is thrown
    val handler = TrayHandlerImpl.addIcon(createImage(), Tip)
    handler foreach (_.remove())
  }

  "A TrayIconHandler" should "remove the icon from the tray" in {
    val tray = mock[SystemTray]
    val icon = mock[TrayIcon]
    val handler = new TrayIconHandler(icon, tray)

    handler.remove()
    verify(tray).remove(icon)
  }

  it should "set the icon's popup menu" in {
    val icon = new TrayIcon(createImage())
    val menu = new PopupMenu
    val handler = new TrayIconHandler(icon, mock[SystemTray])

    handler updateMenu menu
    icon.getPopupMenu should be(menu)
  }
}
