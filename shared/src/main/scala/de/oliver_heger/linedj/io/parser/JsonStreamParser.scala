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

package de.oliver_heger.linedj.io.parser

import de.oliver_heger.linedj.io.stream.StreamSizeRestrictionStage
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{KillSwitch, KillSwitches}
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, JsonFraming, Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import spray.json.*

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing functionality for parsing (potentially large) JSON
  * sources.
  *
  * The module offers a function that accepts a [[Source]] to a JSON array. It
  * makes use of JSON deserialization provided by Spray Json to convert the 
  * single array elements to model objects.
  *
  * There is also some functionality for dealing with smaller data sources,
  * e.g. files that can be read in a single shot.
  */
object JsonStreamParser:
  /**
    * The default value for the maximum length parameter passed to
    * [[JsonFraming]]. Clients can override this value if there is need.
    */
  final val DefaultMaxObjectLength = 8192

  /**
    * Parses a source with a JSON array and converts it to a source of model
    * objects.
    *
    * @param source          the source with JSON data
    * @param maxObjectLength the maximum length of a single object
    * @param reader          the [[JsonReader]] for the model objects
    * @tparam T   the type of the model objects
    * @tparam MAT the materialized type of the source
    * @return the [[Source]] for model objects
    */
  def parseStream[T, MAT](source: Source[ByteString, MAT],
                          maxObjectLength: Int = DefaultMaxObjectLength)
                         (using reader: JsonReader[T]): Source[T, MAT] =
    source.viaMat(JsonFraming.objectScanner(maxObjectLength))(Keep.left)
      .viaMat(Flow[ByteString].map(convert))(Keep.left)

  /**
    * Parses a [[Source]] that contains a single JSON object and returns a
    * tuple with the [[Future]] of the resulting object and a [[KillSwitch]] to
    * cancel the operation. This function loads the whole source into memory
    * (up to the configured maximum size), which can be interrupted using the
    * returned [[KillSwitch]]. Then it performs a JSON to object conversion.
    *
    * @param source          the source with JSON data
    * @param maxObjectLength the maximum size of bytes to process; this limits
    *                        the amount of data loaded into memory
    * @param reader          the [[JsonReader]] for the to object conversion
    * @param system          the actor system for running the stream
    * @tparam T the type of the resulting object
    * @return a tuple with the [[Future]] with the parsed object and a
    *         [[KillSwitch]] to cancel the operation
    */
  def parseObjectWithCancellation[T](source: Source[ByteString, Any],
                                     maxObjectLength: Int = DefaultMaxObjectLength)
                                    (using reader: JsonReader[T], system: ActorSystem): (Future[T], KillSwitch) =
    given ExecutionContext = system.dispatcher

    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val graph = source.via(new StreamSizeRestrictionStage(maxObjectLength))
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink)(Keep.both)
    val (ks, futBytes) = graph.run()
    (futBytes.map(convert), ks)

  /**
    * Parses a [[Source]] that contains a single JSON object and returns a
    * [[Future]] with the resulting object. This function loads the whole
    * source into memory (up to the configured maximum size) and then performs
    * a JSON to object conversion.
    *
    * @param source          the source with JSON data
    * @param maxObjectLength the maximum size of bytes to process; this limits
    *                        the amount of data loaded into memory
    * @param reader          the [[JsonReader]] for the to object conversion
    * @param system          the actor system for running the stream
    * @tparam T the type of the resulting object
    * @return a [[Future]] with the parsed object
    */
  def parseObject[T](source: Source[ByteString, Any],
                     maxObjectLength: Int = DefaultMaxObjectLength)
                    (using reader: JsonReader[T], system: ActorSystem): Future[T] =
    parseObjectWithCancellation(source, maxObjectLength)._1

  /**
    * Reads a file that contains a single JSON object and returns a tuple with
    * the [[Future]] of the resulting object and a [[KillSwitch]] to cancel the
    * operation. This is a convenience function that generates a [[Source]] for
    * the provided path and then delegates to [[parseObjectWithCancellation]].
    *
    * @param path            the path to the file to read
    * @param maxObjectLength the maximum size of bytes to process; this limits
    *                        the amount of data loaded into memory
    * @param reader          the [[JsonReader]] for the to object conversion
    * @param system          the actor system for running the stream
    * @tparam T the type of the resulting object
    * @return a tuple with the [[Future]] with the parsed object and a
    *         [[KillSwitch]] to cancel the operation
    */
  def parseFileWithCancellation[T](path: Path, maxObjectLength: Int = DefaultMaxObjectLength)
                                  (using reader: JsonReader[T], system: ActorSystem): (Future[T], KillSwitch) =
    parseObjectWithCancellation(FileIO.fromPath(path), maxObjectLength)

  /**
    * Reads a file that contains a single JSON object and returns a [[Future]]
    * with the resulting object. This is a convenience function that generates
    * a [[Source]] for the provided path and then delegates to 
    * [[parseObject]].
    *
    * @param path            the path to the file to read
    * @param maxObjectLength the maximum size of bytes to process; this limits
    *                        the amount of data loaded into memory
    * @param reader          the [[JsonReader]] for the to object conversion
    * @param system          the actor system for running the stream
    * @tparam T the type of the resulting object
    * @return a [[Future]] with the parsed object
    */
  def parseFile[T](path: Path, maxObjectLength: Int = DefaultMaxObjectLength)
                  (using reader: JsonReader[T], system: ActorSystem): Future[T] =
    parseFileWithCancellation(path, maxObjectLength)._1

  /**
    * Converts the given string with JSON data to an object using the provided
    * [[JsonReader]].
    *
    * @param json   the string with JSON data
    * @param reader the [[JsonReader]] for the object conversion
    * @tparam T the type of the resulting object
    * @return the converted object
    */
  private def convert[T](json: ByteString)(using reader: JsonReader[T]): T =
    val jsonAst = json.utf8String.parseJson
    jsonAst.convertTo(reader)
