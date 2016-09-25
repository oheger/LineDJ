/*
 * Copyright 2015-2016 The Developers Team.
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
 * ''MetaDataChunk'' message. If the ''registerAsListener'' flag is
 * '''true''', the sending actor will be notified when more meta data for
 * this medium becomes available until processing is complete.
 * @param mediumID ID of the medium in question (as returned from the media
 *                 manager actor)
 * @param registerAsListener flag whether the sending actor should be
 *                           registered as listener if meta data for this
 *                           medium is incomplete yet
 */
case class GetMetaData(mediumID: MediumID, registerAsListener: Boolean)

/**
 * A message sent as answer for a ''GetMetaData'' request.
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
