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

import java.nio.file.Path

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.io.stream.ListSeparatorStage
import de.oliver_heger.linedj.platform.audio.AudioPlayerState
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.player.engine.{AudioSourcePlaylistInfo, PlaybackProgressEvent}
import de.oliver_heger.linedj.playlist.persistence.PlaylistFileWriterActor.{FileWritten, WriteFile}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.duration.FiniteDuration

object PlaylistStateWriterActor {
  /**
    * Creates a ''Props'' object for the creation of a new actor instance.
    *
    * @param pathPlaylist     the path to the file with the playlist
    * @param pathPosition     the path to the file with the current position
    * @param autoSaveInterval the auto save interval
    * @return ''Props'' for a new actor instance
    */
  def apply(pathPlaylist: Path, pathPosition: Path,
            autoSaveInterval: FiniteDuration): Props =
    Props(classOf[PlaylistStateWriterActorImpl], pathPlaylist, pathPosition, autoSaveInterval)

  private class PlaylistStateWriterActorImpl(pathPlaylist: Path, pathPosition: Path,
                                             autoSaveInterval: FiniteDuration)
    extends PlaylistStateWriterActor(pathPlaylist, pathPosition, autoSaveInterval)
      with ChildActorFactory

  /**
    * Generates a string for the specified playlist item.
    *
    * @param item the item in the playlist
    * @param idx  the index of this item
    * @return a string representation of this item
    */
  private def convertItem(item: AudioSourcePlaylistInfo, idx: Int): String = {
    val descPath = generateDescriptionPath(item.sourceID.mediumID)
    s"""{
       |"${PersistentPlaylistParser.PropIndex}": $idx,
       |"${PersistentPlaylistParser.PropMediumURI}": "${item.sourceID.mediumID.mediumURI}",$descPath
       |"${PersistentPlaylistParser.PropArchiveCompID}": "${item.sourceID.mediumID
      .archiveComponentID}",
       |"${PersistentPlaylistParser.PropURI}": "${item.sourceID.uri}"
       |}
    """.stripMargin
  }

  /**
    * Generates a string for the optional medium description path. If no
    * description path is defined, result is an empty string.
    *
    * @param mid the medium ID
    * @return a string for the description path
    */
  private def generateDescriptionPath(mid: MediumID): String =
    mid.mediumDescriptionPath.map("\n\"" + PersistentPlaylistParser.PropMediumDescPath +
      "\": \"" + _ + "\",") getOrElse ""

  /**
    * Generates a string for the specified current playlist position.
    *
    * @param position the position
    * @return the JSON representation for this position
    */
  private def convertPosition(position: CurrentPlaylistPosition): ByteString =
    ByteString(
      s"""{
         |"${CurrentPositionParser.PropIndex}": ${position.index},
         |"${CurrentPositionParser.PropPosition}": ${position.positionOffset},
         |"${CurrentPositionParser.PropTime}": ${position.timeOffset}
         |}
     """.stripMargin)
}

/**
  * An actor responsible for storing the current state of a playlist.
  *
  * This actor is notified whenever there are changes in the current state of
  * the audio player and when a ''PlaybackProgressEvent'' arrives. It then
  * decides whether playlist information needs to be persisted. If so, it calls
  * a child writer actor with the data to be persisted.
  *
  * The actor keeps track on ongoing write operations; a file can only be
  * written after the previous write operation completes. It also supports a
  * graceful shutdown, so that pending data can be written before the
  * application stops.
  *
  * Per default, an update of the playlist position is written each time
  * playback of a new song begins. For longer songs it makes sense to write
  * intermediate updates. This can be configured by an auto save interval. A
  * save operation is then triggered after ongoing playback for this time span.
  *
  * @param pathPlaylist     the path to the file with the playlist
  * @param pathPosition     the path to the file with the current position
  * @param autoSaveInterval the auto save interval
  */
