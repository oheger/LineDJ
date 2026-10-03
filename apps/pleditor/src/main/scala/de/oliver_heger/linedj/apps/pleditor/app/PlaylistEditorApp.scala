/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.pleditor.app

import de.oliver_heger.linedj.apps.pleditor.reorder.ReorderService
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist, PlaylistService}
import net.sf.jguiraffe.gui.app.ApplicationContext

import java.util.concurrent.atomic.AtomicReference

object PlaylistEditorApp:
  /** The bean name for the configuration bean. */
  final val BeanConfig = "pleditorApp_ExportConfiguration"

  /** The bean name for the reorder service. */
  final val BeanReorderService = "pleditor_reorderService"

  /** The bean name for the playlist service. */
  final val BeanPlaylistService = "pleditor_playlistService"

  /** The bean name for the media file resolver service. */
  final val BeanResolverService = "pleditor_resolverService"

  /** The bean name for the archive service. */
  final val BeanArchiveService = "pleditor_archiveService"
end PlaylistEditorApp

/**
  * The main application class of the LineDJ Playlist Editor application.
  */
class PlaylistEditorApp extends ClientApplication("pleditor"), ApplicationAsyncStartup:

  import PlaylistEditorApp.*

  /** Stores the reference to the reorder service. */
  private val refReorderService = new AtomicReference[ReorderService]

  /** Stores the playlist service. */
  private val refPlaylistService = new AtomicReference[PlaylistService[Playlist, String]]

  /** Stores the media file resolver service. */
  private val refResolverService = new AtomicReference[MediaFileInfoResolverService]

  /** Stores the archive service. */
  private val refArchiveService = new AtomicReference[ArchiveService]

  /**
    * Initializes the reference to the [[ReorderService]]. This method is
    * called by the declarative services runtime.
    *
    * @param service the [[ReorderService]]
    */
  def initReorderService(service: ReorderService): Unit =
    refReorderService.set(service)

  /**
    * Initializes the reference to the [[PlaylistService]]. This method is
    * called by the declarative services runtime.
    *
    * @param service the [[PlaylistService]]
    */
  def initPlaylistService(service: PlaylistService[Playlist, String]): Unit =
    refPlaylistService.set(service)

  /**
    * Initializes the reference to the [[MediaFileInfoResolverService]]. This
    * method is called by the declarative services runtime.
    *
    * @param service the [[MediaFileInfoResolverService]]
    */
  def initResolverService(service: MediaFileInfoResolverService): Unit =
    refResolverService.set(service)

  /**
    * Initializes the reference to the [[ArchiveService]]. This method is
    * called by the declarative services runtime.
    *
    * @param service the [[ArchiveService]]
    */
  def initArchiveService(service: ArchiveService): Unit =
    refArchiveService.set(service)

  /**
    * @inheritdoc This implementation adds some specific beans for the
    *             playlist editor application.
    */
  override def createApplicationContext(): ApplicationContext =
    val context = super.createApplicationContext()
    addBeanDuringApplicationStartup(BeanReorderService, refReorderService.get())
    addBeanDuringApplicationStartup(BeanPlaylistService, refPlaylistService.get())
    addBeanDuringApplicationStartup(BeanResolverService, refResolverService.get())
    addBeanDuringApplicationStartup(BeanArchiveService, refArchiveService.get())
    context
