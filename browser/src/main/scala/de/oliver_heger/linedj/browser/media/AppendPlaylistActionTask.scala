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

package de.oliver_heger.linedj.browser.media

import de.oliver_heger.linedj.platform.audio.AppendPlaylist
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.builder.components.model.TableHandler

/**
  * A base class for action tasks that append certain data to the current
  * playlist.
  *
  * The media controller has a couple of actions for appending songs to the
  * current playlist. This is a base trait with common functionality needed
  * by the tasks for such actions. It already implements the logic for
  * sending the append message to the message bus. Derived classes need to
  * obtain the correct songs.
  *
  * @param messageBus the ''MessageBus''
  */
abstract class AppendPlaylistActionTask(messageBus: MessageBus) extends Runnable {
  override def run(): Unit = {
    messageBus publish createAppendMessage(fetchSongsToAppend())
  }

  /**
    * Obtains the songs that have to be appended to the playlist for this
    * specific action task. Typically, the songs can be fetched from the
    * media controller, but other sources are possible as well.
    * @return the sequence of songs to be appended to the playlist
    */
  protected def fetchSongsToAppend(): Seq[SongData]

  /**
    * Generates a message to append songs to the playlist based on the passed
    * in sequence.
    *
    * @param songs a sequence of ''SongData'' objects
    * @return the message to extend the playlist by these songs
    */
  private def createAppendMessage(songs: Seq[SongData]): AppendPlaylist =
    AppendPlaylist(songs.map(_.id).toList, activate = false)
}

/**
  * A specialized action task which appends all songs contained on the current
  * medium.
  *
  * @param controller the ''MediaController''
  * @param messageBus the ''MessageBus''
  */
class AppendMediumActionTask(controller: MediaController, messageBus: MessageBus) extends
AppendPlaylistActionTask(messageBus) {
  override protected def fetchSongsToAppend(): Seq[SongData] =
    controller.songsForSelectedMedium
}

/**
  * A specialized action task which appends all the songs of selected artists
  * to the current playlist.
  *
  * @param controller the ''MediaController''
  * @param messageBus the ''MessageBus''
  */
class AppendArtistActionTask(controller: MediaController, messageBus: MessageBus) extends
AppendPlaylistActionTask(messageBus) {
  override protected def fetchSongsToAppend(): Seq[SongData] =
    controller.songsForSelectedArtists
}

/**
  * A specialized action task which appends all the songs of selected albums to
  * the current playlist.
  *
  * @param controller the ''MediaController''
  * @param messageBus the ''MessageBus''
  */
class AppendAlbumActionTask(controller: MediaController, messageBus: MessageBus) extends
AppendPlaylistActionTask(messageBus) {
  override protected def fetchSongsToAppend(): Seq[SongData] =
    controller.songsForSelectedAlbums
}

/**
  * A specialized action task which appends all songs currently selected in the
  * songs table to the current playlist.
  *
  * @param tableHandler the handler of the songs table
  * @param messageBus the ''MessageBus''
  */
class AppendSongsActionTask(tableHandler: TableHandler, messageBus: MessageBus) extends
AppendPlaylistActionTask(messageBus) {
  override protected def fetchSongsToAppend(): Seq[SongData] = {
    val selectedIndices = tableHandler.getSelectedIndices
    selectedIndices map (tableHandler.getModel.get(_).asInstanceOf[SongData])
  }
}
