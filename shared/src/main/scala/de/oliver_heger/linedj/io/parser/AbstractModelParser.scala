/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.io.parser

import de.oliver_heger.linedj.io.parser.ParserImpl.ManyPartialData
import de.oliver_heger.linedj.io.parser.ParserTypes.{Failure, Success}

/**
  * An abstract base class for JSON-based parsers that convert the passed in
  * data to model objects.
  *
  * This base class implements functionality to parse input data chunk-wise
  * and to convert results into model objects of a given type. It uses an
  * underlying [[ChunkParser]]. On each chunk the so far extracted information
  * is converted to model objects and  returned. Then parsing can continue
  * with the next chunk. It is in the responsibility of the calling code to
  * read the chunks from disk and feed them into the parser object.
  *
  * A concrete subclass has to implement the conversion function from
  * map-based JSON data to the resulting model objects. This function may
  * require additional data to be passed from the outside; therefore, a data
  * object of a specific type can be provided when invoking the parser.
  *
  * @param chunkParser the underlying ''ChunkParser''
  * @param jsonParser  the underlying JSON parser
  * @tparam T the type of model objects produced by this parser
  * @tparam D type of additional data required by the parser
  */
abstract class AbstractModelParser[T, D](val chunkParser: ChunkParser[ParserTypes.Parser,
  ParserTypes.Result, Failure], val jsonParser: ParserTypes.Parser[JSONParser.JSONData]) {
  /**
    * Parses a chunk of data and returns the extract metadata.
    *
    * @param text       the text of the chunk to be parsed
    * @param mediumID   additional data for the conversion
    * @param lastChunk  a flag whether this is the last chunk
    * @param optFailure an optional ''Failure'' object to continue parsing
    * @return a tuple with the extracted information and a failure which
    *         interrupted the current parse operation
    */
  def processChunk(text: String, mediumID: D, lastChunk: Boolean, optFailure:
  Option[Failure]): (Seq[T], Option[Failure]) = {
    chunkParser.runChunk(jsonParser)(text, lastChunk, optFailure) match {
      case Success(objects, _) =>
        (convertJsonObjects(mediumID, objects), None)
      case Failure(e, c, d) =>
        val (partial, results) = extractPartialDataAndResults(mediumID, d)
        (results, Some(Failure(e, c, partial)))
    }
  }

  /**
    * The conversion function. This method is invoked whenever complete JSON
    * objects have been extracted from the input stream. The objects are
    * provided as maps (representing properties and their values of JSON
    * objects). An implementation has to create a sequence with corresponding
    * data model objects.
    *
    * @param data    additional data for the conversion
    * @param objects a sequence with extracted JSON objects as maps
    * @return a sequence with converted model objects
    */
  def convertJsonObjects(data: D, objects: IndexedSeq[Map[String, String]]): IndexedSeq[T]

  /**
    * Iterates over the partial data from a parser run and extracts the results
    * obtained so far. The way the parser is constructed, there can only be a
    * single ''ManyPartialData'' object with the JSON objects on top level.
    * These objects are converted to model objects. Then the
    * ''ManyPartialData'' has to be replaced by an empty object for the
    * next run of the parser.
    *
    * @param convData additional data for the conversion
    * @param d        the list with partial data
    * @return a tuple with updated partial data and the extracted results
    */
  private def extractPartialDataAndResults(convData: D, d: List[Any]): (List[Any], Seq[T]) = {
    d.foldRight((List.empty[Any], Seq.empty[T])) { (x, s) =>
      x match {
        case pd: ManyPartialData[_] if containsResults(pd) =>
          // Cast is safe because of the structure of the parser
          val data = pd.asInstanceOf[ManyPartialData[Map[String, String]]]
          (ParserImpl.EmptyManyPartialData :: s._1, convertJsonObjects(convData,
            data.results.toIndexedSeq))
        case _ =>
          (x :: s._1, s._2)
      }
    }
  }

  /**
    * Checks whether the given partial data object contains results that are to
    * be extracted by the parser. As there may be different kinds of
    * ''ManyPartialData'' objects during a parsing operation, the type of the
    * objects contained has to be checked.
    *
    * @param pd the partial data object
    * @return a flag whether this object contains parsing results
    */
  private def containsResults(pd: ManyPartialData[_]): Boolean =
    pd.results.headOption.exists(_.isInstanceOf[Map[_, _]])
}
