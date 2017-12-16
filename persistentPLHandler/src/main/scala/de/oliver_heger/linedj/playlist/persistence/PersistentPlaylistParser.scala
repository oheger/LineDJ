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

package de.oliver_heger.linedj.playlist.persistence

import java.nio.charset.StandardCharsets

import akka.util.ByteString
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure
import de.oliver_heger.linedj.io.parser._
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.audio.playlist.Playlist.SongList
import de.oliver_heger.linedj.player.engine.AudioSourcePlaylistInfo
import de.oliver_heger.linedj.playlist.persistence.PersistentPlaylistParser.PlaylistItem
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.{Success, Try}

object PersistentPlaylistParser {
  /** Property for the playlist index. */
  val PropIndex = "index"

  /** Property for the URI of the medium. */
  val PropMediumURI = "mediumURI"

  /** Property for the description path of the medium. */
  val PropMediumDescPath = "mediumDescriptionPath"

  /** Property for the component ID of the managing archive. */
  val PropArchiveCompID = "archiveComponentID"

  /** Property for the song URI. */
  val PropURI = "uri"

  /**
    * The type of playlist items produced by this parser implementation.
    * Because an item in the persistent file may be incomplete or invalid an
    * optional type is used.
    */
  type PlaylistItem = Try[(Int, AudioSourcePlaylistInfo)]

  /** The instance of the item parser. */
  private val ItemParser =
    new PersistentPlaylistParser(ParserImpl, JSONParser.jsonParser(ParserImpl))

  /** The logger. */
  private val Log = LoggerFactory.getLogger(classOf[PersistentPlaylistParser])

  /**
    * Returns a stage for extracting playlist items from a persistent playlist
    * file.
    *
    * @return the stage for extracting playlist items
    */
  def playlistParserStage: ParserStage[PlaylistItem] =
    new ParserStage(parseFunc)

  /**
    * Generates a ''Playlist'' from the given list of intermediate playlist
    * items. The list of songs is sorted, invalid items are filtered out, and
    * the current position is applied.
    *
    * @param items the list of playlist items
    * @return the resulting ''Playlist''
    */
  def generateFinalPlaylist(items: List[PlaylistItem], position: CurrentPlaylistPosition):
  Playlist = {
    if (Log.isInfoEnabled) {
      items.filter(_.isFailure) foreach (f => Log.info("Could not parse playlist item: " + f))
    }
    val songs = items.collect {
      case Success(item) => item
    }.sortWith(_._1 < _._1)
    applyPosition(songs, position)
  }

  /**
    * Creates a playlist by applying position information to the given list
    * of indexed songs.
    *
    * @param items    the list of songs and their indices
    * @param position position information
    * @return the resulting ''Playlist''
    */
  private def applyPosition(items: List[(Int, AudioSourcePlaylistInfo)],
                            position: CurrentPlaylistPosition): Playlist = {
    @tailrec def splitAndConvert(currentItems: List[(Int, AudioSourcePlaylistInfo)],
                                 played: SongList): Playlist =
      currentItems match {
        case h :: t =>
          if (h._1 >= position.index)
            Playlist(playedSongs = played,
              pendingSongs = applyOffsets(h, position) :: t.map(_._2))
          else splitAndConvert(t, h._2 :: played)

        case _ =>
          Playlist(playedSongs = played, pendingSongs = Nil)
      }

    splitAndConvert(items, Nil)
  }

  /**
    * Creates an ''AudioSourcePlaylistInfo'' object for the passed in list
    * element that uses the offsets defined by the position object.
    *
    * @param e        the list element
    * @param position the ''CurrentPlaylistPosition''
    * @return the updated ''AudioSourcePlaylistInfo''
    */
  private def applyOffsets(e: (Int, AudioSourcePlaylistInfo), position: CurrentPlaylistPosition):
  AudioSourcePlaylistInfo =
    if (e._1 == position.index)
      e._2.copy(skip = position.positionOffset, skipTime = position.timeOffset)
    else e._2

  /**
    * Converts a single item in the playlist to its model representation. If
    * properties are invalid or mandatory properties are missing, result is
    * ''None''.
    *
    * @param obj the object to be converted
    * @return the result of the conversion
    */
  private def convertItem(obj: Map[String, String]): PlaylistItem = Try {
    val idx = obj(PropIndex).toInt
    val mediumURI = obj(PropMediumURI)
    val compID = obj(PropArchiveCompID)
    val uri = obj(PropURI)
    val fileID = MediaFileID(MediumID(mediumURI, obj get PropMediumDescPath, compID), uri)
    (idx, AudioSourcePlaylistInfo(fileID, 0, 0))
  }

  /**
    * The function for parsing chunks of data.
    *
    * @param chunk       the current chunk
    * @param lastFailure the failure from the last parsing operation
    * @param lastChunk   flag whether this is the last chunk
    * @return partial parsing results and a failure for the current operation
    */
  private def parseFunc(chunk: ByteString, lastFailure: Option[Failure],
                        lastChunk: Boolean):
  (Iterable[PlaylistItem], Option[Failure]) =
    ItemParser.processChunk(chunk.decodeString(StandardCharsets.UTF_8), (), lastChunk, lastFailure)
}

/**
  * A class for parsing files with a JSON representation of a playlist.
  *
  * A playlist file consists of an array of JSON objects; each object
  * represents a song in the playlist and contains a number of identifying
  * properties.
  *
  * @param chunkParser the underlying ''ChunkParser''
  * @param jsonParser  the underlying JSON parser
  */
class PersistentPlaylistParser private(chunkParser: ChunkParser[ParserTypes.Parser,
  ParserTypes.Result, Failure], jsonParser: ParserTypes.Parser[JSONParser.JSONData])
  extends AbstractModelParser[PlaylistItem, Unit](chunkParser, jsonParser) {

  import PersistentPlaylistParser._

  override def convertJsonObjects(d: Unit, objects: IndexedSeq[Map[String, String]]):
  IndexedSeq[PlaylistItem] =
    objects map convertItem
}
