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

package de.oliver_heger.linedj.archivehttp.impl

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import scala.concurrent.Future

object HttpMediumDescParserSpec:
  /**
    * Converts a medium description object to a map.
    *
    * @param desc the description object
    * @return the map with the properties of this object
    */
  private def toMap(desc: HttpMediumDesc): Map[String, String] =
    Map("mediumDescriptionPath" -> desc.mediumDescriptionPath,
      "metaDataPath" -> desc.metaDataPath)

/**
  * Test class for ''HttpMediumDescParser''.
  */
class HttpMediumDescParserSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("HttpMediumDescParserSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import HttpMediumDescParser.*

  /**
    * Run a stream using the given input data through the parser and return its
    * result.
    *
    * @param inputData the data to generate the input JSON
    * @return a [[Future]] with the result of the stream
    */
  private def runParserStream(inputData: Iterable[LenientHttpMediumDesc]): Future[List[HttpMediumDesc]] =
    val jsonInput = inputData.toJson.prettyPrint
    val source = Source(ByteString(jsonInput).grouped(32).toList)
    val sink = Sink.fold[List[HttpMediumDesc], HttpMediumDesc](List.empty) { (lst, obj) =>
      obj :: lst
    }
    HttpMediumDescParser.parseMediumDescriptions(source).runWith(sink) map (_.reverse)

  "A HttpMediumDescParser" should "produce correct result objects" in :
    val inputData = Vector(
      LenientHttpMediumDesc(Some("medium1/desc.settings"), Some("metadata/med1.mdt")),
      LenientHttpMediumDesc(Some("medium2/playlist.settings"), Some("metadata/med2.mdt")),
      LenientHttpMediumDesc(Some("medium3/desc.settings"), Some("metadata/45848481.mdt"))
    )
    val expectedResult = Vector(
      HttpMediumDesc("medium1/desc.settings", "metadata/med1.mdt"),
      HttpMediumDesc("medium2/playlist.settings", "metadata/med2.mdt"),
      HttpMediumDesc("medium3/desc.settings", "metadata/45848481.mdt")
    )

    runParserStream(inputData) map { result =>
      result should contain theSameElementsInOrderAs expectedResult
    }

  it should "filter out incomplete medium descriptions" in :
    val inputData = Vector(
      LenientHttpMediumDesc(Some("someDescription"), None),
      LenientHttpMediumDesc(Some("medium1/desc.settings"), Some("metadata/med1.mdt")),
      LenientHttpMediumDesc(None, Some("somePath")),
      LenientHttpMediumDesc(Some("medium3/desc.settings"), Some("metadata/45848481.mdt"))
    )
    val expectedResult = Vector(
      HttpMediumDesc("medium1/desc.settings", "metadata/med1.mdt"),
      HttpMediumDesc("medium3/desc.settings", "metadata/45848481.mdt")
    )

    runParserStream(inputData) map { result =>
      result should contain theSameElementsInOrderAs expectedResult
    }
