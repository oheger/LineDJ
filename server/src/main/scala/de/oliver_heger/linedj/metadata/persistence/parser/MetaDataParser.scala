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

package de.oliver_heger.linedj.metadata.persistence.parser

import java.nio.file.Paths

import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.persistence.parser.ParserImpl.ManyPartialData
import de.oliver_heger.linedj.metadata.persistence.parser.ParserTypes.{Failure, Success}
import de.oliver_heger.linedj.metadata.{MediaMetaData, MetaDataProcessingResult}
import org.slf4j.LoggerFactory

object MetaDataParser {
  /** The property for the song title. */
  val PropTitle = "title"

  /** The property for the artist name. */
  val PropArtist = "artist"

  /** The property for the album name. */
  val PropAlbum = "album"

  /** The property for the inception year. */
  val PropInceptionYear = "inceptionYear"

  /** The property for the track number. */
  val PropTrackNumber = "trackNumber"

  /** The property for the duration. */
  val PropDuration = "duration"

  /** The property for the format description. */
  val PropFormatDescription = "formatDescription"

  /** The property for the file size. */
  val PropSize = "size"

  /** The property for the song's URI. */
  val PropUri = "uri"

  /** The property for the song's path. */
  val PropPath = "path"

  /**
    * Constant for an unknown file size. This value is set if the file size
    * has not been stored or is invalid.
    */
  val UnknownFileSize = -1

  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[MetaDataParser])

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
  private def createProcessingResult(obj: Map[String, String], mediumID: MediumID):
  MetaDataProcessingResult =
    MetaDataProcessingResult(mediumID = mediumID, uri = obj(PropUri),
      path = Paths get obj(PropPath), metaData = createMetaData(obj))

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
      case n: NumberFormatException =>
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

  /**
    * Converts a collection with JSON objects (as maps) received from the JSON
    * parser to a collection of processing result objects.
    *
    * @param mediumID the medium ID
    * @param objects  the JSON object maps to be converted
    * @return a sequence with the converted results
    */
  private def convertJsonObjects(mediumID: MediumID, objects: IndexedSeq[Map[String, String]]):
  IndexedSeq[MetaDataProcessingResult] = {
    objects filter {
      m => m.contains(PropUri) && m.contains(PropPath)
    } map (createProcessingResult(_, mediumID))
  }

  /**
    * Iterates over the partial data from a parser run and extracts the results
    * obtained so far. The way the parser is constructed, there can only be a
    * single ''ManyPartialData'' object with the JSON objects on top level.
    * These objects are converted to ''MetaDataProcessingResult'' objects. Then
    * the ''ManyPartialData'' has to be replaced by an empty object for the
    * next run of the parser.
    *
    * @param mediumID the medium ID
    * @param d        the list with partial data
    * @return a tuple with updated partial data and the extracted results
    */
  private def extractPartialDataAndResults(mediumID: MediumID, d: List[Any]): (List[Any],
    Seq[MetaDataProcessingResult]) = {
    d.foldRight((List.empty[Any], Seq.empty[MetaDataProcessingResult])) { (x, s) =>
      x match {
        case pd: ManyPartialData[_] =>
          // Cast is safe because of the structure of the parser
          val data = pd.asInstanceOf[ManyPartialData[Map[String, String]]]
          (ParserImpl.EmptyManyPartialData :: s._1, convertJsonObjects(mediumID, data.results
            .toIndexedSeq))
        case _ =>
          (x :: s._1, s._2)
      }
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
  * [[de.oliver_heger.linedj.metadata.MediaMetaData]]. Each file defines the
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
class MetaDataParser(val chunkParser: ChunkParser[ParserTypes.Parser, ParserTypes.Result,
  Failure], val jsonParser: ParserTypes.Parser[JSONParser.JSONData]) {

  import MetaDataParser._

  /**
    * Parses a chunk of data and returns the extract metadata.
    *
    * @param text       the text of the chunk to be parsed
    * @param mediumID   the ID of the associated medium
    * @param lastChunk  a flag whether this is the last chunk
    * @param optFailure an optional ''Failure'' object to continue parsing
    * @return a tuple with the extracted information and a failure which
    *         interrupted the current parse operation
    */
  def processChunk(text: String, mediumID: MediumID, lastChunk: Boolean, optFailure:
  Option[Failure]): (Seq
    [MetaDataProcessingResult], Option[Failure]) = {
    chunkParser.runChunk(jsonParser)(text, lastChunk, optFailure) match {
      case Success(objects, _) =>
        (convertJsonObjects(mediumID, objects), None)
      case Failure(e, c, d) =>
        val (partial, results) = extractPartialDataAndResults(mediumID, d)
        (results, Some(Failure(e, c, partial)))
    }
  }
}
