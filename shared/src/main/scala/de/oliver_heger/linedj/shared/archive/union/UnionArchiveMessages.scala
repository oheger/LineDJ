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

package de.oliver_heger.linedj.shared.archive.union

import java.nio.file.Path

import akka.actor.ActorRef
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

/**
  * A message processed by ''MediaUnionActor'' which allows adding media
  * information to the union actor.
  *
  * Messages of this type can be sent from archive components to the union
  * actor with information about media contributed by this archive component.
  * The actor creates a union of the media information passed to it.
  *
  * @param media         a map with media information
  * @param archiveCompID the ID of responsible archive component
  * @param optCtrlActor  an option for the actor to be associated with this
  *                      archive component; if undefined, the sender is used
  */
case class AddMedia(media: Map[MediumID, MediumInfo], archiveCompID: String,
                    optCtrlActor: Option[ActorRef])

/**
  * A message processed by the meta data union actor defining media that will
  * be contributed by an archive component.
  *
  * An archive component first has to send this message to the meta data union
  * actor. So the actor knows which files are available and which meta data is
  * expected. Then for each contributed media file a
  * [[MetaDataProcessingResult]] message has to be sent. That way the meta data
  * actor is able to determine when the meta data for all managed media is
  * complete.
  *
  * @param files a map with meta data files that are part of this contribution
  */
case class MediaContribution(files: Map[MediumID, Iterable[FileData]])

/**
  * A message with the result of meta data extraction for a single media file.
  *
  * Messages of this type are sent to the meta data manager actor whenever a
  * media file has been processed. The message contains the meta data that
  * could be extracted.
  *
  * @param path the path to the media file
  * @param mediumID the ID of the medium this file belongs to
  * @param uri the URI of the file
  * @param metaData an object with the meta data that could be extracted
  */
case class MetaDataProcessingResult(path: Path, mediumID: MediumID, uri: String,
                                    metaData: MediaMetaData)


/**
  * A message processed by ''MetaDataUnionActor'' telling it that a component
  * of the media archive has been removed. This causes the actor to remove
  * all meta data associated with this archive component.
  *
  * @param archiveCompID the archive component ID
  */
case class ArchiveComponentRemoved(archiveCompID: String)

/**
  * A message sent by ''MetaDataUnionActor'' as response of an
  * [[ArchiveComponentRemoved]] message when the remove operation has been
  * processed.
  *
  * A remove operation can sometimes not be processed directly, especially
  * when a scan is in progress. If a removed message was sent to remove the
  * data from an archive component in order to replace it with new scan
  * results, the sender should wait for this confirmation before it starts
  * sending media data.
  *
  * @param archiveCompID the archive component ID
  */
case class RemovedArchiveComponentProcessed(archiveCompID: String)
