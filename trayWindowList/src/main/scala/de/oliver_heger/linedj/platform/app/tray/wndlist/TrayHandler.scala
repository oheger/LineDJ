/*
 * Copyright 2015-2025 The Developers Team.
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

import org.apache.logging.log4j.LogManager

import java.awt.{Image, PopupMenu, SystemTray, TrayIcon}

/**
  * An internally used helper trait which wraps access to the system tray.
  *
  * This trait is used rather than direct access to the tray, which typically
  * requires static method calls. When using the trait code is easier to test.
  */
private trait TrayHandler:
  /**
    * Adds an icon to the tray and returns a handler object that can be used to
    * manipulate it later. If it is not possible to add the icon, ''None'' is
    * returned.
    *
    * @param icon the image of the tray icon
    * @param tip  a tool tip for the icon
    * @return an option for the icon handler
    */
  def addIcon(icon: Image, tip: String): Option[TrayIconHandler]

/**
  * Default implementation of the ''TrayHandler'' trait.
  */
private object TrayHandlerImpl extends TrayHandler:
  /** Logger. */
  private val Log = LogManager.getLogger(getClass)

  /**
    * Adds an icon to the tray and returns a handler object that can be used to
    * manipulate it later. If it is not possible to add the icon, ''None'' is
    * returned.
    *
    * @param icon the image of the tray icon
    * @param tip  a tool tip for the icon
    * @return an option for the icon handler
    */
  override def addIcon(icon: Image, tip: String): Option[TrayIconHandler] =
    fetchSystemTray() flatMap (t => addIconToTray(t, icon, tip))

  /**
    * Implementation of the ''addIcon()'' method assuming that the system tray
    * has already been obtained.
    *
    * @param tray the system tray
    * @param icon the icon
    * @param tip  the tool tip
    * @return the icon handler
    */
  def addIconToTray(tray: SystemTray, icon: Image, tip: String): Option[TrayIconHandler] =
    Log.info("Adding system tray icon.")
    try
      val trayIcon = new TrayIcon(icon, tip)
      tray add trayIcon
      Some(new TrayIconHandler(trayIcon, tray))
    catch
      case ex: Exception =>
        Log.error("Could not add system tray icon!", ex)
        None

  /**
    * Tries to obtain the system tray. If it is not supported, result is
    * ''None''.
    *
    * @return an option for the system tray
    */
  private def fetchSystemTray(): Option[SystemTray] =
    try Some(SystemTray.getSystemTray)
    catch
      case ex: Exception =>
        Log.error("Could not obtain system tray!", ex)
        None

/**
  * A wrapper around a ''TrayIcon'' object.
  *
  * An instance can be used to manipulate the icon and to remove it when it is
  * no longer needed.
  *
  * @param icon the wrapped ''TrayIcon''
  * @param tray the ''SystemTray''
  */
private class TrayIconHandler(val icon: TrayIcon, val tray: SystemTray) {
  def updateMenu(menu: PopupMenu): Unit =
    icon setPopupMenu menu

  /**
    * Removes the wrapped icon from the system tray. This method should be
    * called once when the icon is no longer needed.
    */
  def remove(): Unit =
    tray remove icon
}