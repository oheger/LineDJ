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

import de.oliver_heger.linedj.shared.RemoteSerializable
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import org.apache.pekko.actor.ActorRef

/**
  * A message supported by ''MetaDataManagerActor'' that queries for the
  * metadata of a specific medium.
  *
  * The actor returns the metadata currently available in form of a
  * ''MetaDataResponse'' message. If the ''registerAsListener'' flag is
  * '''true''', the sending actor will be notified when more metadata for
  * this medium becomes available until processing is complete.
  *
  * With the ''registrationID'' parameter, clients can define a numeric ID
  * which is also part of response messages. This can be used by clients to
  * deal with multiple registrations and also to detect stale responses.
  * (There can be race conditions when clients remove a metadata listener
  * registration, but shortly before new metadata arrives which is sent to
  * the client. Using sequence numbers as registration IDs can help to detect
  * such cases.)
  *
  * @param mediumID           ID of the medium in question (as returned from
  *                           the media manager actor)
  * @param registerAsListener flag whether the sending actor should be
  *                           registered as listener if metadata for this
  *                           medium is incomplete yet
  * @param registrationID     a numeric registration ID; it is included in
  *                           response messages sent to this client
  */
case class GetMetadata(mediumID: MediumID, registerAsListener: Boolean,
                       registrationID: Int) extends RemoteSerializable

/**
  * A message sent as payload in a response for a ''GetMetaData'' request.
  *
  * This message contains either complete metadata of a medium (if it is
  * already available at request time) or a chunk which was recently updated.
  * The ''complete'' property defines whether the metadata has already been
  * fully fetched.
  *
  * @param mediumID the ID of the medium
  * @param data     actual metadata; the map contains the URIs of media files as
  *                 keys and the associated metadata as values
  * @param complete a flag whether now complete metadata is available for
  *                 this medium (even if this chunk may not contain the full
  *                 data)
  */
case class MetadataChunk(mediumID: MediumID, data: Map[String, MediaMetadata], complete: Boolean)
  extends RemoteSerializable

/**
  * A response message send for a ''GetMetaData'' request.
  *
  * This message contains the actual metadata in form of a ''MetaDataChunk''
  * object and some metadata about the listener registration.
  *
  * @param chunk          the currently available metadata
  * @param registrationID the registration ID used by the client
  */
case class MetadataResponse(chunk: MetadataChunk, registrationID: Int) extends RemoteSerializable

/**
  * A message sent as answer for a ''GetMetaData'' request if the specified
  * medium is not known.
  *
  * This actor should be in sync with the media manager actor. So all media
  * returned from there can be queried here. If the medium ID passed with a
  * ''GetMetaData'' cannot be resolved, an instance of this message is
  * returned.
  *
  * @param mediumID the ID of the medium in question
  */
case class UnknownMedium(mediumID: MediumID) extends RemoteSerializable

/**
  * Tells the metadata manager actor to remove a listener for the specified
  * medium.
  *
  * With this message a listener that has been registered via a
  * ''GetMetaData'' message can be explicitly removed. This is normally not
  * necessary because medium listeners are removed automatically when the
  * metadata for a medium is complete. However, if a client application is
  * about to terminate, it is good practice to remove listeners for media that
  * are still processed. If the specified listener is not registered for this
  * medium, the processing of this message has no effect.
  *
  * @param mediumID the ID of the medium
  * @param listener the listener to be removed
  */
case class RemoveMediumListener(mediumID: MediumID, listener: ActorRef) extends RemoteSerializable

/**
  * Tells the metadata manager actor to add a state listener actor.
  *
  * The listener will receive notifications of type [[MetadataStateEvent]]
  * indicating important updates in the state of the media archive. Note that
  * on registration of the new listener a [[MetadataStateUpdated]] event is
  * sent automatically with current statistics. Further events report the
  * beginning and end of scan operations and the progress made during scans.
  *
  * Note that only a single listener registration per actor reference is
  * allowed. If an actor is registered which is already a state listener, it is
  * not registered a 2nd time; it also receives update events only once.
  *
  * @param listener the listener actor to be registered
  */
case class AddMetadataStateListener(listener: ActorRef) extends RemoteSerializable

/**
  * Tells the metadata manager actor to remove a state listener actor.
  *
  * With this message state listeners can be removed again. The listener
  * to remove is specified as payload of the message. If this actor is not
  * registered as state listener, this message has no effect.
  *
  * @param listener the state listener to be removed
  */
case class RemoveMetadataStateListener(listener: ActorRef) extends RemoteSerializable

/**
  * A message processed by the metadata manager actor as a request to return
  * data about all metadata files available.
  *
  * Such files are generated to make the metadata extracted from audio files
  * persistent. For each medium a metadata file is generated.
  */
