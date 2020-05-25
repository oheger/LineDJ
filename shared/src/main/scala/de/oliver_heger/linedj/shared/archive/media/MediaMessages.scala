/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.shared.archive.media

import akka.actor.ActorRef
import akka.util.ByteString
import de.oliver_heger.linedj.shared.RemoteSerializable

/**
  * A message processed by ''MediaManagerActor'' telling it to respond with a
  * list of media currently available. This message is sent by clients in
  * order to find out about the audio data available. They can then decide
  * which audio sources are requested for playback.
  */
case object GetAvailableMedia extends RemoteSerializable

/**
  * A message processed by ''MediaManagerActor'' telling it that a reader
  * actor which has been passed to a client is still alive. The download of a
  * media file can take very long (the user may stop playback). With this
  * message a client tells this actor that the download operation is still in
  * progress. If such messages are not received in a given time frame, the
  * affected reader actors are stopped.
  *
  * @param reader the reader actor in question
  * @param fileID the ID of the media file which is subject of the operation
  */
case class DownloadActorAlive(reader: ActorRef, fileID: MediaFileID) extends RemoteSerializable

/**
  * A message processed by ''MediaManagerActor'' telling it to return a list
  * with the files contained on the specified medium.
  *
  * @param mediumID the ID of the medium in question
  */
case class GetMediumFiles(mediumID: MediumID) extends RemoteSerializable

/**
  * A message sent by ''MediaManagerActor'' which contains information about
  * all media currently available.
  *
  * The media are represented as a list of tuples with ''MediumID'' and
  * ''MediumInfo'' objects; for faster access, the list can be converted to a
  * map. The list contains all media for which a medium description file was
  * found. If a source path contained media files which could not be assigned
  * to a medium description, a medium is created for these files as well; in
  * this case, the ''MediumID'' does not contain the path to a description
  * file. In addition, there is a synthetic medium which combines all media
  * files not associated to a medium (so it is the union of all media with
  * undefined description files). This medium (if existing) is stored under the
  * key ''MediumID.UndefinedMediumID''.
  *
  * @param mediaList a list with information about all media currently
  *                  available
  */
case class AvailableMedia(mediaList: List[(MediumID, MediumInfo)]) extends RemoteSerializable {
  /**
    * A map allowing fast access to ''MediumInfo'' objects if the medium ID is
    * known.
    */
  lazy val media: Map[MediumID, MediumInfo] = mediaList.toMap

  /**
    * Returns a set with the IDs of all known media. Use this property, rather
    * than the key set of the media map if no map-like access is needed
    * otherwise.
    *
    * @return a set with all known ''MediumID'' objects
    */
  def mediaIDs: Set[MediumID] = mediaList.map(_._1).toSet

  /**
    * Returns a collection with all the ''MediumInfo'' objects known. Use this
    * property, rather than the values of the media map if no map-like access
    * is needed otherwise.
    *
    * @return all ''MediumInfo'' objects
    */
  def mediumInfos: Iterable[MediumInfo] = mediaList.map(_._2)
}

/**
  * A message sent by ''MediaManagerActor'' in response to a request for the
  * files on a medium. This message contains a sequence with the URIs of the
  * files stored on this medium. The ''existing'' flag can be evaluated if the
  * list is empty: a value of '''true''' means that the medium exists, but
  * does not contain any files; a value of '''false''' indicates an unknown
  * medium.
  *
  * @param mediumID the ID of the medium that was queried
  * @param uris     a sequence with the URIs for the files on this medium
  * @param existing a flag whether the medium exists
  */
case class MediumFiles(mediumID: MediumID, uris: Set[String], existing: Boolean) extends RemoteSerializable

/**
  * A message processed by ''MediaManagerActor'' which requests the download of
  * a medium file.
  *
  * This message class is used to obtain information about a specific file on a
  * medium (its content plus additional meta data) from the media manager actor.
  * The desired file is uniquely identified using the medium ID and the
  * (relative) URI within this medium.
  *
  * When requesting a media file it can be specified whether media meta data
  * (namely ID3 tags) should be contained in the download or not. If the flag is
  * set to '''false''', a special reader actor is returned which filters out
  * such information. Otherwise, the media file is read directly.
  *
  * @param fileID       the ID of the file in question
  * @param withMetaData flag whether media meta data contained in the file
  *                     should be read or skipped
  */
case class MediumFileRequest(fileID: MediaFileID, withMetaData: Boolean) extends RemoteSerializable

/**
  * A message sent by ''MediaManagerActor'' as a response of a
  * [[MediumFileRequest]] message.
  *
  * Via the information stored here all required information about a media
  * file to be played by a client can be obtained. The actual audio data is
  * made available via a download actor which can be read chunk-wise. Note
  * that it is in the responsibility of the receiver of this message to stop the
  * actor when it is no longer needed. If the requested file is not available,
  * a ''None'' reference is returned for the download actor.
  *
  * @param request       the request message identifying the file in question
  * @param contentReader optional reference to an actor for download
  * @param length        the length of the file (in bytes)
  */
case class MediumFileResponse(request: MediumFileRequest, contentReader: Option[ActorRef],
                              length: Long) extends RemoteSerializable

/**
  * A request sent to an actor for downloading media data from the archive
  * requesting a chunk of data.
  *
  * Messages of this type have to be sent to the reader actors received via a
  * [[MediumFileResponse]] message. Each message requests a block of data of
  * the specified size.
  *
  * @param size the size of data to be returned
  */
case class DownloadData(size: Int) extends RemoteSerializable

/**
  * A message representing the result of a [[DownloadData]] message.
  *
  * The message contains a ''ByteString'' with data. The size of this string is
  * guaranteed to be not bigger than the requested size. It may, however, be
  * smaller, if the amount of data available is less than the requested block
  * size.
  *
  * @param data the data that has been requested
  */
case class DownloadDataResult(data: ByteString) extends RemoteSerializable

/**
  * A message indicating that a download is complete.
  *
  * This message is sent as response of a [[DownloadData]] request if no more
  * data is available.
  */
case object DownloadComplete extends RemoteSerializable

/**
  * A message processed by ''MediaManagerActor'' telling it to scan all
  * configured root paths for media files. The paths are obtained from the
  * configuration passed to this actor as construction time.
  */
case object ScanAllMedia extends RemoteSerializable


/**
  * A message processed by ''MediaManagerActor'' that actually triggers a
  * media scan.
  *
  * This message is sent by the group manager actor when a media scan can be
  * executed.
  */
case object StartMediaScan extends RemoteSerializable

/**
  * A message sent by ''MediaManagerActor'' as a notification when a media
  * scan is complete.
  */
case object MediaScanCompleted extends RemoteSerializable
