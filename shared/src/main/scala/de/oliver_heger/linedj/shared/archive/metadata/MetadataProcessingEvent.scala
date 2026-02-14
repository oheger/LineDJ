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

package de.oliver_heger.linedj.shared.archive.metadata

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumDescription, MediumID}
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingResult
import org.apache.pekko.actor as classic

import java.nio.file.Path

/**
  * A type representing event notifications sent during scanning of media 
  * archives and processing of their media files. Listeners can subscribe for
  * such events to keep track of the status of media processing.
  */
enum MetadataProcessingEvent:
  /**
    * A metadata event indicating that the processing of a media archive 
    * consisting of multiple components starts. The passed in group manager
    * actor controls the archive components and is responsible for triggering
    * scan operations on all of them. For each component, corresponding
    * [[ScanStarts]] and [[ScanCompleted]] events will be generated. If all of
    * them are done, a [[ProcessingCompleted]] event follows. This start event
    * is typically the first one received by event listeners.
    *
    * @param groupManager the group manager actor handling the archive 
    *                     components
    */
  case ProcessingStarts(groupManager: classic.ActorRef)

  /**
    * A metadata event indicating that the processing of the media archive
    * controlled by the given group manager actor is now complete. This event
    * is typically the last one received by event listeners for a group of
    * archive components.
    *
    * @param groupManager the group manager actor handling the archive 
    *                     components
    */
  case ProcessingCompleted(groupManager: classic.ActorRef)

  /**
    * A metadata event indicating that a scan operation for media files and 
    * metadata on the media archive controlled by a specific processor starts.
    * During this operation, further events updating the media state are
    * generated. This event, together with the counterpart [[ScanCompleted]],
    * can be used by event listeners to keep track on ongoing scan operations
    * on archive components.
    *
    * @param processor the actor responsible for the scan operation on this
    *                  archive component
    */
  case ScanStarts(processor: classic.ActorRef)

  /**
    * A metadata event indicating that a scan operation on the media archive
    * controlled by a specific processor is completed.
    *
    * @param processor the actor responsible for the scan operation on this
    *                  archive component
    */
  case ScanCompleted(processor: classic.ActorRef)

  /**
    * A metadata event indicating that a specific medium is available in the
    * source archive and is going to be further processed. The message contains
    * information about the properties of the medium and its files. The
    * processor will produce further events for the single media files to
    * report their metadata.
    *
    * @param mediumID the ID of the medium
    * @param checksum the checksum of this medium
    * @param files    a collection with the URIs of the contained media files
    * @param rootPath the (local) root path of the archive; file URLs are 
    *                 relative to this path
    * @param archiveName the name of the archive the medium belongs to
    */
  case MediumAvailable(mediumID: MediumID,
                       checksum: Checksums.MediumChecksum,
                       files: Iterable[MediaFileUri],
                       rootPath: Path,
                       archiveName: String)

  /**
    * A metadata event indicating that the info file about a medium has been
    * parsed. This event can be used to enrich the information about media with
    * further metadata. Note: There is typically no guarantee in which order
    * [[MediumAvailable]] events and events of this type are propagated.
    *
    * @param mediumID          the ID of the medium
    * @param mediumDescription the object with metadata about the medium
    */
  case MediumDescriptionAvailable(mediumID: MediumID,
                                  mediumDescription: MediumDescription)

  /**
    * A metadata event indicating that a [[MetadataProcessingResult]] is
    * available for a specific media file. The result references the affected
    * medium, and the medium's checksum is part of the event as well. By
    * aggregating the events of this type, an event listener can gather the
    * metadata for all known media files.
    *
    * @param checksum the checksum of the owning medium
    * @param result   the processing result for this specific file
    */
  case ProcessingResultAvailable(checksum: Checksums.MediumChecksum,
                                 result: MetadataProcessingResult)
end MetadataProcessingEvent
