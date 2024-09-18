/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.io.parser.{AbstractModelParser, ChunkParser, JSONParser, JsonStreamParser, ParserTypes}
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import scala.collection.immutable.IndexedSeq

/**
  * A module for parsing the content document of an HTTP archive.
  *
  * An HTTP archive contains a JSON-based document with descriptions of the
  * media it stores. This module provides a parse function that can process 
  * this document and convert the data to a sequence of [[HttpMediumDesc]]
  * objects.
  */
object HttpMediumDescParser:
  /** Property for the path to a medium description file. */
  private val PropDescriptionPath = "mediumDescriptionPath"

  /** Property for the path to a meta data file. */
  private val PropMetaDataPath = "metaDataPath"

  /**
    * Converts a map representing a JSON object to an ''HttpMediumDesc''
    * object.
    *
    * @param m the map
    * @return the resulting ''HttpMediumDesc''
    */
  private def convertDescription(m: Map[String, String]): HttpMediumDesc =
    HttpMediumDesc(m(PropDescriptionPath), m(PropMetaDataPath))

  /**
    * An internal model class representing a medium description that can be
    * partially defined only. This is used for the parsing process. In a later
    * stage, incomplete items are filtered out.
    *
    * @param mediumDescriptionPath the optional medium description path
    * @param metaDataPath          the optional metadata path
    */
  private[impl] case class LenientHttpMediumDesc(mediumDescriptionPath: Option[String],
                                                 metaDataPath: Option[String]):
    /**
      * Tries to convert this object to an [[HttpMediumDesc]] if possible.
      * Result is ''None'' if not all properties are defined.
      *
      * @return an ''Option'' with the converted [[HttpMediumDesc]]
      */
    def convertToHttpMediumDesc(): Option[HttpMediumDesc] =
      for
        descPath <- mediumDescriptionPath
        metaPath <- metaDataPath
      yield HttpMediumDesc(descPath, metaPath)

  /** The JSON format for the internal model class. */
  private[impl] given RootJsonFormat[LenientHttpMediumDesc] = jsonFormat2(LenientHttpMediumDesc.apply)

  /**
    * Parses the given [[Source]] with JSON data to a source of
    * [[HttpMediumDesc]] objects. The parsing is lenient; items with incomplete
    * data are ignored.
    *
    * @param source the source to be processed
    * @return a source with the valid medium description objects
    */
  def parseMediumDescriptions(source: Source[ByteString, Any]): Source[HttpMediumDesc, Any] =
    JsonStreamParser.parseStream[LenientHttpMediumDesc, Any](source)
      .map(_.convertToHttpMediumDesc())
      .filter(_.isDefined)
      .map(_.get)

/**
  * A class for parsing the content document of an HTTP archive.
  *
  * An HTTP archive contains a JSON-based document with descriptions of the
  * media it stores. This parser can process this document and convert the data
  * to a sequence of [[HttpMediumDesc]] objects.
  *
  * @param chunkParser the underlying ''ChunkParser''
  * @param jsonParser  the underlying JSON parser
  */
class HttpMediumDescParser(chunkParser: ChunkParser[ParserTypes.Parser, ParserTypes.Result,
  Failure], jsonParser: ParserTypes.Parser[JSONParser.JSONData])
  extends AbstractModelParser[HttpMediumDesc, AnyRef](chunkParser, jsonParser):

  import HttpMediumDescParser._

  /**
    * @inheritdoc This implementation converts the passed in maps to objects of
    *             type [[HttpMediumDesc]]. Only objects with all properties are
    *             taken into account. The data parameter is not needed here.
    */
  override def convertJsonObjects(data: AnyRef, objects: IndexedSeq[Map[String, String]]): IndexedSeq[HttpMediumDesc] =
    objects filter { m =>
      m.contains(PropDescriptionPath) && m.contains(PropMetaDataPath)
    } map convertDescription