class PlaylistStateWriterActor(pathPlaylist: Path, pathPosition: Path,
                               autoSaveInterval: FiniteDuration) extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import PlaylistStateWriterActor._

  /** The playlist service. */
  private val plService = PlaylistService

  /** The child actor for file write operations. */
  private var fileWriterActor: ActorRef = _

  /**
    * A map for keeping track on write operations that are blocked by ongoing
    * operations.
    */
  private var pendingWriteOperations = Map.empty[Path, WriteFile]

  /**
    * A set for keeping track on write operations that are currently executed.
    */
  private var writesInProgress = Set.empty[Path]

  /** Stores the sequence number of the current playlist. */
  private var playlistSeqNo: Option[Int] = None

  /** Stores information about the current playlist position. */
  private var currentPosition = CurrentPlaylistPosition(0, 0, 0)

  /** Stores an updated position that has not yet been written. */
  private var updatedPosition = currentPosition

  /** Stores the sender of a close request. */
  private var closeRequest: Option[ActorRef] = None

  override def preStart(): Unit = {
    super.preStart()
    fileWriterActor = createChildActor(Props[PlaylistFileWriterActor])
  }

  override def receive: Receive = {
    case AudioPlayerState(playlist, no, _, _) if closeRequest.isEmpty =>
      handlePlaylistUpdate(playlist, no)
      handlePositionUpdate(extractPosition(playlist))

    case PlaybackProgressEvent(ofs, time, _, _) if closeRequest.isEmpty &&
      playlistSeqNo.isDefined =>
      val position = CurrentPlaylistPosition(currentPosition.index, ofs, time)
      handlePositionUpdate(position)

    case FileWritten(path, _) =>
      writesInProgress -= path
      pendingWriteOperations.get(path) foreach { msg =>
        callWriteActor(msg)
        pendingWriteOperations -= path
      }
      if (writesInProgress.isEmpty) {
        closeRequest foreach (_ ! CloseAck(self))
      }

    case CloseRequest =>
      if (updatedPosition != currentPosition) {
        writePosition(updatedPosition)
      }
      if (writesInProgress.isEmpty) {
        sender ! CloseAck(self)
      }
      closeRequest = Some(sender())
  }

  /**
    * Handles an update notification for a playlist. If the change is
    * relevant, the updated playlist is written to disk.
    *
    * @param playlist the playlist
    * @param no       the playlist sequence number
    */
  private def handlePlaylistUpdate(playlist: Playlist, no: Int): Unit = {
    playlistSeqNo match {
      case Some(seqNo) =>
        if (seqNo != no) {
          val source = Source(plService.toSongList(playlist))
          val sepStage =
            new ListSeparatorStage[AudioSourcePlaylistInfo]("[\n", ",\n", "\n]\n")(convertItem)
          triggerWrite(source.via(sepStage), pathPlaylist)
          playlistSeqNo = Some(no)
        }

      case None =>
        playlistSeqNo = Some(no)
        currentPosition = extractPosition(playlist)
    }
  }

  /**
    * Handles an update notification for a position. If there is a relevant
    * change, the position data is written to disk.
    *
    * @param position the object representing the updated position
    */
  private def handlePositionUpdate(position: CurrentPlaylistPosition): Unit = {
    if (positionChanged(position)) {
      writePosition(position)
    }
    updatedPosition = position
  }

  /**
    * Sends a write request to store the specified position.
    *
    * @param position the position to be written
    */
  private def writePosition(position: CurrentPlaylistPosition): Unit = {
    triggerWrite(Source.single(convertPosition(position)), pathPosition)
    currentPosition = position
  }

  /**
    * Extracts information about the current position from the passed in
    * playlist. If the playlist has no current element, a position after the
    * end is returned.
    *
    * @param playlist the ''Playlist''
    * @return an object with the current position
    */
  private def extractPosition(playlist: Playlist): CurrentPlaylistPosition = {
    val currentInfo = for {
      song <- plService.currentSong(playlist)
      idx <- plService.currentIndex(playlist)
    } yield (song, idx)
    currentInfo map { current =>
      CurrentPlaylistPosition(current._2, current._1.skip, current._1.skipTime)
    } getOrElse CurrentPlaylistPosition(plService.size(playlist), 0, 0)
  }

  /**
    * Checks whether a relevant change in the playlist position took place. If
    * so, the position data has to be written.
    *
    * @param position the updated position
    * @return a flag whether there is a relevant change
    */
  private def positionChanged(position: CurrentPlaylistPosition): Boolean =
    position.index != currentPosition.index ||
      position.timeOffset - currentPosition.timeOffset >= autoSaveInterval.toSeconds

  /**
    * Triggers a write operation for the specified data. This method also keeps
    * track on ongoing write operations and prevents that the same file is
    * written multiple times concurrently.
    *
    * @param source the source defining the file content
    * @param path   the path to the file to be written
    */
  private def triggerWrite(source: Source[ByteString, Any], path: Path): Unit = {
    val writeMsg = WriteFile(source, path)
    if (writesInProgress contains path) {
      pendingWriteOperations += path -> writeMsg
    } else {
      callWriteActor(writeMsg)
    }
  }

  /**
    * Actually invokes the write actor with the specified data and records the
    * ongoing write operation.
    *
    * @param writeMsg the write message
    */
  private def callWriteActor(writeMsg: WriteFile): Unit = {
    fileWriterActor ! writeMsg
    writesInProgress += writeMsg.target
    log.info("Saving playlist information to {}.", writeMsg.target)
  }
}
