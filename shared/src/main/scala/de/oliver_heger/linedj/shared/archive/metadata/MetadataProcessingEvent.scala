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

package de.oliver_heger.linedj.shared.archive.metadata

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingResult
import org.apache.pekko.actor as classic

/**
  * A type representing event notifications sent by the
  * [[MetadataManagerActor]] during processing of media files. Listeners can
  * subscribe for such events to keep track of the status of media processing.
  */
enum MetadataProcessingEvent:
  /**
    * A metadata event indicating that an update operation on the media archive
    * controlled by a specific processor starts. During this operation, further
    * events updating the media state are generated. This event, together with
    * the counterpart [[UpdateOperationCompleted]], is used to notify event
    * listeners that there is now some activity going on and that further
    * events can be expected.
    *
    * @param processor the actor responsible for the update operation
    */
  case UpdateOperationStarts(processor: classic.ActorRef)

  /**
    * A metadata event indicating that an update operation on the media archive
    * controlled by a specific processor is completed.
    */
  case UpdateOperationCompleted(processor: classic.ActorRef)

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
    */
  case MediumAvailable(mediumID: MediumID,
                       checksum: MediumChecksum,
                       files: Iterable[MediaFileUri])

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
  case ProcessingResultAvailable(checksum: MediumChecksum,
                                 result: MetadataProcessingResult)
end MetadataProcessingEvent
