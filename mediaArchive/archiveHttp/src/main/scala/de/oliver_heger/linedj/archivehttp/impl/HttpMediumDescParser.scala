/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.io.parser.{AbstractModelParser, ChunkParser, JSONParser, ParserTypes}
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure

import scala.collection.immutable.IndexedSeq

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
