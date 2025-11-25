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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.content.MediaFileActor.MediaFileCommand.AddFile
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/**
  * Implementation of an actor that manages a map with all media files
  * contained in the archive.
  *
  * This actor provides a mapping from media file IDs to detail information
  * about these files. The mapping is used to support download requests for
  * files from the archive.
  */
object MediaFileActor:
  enum MediaFileCommand:
    /**
      * A command notifying the actor about a media file contained in the
      * archive. The actor adds this file to its internal mapping.
      *
      * @param mediumID the ID of the hosting medium
      * @param fileUri  the URI pointing to the location of the file
      * @param metadata the metadata about the file
      */
    case AddFile(mediumID: Checksums.MediumChecksum,
                 fileUri: MediaFileUri,
                 metadata: MediaMetadata)

    /**
      * A command to request information about a specific file. If the provided
      * file ID is known to this actor, information about the file is returned
      * in a [[ArchiveCommands.GetFileInfoResponse]] message.
      *
      * @param fileID  the ID of the file in question
      * @param replyTo the actor to receive the response
      */
    case GetFileInfo(fileID: String,
                     replyTo: ActorRef[ArchiveCommands.GetFileInfoResponse])
  end MediaFileCommand

  /**
    * A factory trait for creating new instances of this actor.
    */
  trait Factory:
    /**
      * Returns a [[Behavior]] for creating a new actor instance.
      *
      * @return the [[Behavior]] for the new instance
      */
    def apply(): Behavior[MediaFileCommand]
  end Factory

  /** The standard factory for creating a new instance. */
  final val behavior: Factory = () => handleCommand(Map.empty)

  /**
    * The main command handling function of this actor.
    *
    * @param files the current map with file information
    * @return the next behavior of this actor
    */
  private def handleCommand(files: Map[String, ArchiveModel.MediaFileInfo]): Behavior[MediaFileCommand] =
    Behaviors.receiveMessage:
      case MediaFileCommand.AddFile(mediumID, fileUri, metadata) =>
        val fileInfo = ArchiveModel.MediaFileInfo(
          metadata = metadata,
          relativePath = fileUri.path,
          mediumID = mediumID
        )
        handleCommand(files + (metadata.checksum -> fileInfo))

      case MediaFileCommand.GetFileInfo(fileID, replyTo) =>
        replyTo ! ArchiveCommands.GetFileInfoResponse(fileID, files.get(fileID))
        Behaviors.same
