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

package de.oliver_heger.linedj.archive.metadata

import akka.actor.ActorRef
import de.oliver_heger.linedj.archive.media.MediumID

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
 * A message sent to registered completion listeners notifying them that the
 * full meta data for a medium is now available.
 *
 * Clients interested in the whole meta data library can register co-called
 * completion listeners. They are then notified whenever all meta data for a
 * medium has been extracted. After receiving this message, the meta data can
 * be requested in a second step. This is more efficient than receiving
 * chunks for updates.
 *
 * Typically, during a single meta data extraction operation a message of
 * this type is produced once for each medium discovered. The only exception
 * is the undefined medium which can appear on multiple source directory
 * structures. In this case, multiple messages may be produced.
 *
 * @param mediumID the ID of the medium that has been completed
 */
case class MediumMetaDataCompleted(mediumID: MediumID)

/**
 * Tells the meta data manager actor to add a completion listener.
 *
 * Each time all meta data for a specific medium has been extracted, the
 * specified listener actor receives a [[MediumMetaDataCompleted]] message.
 *
 * @param listener the listener actor to be registered
 */
case class AddCompletionListener(listener: ActorRef)

/**
 * Tells the meta data manager actor to remove a completion listener.
 *
 * With this message completion listeners can be removed again. The listener
 * to remove is specified as payload of the message. If this actor is not
 * registered as completion listener, this message has no effect.
 *
 * @param listener the completion listener to be removed
 */
case class RemoveCompletionListener(listener: ActorRef)
