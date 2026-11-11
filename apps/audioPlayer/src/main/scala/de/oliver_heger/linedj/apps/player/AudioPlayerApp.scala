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

package de.oliver_heger.linedj.apps.player

import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist, PlaylistService}
import net.sf.jguiraffe.gui.app.ApplicationContext

import java.util.concurrent.atomic.AtomicReference

object AudioPlayerApp:
  /** The name of this application. */
  final val ApplicationName = "audioPlayer"

  /**
    * Name of a bean in the application context with the current player
    * configuration.
    */
  final val BeanPlayerConfig: String = ApplicationName + ".config"

  /** The bean name for the playlist service. */
  final val BeanPlaylistService = ApplicationName + ".playlistService"

  /** The bean name for the media file resolver service. */
  final val BeanResolverService = ApplicationName + ".resolverService"
end AudioPlayerApp

/**
  * The main application class for the audio player application.
  */
class AudioPlayerApp extends ClientApplication(AudioPlayerApp.ApplicationName), ApplicationAsyncStartup:

  import AudioPlayerApp.*

  /** Stores the playlist service. */
  private val refPlaylistService = new AtomicReference[PlaylistService[Playlist, String]]

  /** Stores the media file resolver service. */
  private val refResolverService = new AtomicReference[MediaFileInfoResolverService]

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
    * @inheritdoc This implementation adds some beans defined in the client
    *             application context to this application's ''BeanContext'',
    *             so that they are available everywhere in this application.
    */
  override def createApplicationContext(): ApplicationContext =
    val appCtx = super.createApplicationContext()
    addBeanDuringApplicationStartup(
      BeanPlayerConfig,
      AudioPlayerConfig(clientApplicationContext.managementConfiguration)
    )
    addBeanDuringApplicationStartup(BeanPlaylistService, refPlaylistService.get())
    addBeanDuringApplicationStartup(BeanResolverService, refResolverService.get())
    appCtx
