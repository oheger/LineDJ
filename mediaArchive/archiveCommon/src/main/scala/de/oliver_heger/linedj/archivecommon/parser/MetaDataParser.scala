/*
 * Copyright 2015-2022 The Developers Team.
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

import de.oliver_heger.linedj.io.parser.{AbstractModelParser, ChunkParser, JSONParser, ParserTypes}
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import org.apache.logging.log4j.LogManager

object MetaDataParser {
  /** The property for the song title. */
  final val PropTitle = "title"

  /** The property for the artist name. */
  final val PropArtist = "artist"

  /** The property for the album name. */
  final val PropAlbum = "album"

  /** The property for the inception year. */
  final val PropInceptionYear = "inceptionYear"

  /** The property for the track number. */
  final val PropTrackNumber = "trackNumber"

  /** The property for the duration. */
  final val PropDuration = "duration"

  /** The property for the format description. */
  final val PropFormatDescription = "formatDescription"

  /** The property for the file size. */
  final val PropSize = "size"

  /** The property for the song's URI. */
  final val PropUri = "uri"

  /**
    * Constant for an unknown file size. This value is set if the file size
    * has not been stored or is invalid.
    */
  final val UnknownFileSize: Int = -1

  /** The logger. */
  private val log = LogManager.getLogger(classOf[MetaDataParser])

  /**
    * Converts a map representing a JSON object to the corresponding
    * ''MediaMetaData'' object.
    *
    * @param obj the map with object properties
    * @return the extracted meta data
    */
  private def createMetaData(obj: Map[String, String]): MediaMetaData = {
    MediaMetaData(artist = obj get PropArtist, album = obj get PropAlbum,
      title = obj get PropTitle, inceptionYear = intProperty(obj, PropInceptionYear),
      trackNumber = intProperty(obj, PropTrackNumber),
      duration = intProperty(obj, PropDuration),
      formatDescription = obj get PropFormatDescription, size = sizeProperty(obj))
  }

  /**
    * Converts a JSON object to a processing result.
    *
    * @param obj      the map with the object's properties
    * @param mediumID the medium ID
    * @return the processing result
    */
  private def createProcessingResult(obj: Map[String, String], mediumID: MediumID): MetaDataProcessingSuccess =
    MetaDataProcessingSuccess(mediumID = mediumID, uri = MediaFileUri(obj(PropUri)), metaData = createMetaData(obj))

  /**
    * Extracts an optional property of type Int from the given map. If the
    * value cannot be converted to Int, result is ''None''.
    *
    * @param obj  the map with object properties
    * @param prop the property to be extracted
    * @return the extracted numeric value
    */
  private def intProperty(obj: Map[String, String], prop: String): Option[Int] = {
    try obj.get(prop) map (_.toInt)
    catch {
      case _: NumberFormatException =>
        log.warn(s"Invalid value for property $prop: '${obj(prop)}'; ignoring.")
        None
    }
  }

  /**
    * Extracts the size from the given object map. The size can be invalid or
    * missing.
    *
    * @param obj the map with object properties
    * @return the extracted size
    */
  private def sizeProperty(obj: Map[String, String]): Long = {
    try obj.get(PropSize).map(_.toLong) getOrElse UnknownFileSize
    catch {
      case _: NumberFormatException =>
        log.warn(s"Invalid value for property size: '${obj(PropSize)}'.")
        UnknownFileSize
    }
  }

}

/**
  * A class for parsing files with metadata about songs.
  *
  * Rather than scanning the media directory on every start, extracted metadata
  * about songs can be stored in JSON files in a specific folder. This parser
  * reads such files.
  *
  * The data persisted in such files is derived from the data class
  * [[MediaMetaData]]. Each file defines the
  * content of a medium and contains a JSON array with an arbitrary number of
  * objects representing the single songs on that medium. The properties
  * supported for a song are named based on the properties of the
  * ''MediaMetaData'' class.
  *
  * This class uses an underlying [[ChunkParser]] for parsing metadata files
  * chunk-wise. On each chunk the so far extracted information about songs is
  * returned. Then parsing can continue with the next chunk. It is in the
  * responsibility of the calling code to read the chunks from disk and feed
  * them into the parser object.
  *
  * @param chunkParser the underlying ''ChunkParser''
  * @param jsonParser  the underlying JSON parser
  */
class MetaDataParser(chunkParser: ChunkParser[ParserTypes.Parser, ParserTypes.Result,
  Failure], jsonParser: ParserTypes.Parser[JSONParser.JSONData])
  extends AbstractModelParser[MetaDataProcessingSuccess, MediumID](chunkParser, jsonParser) {

  import MetaDataParser._

  override def convertJsonObjects(mediumID: MediumID, objects: IndexedSeq[Map[String, String]]):
  IndexedSeq[MetaDataProcessingSuccess] = {
    objects filter {
      m => m.contains(PropUri)
    } map (createProcessingResult(_, mediumID))
  }
}
