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

package de.oliver_heger.linedj.archivehttp.impl

import de.oliver_heger.linedj.io.parser.{JSONParser, ParserImpl}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object HttpMediumDescParserSpec {
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
    * Creates a new parser instance.
    *
    * @return the new parser
    */
  private def createParser(): HttpMediumDescParser =
    new HttpMediumDescParser(ParserImpl, JSONParser.jsonParser(ParserImpl))
}

/**
  * Test class for ''HttpMediumDescParser''.
  */
class HttpMediumDescParserSpec extends AnyFlatSpec with Matchers {

  import HttpMediumDescParserSpec._

  "A HttpMediumDescParser" should "produce correct result objects" in {
    val descs = Vector(HttpMediumDesc("medium1/desc.settings", "metadata/med1.mdt"),
      HttpMediumDesc("medium2/playlist.settings", "metadata/med2.mdt"),
      HttpMediumDesc("medium3/desc.settings", "metadata/45848481.mdt"))

    val result = createParser().convertJsonObjects(null, descs map toMap)
    result should be(descs)
  }

  it should "filter out incomplete medium descriptions" in {
    val valid = Vector(HttpMediumDesc("medium1/desc.settings", "metadata/med1.mdt"),
      HttpMediumDesc("medium3/desc.settings", "metadata/45848481.mdt"))
    val descs = Map("mediumDescriptionPath" -> "somePath") +: valid.map(toMap)
    val moreDescs = descs :+ Map("metaDataPath" -> "anotherPath")

    val result = createParser().convertJsonObjects(null, moreDescs)
    result should be(valid)
  }
}
