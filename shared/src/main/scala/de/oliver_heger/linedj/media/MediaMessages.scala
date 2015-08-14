/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.media

import akka.actor.ActorRef

/**
 * A message processed by ''MediaManagerActor'' telling it to respond with a
 * list of media currently available. This message is sent by clients in
 * order to find out about the audio data available. They can then decide
 * which audio sources are requested for playback.
 */
case object GetAvailableMedia

/**
 * A message processed by ''MediaManagerActor'' telling it that a reader
 * actor which has been passed to a client is still alive. The download of a
 * media file can take very long (the user may stop playback). With this
 * message a client tells this actor that the download operation is still in
 * progress. If such messages are not received in a given time frame, the
 * affected reader actors are stopped.
 * @param reader the reader actor in question
 */
case class ReaderActorAlive(reader: ActorRef)

/**
 * A message processed by ''MediaManagerActor'' telling it to return a list
 * with the files contained on the specified medium.
 *
 * @param mediumID the ID of the medium in question
 */
case class GetMediumFiles(mediumID: String)

/**
 * A message sent by ''MediaManagerActor'' which contains information about
 * all media currently available. The media currently available are passed as
 * a map with alphanumeric media IDs as keys and the corresponding info
 * objects as values.
 *
 * @param media a map with information about all media currently available
 */
case class AvailableMedia(media: Map[String, MediumInfo])

/**
 * A message sent by ''MediaManagerActor'' in response to a request for the
 * files on a medium. This message contains a sequence with the URIs of the
 * files stored on this medium. The ''existing'' flag can be evaluated if the
 * list is empty: a value of '''true''' means that the medium exists, but
 * does not contain any files; a value of '''false''' indicates an unknown
 * medium.
 * @param mediumID the ID of the medium that was queried
 * @param uris a sequence with the URIs for the files on this medium
 * @param existing a flag whether the medium exists
 */
case class MediumFiles(mediumID: String, uris: Set[String], existing: Boolean)
