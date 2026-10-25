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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.io.stream.StreamSizeRestrictionStage
import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.platform.audio2.playlist.Playlist
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}

import java.nio.file.{Files, NoSuchFileException, Path}
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

/**
  * An object defining functionality for loading playlist information stored on
  * the local file system.
  *
  * A persistent playlist as managed by this handler consists of two files:
  *  - One file containing the IDs of the songs in the playlist; the order of
  *    these IDs corresponds to the order of the songs in the playlist.
  *  - One file with position information which allows continuing playback at
  *    the position the playlist was saved.
  *
  * Both files are in JSON format. The position file is optional. If it does
  * not exist or is corrupt, playback starts with the first song in the
  * playlist.
  *
  * This object provides a function to asynchronously loading the data files
  * and combining the data to a _SetPlaylist_ message that can be passed to the
  * audio platform to enable this playlist.
  */
object PlaylistLoader:
  /**
    * A data class collecting the configuration properties for loading a 
    * persistent playlist.
    *
    * @param playlistPath the path to the file with playlist items
    * @param positionPath the path to the file with the current position
    * @param maxFileSize  the maximum size of files that can be loaded
    */
  final case class Config(playlistPath: Path,
                          positionPath: Path,
                          maxFileSize: Int)

  /**
    * Type alias for a function that loads a persistent playlist 
    * asynchronously. The function loads the files that comprise the playlist
    * and combines the resulting data to a command to set and activate this
    * playlist. If no file with playlist items is found at the specified 
    * location, the [[Option]] returned in the [[Future]] is _None_.
    */
  type LoaderFunc = Config => Future[Option[AudioPlayerCommands]]

  /** The logger. */
  private val log = LogManager.getLogger(classOf[PlaylistLoader.type])

  /**
    * A dummy position object that is used if no file with position information
    * is available or can be loaded.
    */
  private final val NoPosition = PlaylistModel.CurrentPlaylistPosition(0, 0L, 0L)

  /**
    * A factory trait for creating a [[LoaderFunc]].
    */
  trait Factory:
    /**
      * Returns a [[LoaderFunc]] that makes use of the provided actor system.
      *
      * @param system the actor system
      * @return the [[LoaderFunc]]
      */
    def apply(using system: ActorSystem): LoaderFunc
  end Factory

  import de.oliver_heger.linedj.shared.actors.ActorFactory.given

  /**
    * A [[Factory]] instance to create the default [[LoaderFunc]].
    */
  final val default: Factory = new Factory:
    override def apply(using system: ActorSystem): LoaderFunc = config =>
      if Files.exists(config.playlistPath) then
        log.info("Trying to load the playlist from file '{}'.", config.playlistPath)
        log.info("Trying to load position info from file '{}'.", config.positionPath)
        val futPlaylist = loadPlaylistFile(config.playlistPath, config.maxFileSize)
        val futPosition = loadPositionFile(config.positionPath, config.maxFileSize)
        for
          playlist <- futPlaylist
          optPosition <- futPosition
        yield
          Some(applyPosition(playlist.reverse, optPosition.getOrElse(NoPosition)))
      else
        log.info("File '{}' does not exist. No playlist is loaded", config.playlistPath)
        Future.successful(None)

  /**
    * Starts a stream to load the file with playlist items at the provided 
    * path. Returns a [[Future]] with the items extracted from this file.
    *
    * @param path   the path to the playlist file
    * @param system the actor system
    * @return a [[Future]] with the items in the playlist
    */
  private def loadPlaylistFile(path: Path, maxSize: Int)(using system: ActorSystem): Future[List[PlaylistModel.PlaylistItem]] =
    val source = FileIO.fromPath(path).via(new StreamSizeRestrictionStage(maxSize))
    val itemsSource = JsonStreamParser.parseStream[PlaylistModel.PlaylistItem, Any](source)
    val sink = Sink.fold[List[PlaylistModel.PlaylistItem], PlaylistModel.PlaylistItem](Nil): (lst, item) =>
      item :: lst
    itemsSource.runWith(sink)

  /**
    * Loads a file with position information about a playlist.
    *
    * @param path    the path to the position file
    * @param maxSize the maximum file size
    * @return a [[Future]] for the result of the load operation
    */
  private def loadPositionFile(path: Path, maxSize: Int)
                              (using system: ActorSystem): Future[Option[PlaylistModel.CurrentPlaylistPosition]] =
    JsonStreamParser.parseFile[PlaylistModel.CurrentPlaylistPosition](path, maxSize).map(Some(_))
      .recover:
        case e: NoSuchFileException =>
          // This is a normal situation, so do not log an exception.
          None
        case e =>
          log.error("Failed to load position file '{}'.", path, e)
          None

  /**
    * Creates a command to set the playlist by applying position information to
    * the given list of playlist items.
    *
    * @param items    the list of items in the playlist
    * @param position position information
    * @return the resulting command to set the playlist
    */
  private def applyPosition(items: List[PlaylistModel.PlaylistItem],
                            position: PlaylistModel.CurrentPlaylistPosition): AudioPlayerCommands =
    @tailrec def splitAndConvert(currentItems: List[(PlaylistModel.PlaylistItem, Int)],
                                 played: List[String]): AudioPlayerCommands =
      currentItems match
        case h :: t =>
          if h._2 >= position.index then
            val playlist = Playlist(
              playedSongs = played,
              pendingSongs = h._1.fileID :: t.map(_._1.fileID)
            )
            applyOffsets(playlist, h._2, position)
          else
            splitAndConvert(t, h._1.fileID :: played)

        case _ =>
          AudioPlayerCommands.SetPlaylist(
            Playlist(playedSongs = played, pendingSongs = Nil),
            closePlaylist = played.nonEmpty
          )

    splitAndConvert(items.zipWithIndex, Nil)

  /**
    * Transforms a [[Playlist]] to a _SetPlaylist_ command by applying
    * position information if applicable. Position and time offsets are set
    * only if the song with the current index matches.
    *
    * @param pl       the [[Playlist]]
    * @param curIdx   the index of the current song in the playlist
    * @param position the object with position information
    * @return the command for setting the playlist
    */
  private def applyOffsets(pl: Playlist,
                           curIdx: Int,
                           position: PlaylistModel.CurrentPlaylistPosition): AudioPlayerCommands =
    if curIdx == position.index then
      AudioPlayerCommands.SetPlaylist(
        playlist = pl,
        positionOffset = position.position,
        timeOffset = position.time.seconds
      )
    else
      AudioPlayerCommands.SetPlaylist(playlist = pl)
