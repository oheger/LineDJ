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

package de.oliver_heger.linedj.archivecommon.parser

import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import spray.json.*
import spray.json.DefaultJsonProtocol.*

/**
  * A module for parsing files with metadata about songs.
  *
  * Rather than scanning the media directory on every start, extracted metadata
  * about songs can be stored in JSON files in a specific folder. This parser
  * reads such files.
  *
  * The data persisted in such files is derived from the data class
  * [[MediaMetadata]]. Each file defines the
  * content of a medium and contains a JSON array with an arbitrary number of
  * objects representing the single songs on that medium. The properties
  * supported for a song are named based on the properties of the
  * ''MediaMetaData'' class.
  *
  * This class uses an underlying [[JsonStreamParser]] for parsing metadata
  * files. It produces a [[Source]] returning the metadata objects extracted
  * from the file.
  */
object MetadataParser:
  /** The property for the song's URI. */
  private val PropUri = "uri"

  /**
    * A model class to combine metadata of a song with the URI of the
    * corresponding song file.
    *
    * @param uri      the URI of the song file
    * @param metadata the metadata
    */
  case class MetadataWithUri(uri: String,
                             metadata: MediaMetadata)

  /**
    * An internally used model class for metadata that uses optional fields for
    * all properties. This allows for a lenient deserialization mode; for
    * missing properties, default values are set.
    *
    * @param title             the title
    * @param artist            the artist
    * @param album             the album
    * @param inceptionYear     the inception year
    * @param trackNumber       the track number
    * @param duration          the duration
    * @param formatDescription the format description
    * @param size              the file size
    * @param checksum          the checksum
    */
  private case class RelaxedMediaMetadata(title: Option[String] = None,
                                          artist: Option[String] = None,
                                          album: Option[String] = None,
                                          inceptionYear: Option[Int] = None,
                                          trackNumber: Option[Int] = None,
                                          duration: Option[Int] = None,
                                          formatDescription: Option[String] = None,
                                          size: Option[Long],
                                          checksum: Option[String]):
    /**
      * Convert this object to a [[MediaMetadata]] instance setting default
      * values for undefined properties.
      *
      * @return the resulting [[MediaMetadata]] instance
      */
    def toMetadata: MediaMetadata =
      MediaMetadata(
        title = title,
        artist = artist,
        album = album,
        inceptionYear = inceptionYear,
        trackNumber = trackNumber,
        duration = duration,
        formatDescription = formatDescription,
        size = size.getOrElse(MediaMetadata.UndefinedMediaData.size),
        checksum = checksum.getOrElse(MediaMetadata.UndefinedMediaData.checksum)
      )

  /**
    * A JSON format object for processing metadata objects with potentially
    * undefined properties. This is used for reading metadata in a lenient way.
    */
  private val relaxedMediaMetadataFormat = jsonFormat9(RelaxedMediaMetadata.apply)

  /**
    * A JSON format object for processing metadata objects. This is used to
    * parse the metadata in the custom format for [[MetadataWithUri]].
    */
  private val mediaMetadataFormat = jsonFormat9(MediaMetadata.apply)

  /**
    * A JSON format object for handling [[MetadataWithUri]] objects. Here, the
    * serialization format deviates from the standard, because the URI is
    * included in the JSON object with the metadata.
    */
  given RootJsonFormat[MetadataWithUri] = new RootJsonFormat[MetadataWithUri]:
    override def read(json: JsValue): MetadataWithUri =
      val relaxedMetadata = relaxedMediaMetadataFormat.read(json)
      val uri = json.asJsObject.getFields(PropUri) match
        case Seq(JsString(value)) => value
        case _ => deserializationError(s"Missing '$PropUri' property in metadata.")
      MetadataWithUri(uri, relaxedMetadata.toMetadata)

    override def write(obj: MetadataWithUri): JsValue =
      val metadataObj = mediaMetadataFormat.write(obj.metadata).asJsObject
      val fields = metadataObj.fields + (PropUri -> JsString(obj.uri))
      metadataObj.copy(fields = fields)

  /**
    * Returns a [[Source]] that can be used to extract
    * [[MetadataProcessingSuccess]] objects from the given data source that
    * returns JSON.
    *
    * @param source   the [[Source]] for the JSON input
    * @param mediumID the [[MediumID]] for the medium the data belongs to
    * @return a [[Source]] for extracting metadata objects
    */
  def parseMetadata(source: Source[ByteString, Any], mediumID: MediumID): Source[MetadataProcessingSuccess, Any] =
    JsonStreamParser.parseStream[MetadataWithUri, Any](source).map { mwu =>
      MetadataProcessingSuccess(mediumID, MediaFileUri(mwu.uri), mwu.metadata)
    }
