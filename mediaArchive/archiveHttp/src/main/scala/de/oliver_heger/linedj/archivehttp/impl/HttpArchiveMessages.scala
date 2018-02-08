/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl

import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Source}
import de.oliver_heger.linedj.archivehttp.{HttpArchiveState, HttpArchiveStateConnected}
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess

import scala.util.Try

/**
  * Data class representing a description of a medium in an HTTP archive.
  *
  * The URL identifying an HTTP archive points to a JSON file with a list of
  * the media available in this archive. Each element in this document
  * references a medium, consisting of the path to the medium description file
  * and the meta data file. Paths are relative URLs to the root URL of the
  * archive.
  *
  * This class represents one element of this description document.
  *
  * @param mediumDescriptionPath the path to the medium description file
  * @param metaDataPath          the path to the meta data file
  */
case class HttpMediumDesc(mediumDescriptionPath: String, metaDataPath: String)

/**
  * A data class that carries information about a request to the HTTP
  * archive.
  *
  * A single [[HttpMediumDesc]] object is transformed into a number of requests
  * whose responses will be processed by different actors With this class it is
  * possible to map a response to the original request and identify the
  * correct processor actor.
  *
  * @param mediumDesc the ''HttpMediumDesc'' that triggered this request
  * @param processorActor the responsible processor actor
  */
  case class RequestData(mediumDesc: HttpMediumDesc, processorActor: ActorRef)

/**
  * A message class that tells a processor actor to process the response from
  * the HTTP archive.
  *
  * The response contains the data of a file that has been downloaded from the
  * archive (either a settings file or a meta data file). The receiving actor
  * now has to process the response and produce a corresponding result object.
  *
  * @param mediumID      the ID of the medium affected
  * @param response      the response received from the archive
  * @param archiveConfig the config for the HTTP archive
  * @param seqNo         the sequence number of the current scan operation
  */
case class ProcessResponse(mediumID: MediumID, response: Try[HttpResponse],
                           archiveConfig: HttpArchiveConfig, seqNo: Int)

/**
  * A data class combining the relevant information for processing the content
  * of an HTTP archive.
  *
  * An instance of this class contains all the information required to process
  * the source of the content document of an HTTP archive and to download the
  * settings and metadata of all hosted media. The class is used by the actor
  * that handles the gathering of media data from an archive.
  *
  * Each scan operation started by the management actor has a sequence number
  * which is also part of this message. Result messages sent back to the
  * management actor must have the same sequence number. This is used to
  * identify stale messages from older scan operations; it is part of the
  * mechanism to handle cancellation of scan operations.
  *
  * @param mediaSource            the source for the content of the HTTP archive
  * @param clientFlow             a flow for requesting files from the archive
  * @param archiveConfig          the configuration for the HTTP archive
  * @param settingsProcessorActor the actor to process settings requests
  * @param metaDataProcessorActor the actor to process meta data requests
  * @param archiveActor           the management actor to send the final result to
  * @param seqNo                  the sequence number of the current scan operation
  */
case class ProcessHttpArchiveRequest(mediaSource: Source[HttpMediumDesc, Any],
                                     clientFlow: Flow[(HttpRequest, RequestData),
                                       (Try[HttpResponse], RequestData), _],
                                     archiveConfig: HttpArchiveConfig,
                                     settingsProcessorActor: ActorRef,
                                     metaDataProcessorActor: ActorRef,
                                     archiveActor: ActorRef, seqNo: Int)

/**
  * A message that indicates that processing of the content of an HTTP archive
  * is now complete.
  *
  * This message is sent to the manager actor for an HTTP archive to notify it
  * that the process operation is now done and that no further messages about
  * the content of this archive are going to be sent. The sequence number part
  * of this message identifies the scan operation this message belongs to.
  *
  * @param seqNo     the sequence number of the current scan operation
  * @param nextState the next state of the HTTP archive
  */
case class HttpArchiveProcessingComplete(seqNo: Int, nextState: HttpArchiveState =
HttpArchiveStateConnected)

/**
  * A message that indicates that processing of the response for a specific
  * file failed.
  *
  * Messages of this class are generated by processing actors if something
  * goes wrong. In this case, the affected medium can typically not be added to
  * the content of the HTTP archive. The manager actor can decide how to deal
  * with this issue.
  *
  * @param mediumID  the ID of the affected medium
  * @param fileType  a string for the affected file type (settings or meta data
  *                  file)
  * @param exception the exception that caused the failure
  */
case class ResponseProcessingError(mediumID: MediumID, fileType: String,
                                   exception: Throwable)

/**
  * A message that represents a successful result of processing a response for
  * a meta data file.
  *
  * The message contains all the meta data objects for the files on this
  * medium. Such files are then available via the HTTP archive.
  *
  * @param mediumID the ID of the medium
  * @param metaData a list with meta data objects for the songs on this medium
  * @param seqNo    the sequence number of the current scan operation
  */
case class MetaDataResponseProcessingResult(mediumID: MediumID,
                                            metaData: Iterable[MetaDataProcessingSuccess],
                                            seqNo: Int)

/**
  * A message that represents a success result of processing for a response
  * for a medium info file.
  *
  * Messages of this type are produced by the actor that processes medium
  * information files.
  *
  * @param mediumInfo the resulting ''MediumInfo'' object
  * @param seqNo      the sequence number of the current scan operation
  */
case class MediumInfoResponseProcessingResult(mediumInfo: MediumInfo, seqNo: Int)
