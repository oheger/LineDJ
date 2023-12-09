/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.apache.pekko.actor.ActorRef

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
  * @param files a map with the URIs of meta data files that are part of this
  *              contribution
  */
case class MediaContribution(files: Map[MediumID, Iterable[MediaFileUri]])

/**
  * A message processed by the meta data union actor announcing that a meta
  * data update operation is going to start.
  *
  * Clients of the union archive should send a message of this type before they
  * collect meta data results and pass them to the archive (using messages like
  * [[AddMedia]] or [[MediaContribution]]). This ensures that the archive's
  * update in progress state can be updated correctly.
  *
  * The message contains an optional reference to the processor actor
  * responsible for the update. A value of ''None'' means that the sender of
  * this message is the processor actor.
  *
  * @param processor an option for the responsible processor actor
  */
case class UpdateOperationStarts(processor: Option[ActorRef])

/**
  * A message processed by the meta data union actor indicating that a
  * processor actor has completed updates of meta data in the archive.
  *
  * This message is the counterpart of [[UpdateOperationStarts]]. It should be
  * sent by an archive component after all meta data has been added to the
  * archive. Again, the actor responsible for the update operation can be
  * either specified explicitly or the sender of the message is used.
  *
  * @param processor an option for the responsible processor actor
  */
case class UpdateOperationCompleted(processor: Option[ActorRef])

/**
  * A trait describing the result of a meta data processing operation.
  *
  * A result consists of some meta information about the file that was subject
  * of the operation. Concrete sub classes will then contain either an actual
  * result or an error message.
  */
sealed trait MetaDataProcessingResult:
  /**
    * Returns the ID of the medium the media file belongs to
    *
    * @return the medium ID
    */
  def mediumID: MediumID

  /**
    * Returns the URI of the media file.
    *
    * @return the URI
    */
  def uri: MediaFileUri

/**
  * A message with the successful result of meta data extraction for a single
  * media file.
  *
  * Messages of this type are sent to the meta data manager actor whenever a
  * media file has been processed successfully. The message contains the meta
  * data that could be extracted.
  *
  * @param mediumID the ID of the medium this file belongs to
  * @param uri      the URI of the file
  * @param metaData an object with the meta data that could be extracted
  */
case class MetaDataProcessingSuccess(override val mediumID: MediumID,
                                     override val uri: MediaFileUri, metaData: MediaMetaData)
  extends MetaDataProcessingResult:
  /**
    * Returns a new instance of ''MetaDataProcessingResult'' with the same
    * properties as this instance, but with updated meta data.
    *
    * @param metaData the new meta data
    * @return the updated instance
    */
  def withMetaData(metaData: MediaMetaData): MetaDataProcessingSuccess =
    copy(metaData = metaData)

  /**
    * Converts this result into an error result using the specified exception
    * as cause. This is useful when during processing of an MP3 file an
    * error occurs.
    *
    * @param exception the exception causing the error
    * @return the transformed error result
    */
  def toError(exception: Throwable): MetaDataProcessingError =
    MetaDataProcessingError(uri = uri, mediumID = mediumID, exception = exception)

/**
  * A message indicating a failure during a meta data extraction operation.
  *
  * Messages of this type are produced when no meta data can be extracted from
  * a specific file. This normally means that the file is corrupt.
  *
  * @param mediumID  the ID of the medium this file belongs to
  * @param uri       the URI of the file
  * @param exception the exception which is the cause of the failure
  */
case class MetaDataProcessingError(override val mediumID: MediumID,
                                   override val uri: MediaFileUri,
                                   exception: Throwable)
  extends MetaDataProcessingResult

/**
  * A message serving as a request to process a meta data file.
  *
  * To identify the file, a number of properties are needed - a path, a URI, a
  * medium ID. This information is provided in form of a result object with
  * undefined meta data - a result template. The actor processing this message
  * can use this template to generate the final processing result.
  *
  * @param fileData       an object describing the file to be processed
  * @param resultTemplate a template for the expected result
  */
case class ProcessMetaDataFile(fileData: FileData, resultTemplate: MetaDataProcessingSuccess)

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

/**
  * A message processed by ''MediaUnionActor'' that requests information about
  * the meta data files of a specific archive.
  *
  * The message is forwarded to the controller actor of the selected archive.
  * It returns a ''MetaDataFileInfo'' message as response.
  *
  * @param archiveCompID the archive component ID
  */
case class GetArchiveMetaDataFileInfo(archiveCompID: String)
