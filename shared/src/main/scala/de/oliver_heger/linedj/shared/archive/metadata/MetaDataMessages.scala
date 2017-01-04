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

package de.oliver_heger.linedj.shared.archive.metadata

import akka.actor.ActorRef
import de.oliver_heger.linedj.shared.archive.media.MediumID

/**
  * A message supported by ''MetaDataManagerActor'' that queries for the meta
  * data of a specific medium.
  *
  * The actor returns the meta data currently available in form of a
  * ''MetaDataResponse'' message. If the ''registerAsListener'' flag is
  * '''true''', the sending actor will be notified when more meta data for
  * this medium becomes available until processing is complete.
  *
  * With the ''registrationID'' parameter, clients can define a numeric ID
  * which is also part of response messages. This can be used by clients to
  * deal with multiple registrations and also to detect stale responses.
  * (There can be race conditions when clients remove a meta data listener
  * registration, but shortly before new meta data arrives which is sent to
  * the client. Using sequence numbers as registration IDs can help to detect
  * such cases.)
  *
  * @param mediumID           ID of the medium in question (as returned from
  *                           the media manager actor)
  * @param registerAsListener flag whether the sending actor should be
  *                           registered as listener if meta data for this
  *                           medium is incomplete yet
  * @param registrationID     a numeric registration ID; it is included in
  *                           response messages sent to this client
  */
case class GetMetaData(mediumID: MediumID, registerAsListener: Boolean,
                       registrationID: Int)

/**
 * A message sent as payload in a response for a ''GetMetaData'' request.
 *
 * This message contains either complete meta data of an medium (if it is
 * already available at request time) or a chunk which was recently updated.
 * The ''complete'' property defines whether the meta data has already been
 * fully fetched.
 *
 * @param mediumID the ID of the medium
 * @param data actual meta data; the map contains the URIs of media files as
 *             keys and the associated meta data as values
 * @param complete a flag whether now complete meta data is available for
 *                 this medium (even if this chunk may not contain the full
 *                 data)
 */
case class MetaDataChunk(mediumID: MediumID, data: Map[String, MediaMetaData], complete: Boolean)

/**
  * A response message send for a ''GetMetaData'' request.
  *
  * This message contains the actual meta data in form of a ''MetaDataChunk''
  * object and some meta data about the listener registration.
  *
  * @param chunk          the currently available meta data
  * @param registrationID the registration ID used by the client
  */
case class MetaDataResponse(chunk: MetaDataChunk, registrationID: Int)

/**
 * A message sent as answer for a ''GetMetaData'' request if the specified
 * medium is not known.
 *
 * This actor should be in sync with the media manager actor. So all media
 * returned from there can be queried here. If the medium ID passed with a
 * ''GetMetaData'' cannot be resolved, an instance of this message is
 * returned.
 * @param mediumID the ID of the medium in question
 */
case class UnknownMedium(mediumID: MediumID)

/**
 * Tells the meta data manager actor to remove a listener for the specified
 * medium.
 *
 * With this message a listener that has been registered via a
 * ''GetMetaData'' message can be explicitly removed. This is normally not
 * necessary because medium listeners are removed automatically when the
 * meta data for a medium is complete. However, if a client application is
 * about to terminate, it is good practice to remove listeners for media that
 * are still processed. If the specified listener is not registered for this
 * medium, the processing of this message has no effect.
 *
 * @param mediumID the ID of the medium
 * @param listener the listener to be removed
 */
case class RemoveMediumListener(mediumID: MediumID, listener: ActorRef)

/**
  * Tells the meta data manager actor to add a state listener actor.
  *
  * The listener will receive notifications of type [[MetaDataStateEvent]]
  * indicating important updates in the state of the media archive. Note that
  * on registration of the new listener a [[MetaDataStateUpdated]] event is
  * sent automatically with current statistics. Further events report the
  * beginning and end of scan operations and the progress made during scans.
  *
  * Note that only a single listener registration per actor reference is
  * allowed. If an actor is registered which is already a state listener, it is
  * not registered a 2nd time; it also receives update events only once.
  *
  * @param listener the listener actor to be registered
  */
case class AddMetaDataStateListener(listener: ActorRef)

/**
  * Tells the meta data manager actor to remove a state listener actor.
  *
  * With this message state listeners can be removed again. The listener
  * to remove is specified as payload of the message. If this actor is not
  * registered as state listener, this message has no effect.
  *
  * @param listener the state listener to be removed
  */
case class RemoveMetaDataStateListener(listener: ActorRef)

/**
  * A message processed by the meta data manager actor as a request to return
  * data about all meta data files available.
  *
  * Such files are generated to make the meta data extracted from audio files
  * persistent. For each medium a meta data file is generated.
  */
case object GetMetaDataFileInfo

/**
  * A message sent by the meta data manager actor as response to a
  * [[GetMetaDataFileInfo]] request.
  *
  * This data class contains the information about persistent meta data files.
  * During a meta data scan operation, for each medium a file with extracted
  * meta data is created (which allows fast access to this meta data). The map
  * with meta data files allows finding out for which media such a file exists.
  * The keys of the map are the IDs of the media for which a meta data file
  * exists; the values are checksum values for media on which the file names of
  * meta data files are based.
  *
  * If a medium is removed or changed, an already existing meta data file is
  * not removed automatically, but remains on disk. Such orphan files are
  * listed in the set. Here again checksum values are contained that can be
  * mapped to real file names.
  *
  * @param metaDataFiles map with meta data files assigned to a medium
  * @param unusedFiles   set with orphan meta data files
  */
case class MetaDataFileInfo(metaDataFiles: Map[MediumID, String],
                            unusedFiles: Set[String])

/**
  * A message processed by the meta data manager actor that causes persistent
  * meta data files to be removed.
  *
  * This message can be used by an admin application to clean up the storage
  * area for persistent meta data files; for instance, to remove files no
  * longer associated with an active medium. The checksum values of the files
  * to be removed have to be specified, which can be obtained from a
  * [[MetaDataFileInfo]] message.
  *
  * @param checksumSet a set with checksum values for the files to be removed
  */
case class RemovePersistentMetaData(checksumSet: Set[String])


/**
  * A message sent by the meta data manager actor as a result of an operation
  * to remove persistent meta data files.
  *
  * From the result it can be determined which files were removed successfully
  * and which files caused errors.
  *
  * @param request           the original request to remove files
  * @param successfulRemoved a set with checksum values for files that could be
  *                          successfully removed
  */
case class RemovePersistentMetaDataResult(request: RemovePersistentMetaData,
                                          successfulRemoved: Set[String])