case object GetMetadataFileInfo extends RemoteSerializable

/**
  * A message sent by the metadata manager actor as response to a
  * [[GetMetadataFileInfo]] request.
  *
  * This data class contains the information about persistent metadata files.
  * During a metadata scan operation, for each medium a file with extracted
  * metadata is created (which allows fast access to this metadata). The map
  * with metadata files allows finding out for which media such a file exists.
  * The keys of the map are the IDs of the media for which a metadata file
  * exists; the values are checksum values for media on which the file names of
  * metadata files are based.
  *
  * If a medium is removed or changed, an already existing metadata file is
  * not removed automatically, but remains on disk. Such orphan files are
  * listed in the set. Here again checksum values are contained that can be
  * mapped to real file names.
  *
  * If an archive component supports updates of metadata files, the message
  * contains an actor reference that can be used to trigger such updates, e.g.
  * the removal of files.
  *
  * @param metadataFiles map with metadata files assigned to a medium
  * @param unusedFiles   set with orphan metadata files
  */
case class MetadataFileInfo(metadataFiles: Map[MediumID, String],
                            unusedFiles: Set[String],
                            optUpdateActor: Option[ActorRef]) extends RemoteSerializable

/**
  * A message processed by the metadata manager actor that causes persistent
  * metadata files to be removed.
  *
  * This message can be used by an admin application to clean up the storage
  * area for persistent metadata files; for instance, to remove files no
  * longer associated with an active medium. The checksum values of the files
  * to be removed have to be specified, which can be obtained from a
  * [[MetadataFileInfo]] message.
  *
  * @param checksumSet a set with checksum values for the files to be removed
  */
case class RemovePersistentMetadata(checksumSet: Set[String]) extends RemoteSerializable


/**
  * A message sent by the metadata manager actor as a result of an operation
  * to remove persistent metadata files.
  *
  * From the result it can be determined which files were removed successfully
  * and which files caused errors.
  *
  * @param request           the original request to remove files
  * @param successfulRemoved a set with checksum values for files that could be
  *                          successfully removed
  */
case class RemovePersistentMetadataResult(request: RemovePersistentMetadata,
                                          successfulRemoved: Set[String]) extends RemoteSerializable

/**
  * A message processed by the metadata manager actor that requests metadata
  * for a set of media files.
  *
  * This message has a similar purpose as [[GetMetadata]]. However, it does not
  * query metadata for a specific medium, but an arbitrary set of media files.
  * This is useful for clients that have to deal with audio files from
  * different media.
  *
  * @param files the files for which metadata is to be retrieved
  * @param seqNo a sequence number to detect outdated responses
  */
case class GetFilesMetadata(files: Iterable[MediaFileID], seqNo: Int = 0) extends RemoteSerializable

/**
  * A message sent as response for a [[GetFilesMetadata]] request.
  *
  * This message contains the metadata for the files that have been requested.
  * If files could not be resolved, they are not contained in the list with
  * metadata. By comparing the files in the request with the keys in the list,
  * it can be determined which files could not be resolved.
  *
  * @param request a reference to the request which caused this response
  * @param data    a list with metadata for the requested files
  */
case class FilesMetadataResponse(request: GetFilesMetadata,
                                 data: List[(MediaFileID, MediaMetadata)]) extends RemoteSerializable

/**
  * A message processed by the metadata manager actor that requests statistics
  * for a select archive component.
  *
  * @param archiveComponentID the ID of the archive component
  */
case class GetArchiveComponentStatistics(archiveComponentID: String) extends RemoteSerializable

/**
  * A message sent as response of a [[GetArchiveComponentStatistics]] request.
  *
  * From an instance statistical information about a specific archive component
  * can be obtained. If the requested archive component could not be resolved,
  * the fields have negative values.
  *
  * @param archiveComponentID the ID of the archive component
  * @param mediaCount         the number of media in this component
  * @param songCount          the number of songs in this component
  * @param size               the size of audio files (in bytes)
  * @param duration           the duration of audio files (in milliseconds)
  */
case class ArchiveComponentStatistics(archiveComponentID: String,
                                      mediaCount: Int,
                                      songCount: Int,
                                      size: Long,
                                      duration: Long) extends RemoteSerializable:
  /**
    * Returns a flag whether this is a valid statistics instance. This function
    * can be used to check whether the archive component could be resolved.
    * Requests for non-existing archive components are answered with instances
    * for which ''isValid'' yields '''false'''.
    *
    * @return a flag whether this is a valid instance
    */
  def isValid: Boolean = mediaCount >= 0 && songCount >= 0 && size >= 0 && duration >= 0
