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

package de.oliver_heger.linedj.archivecommon.parser

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets

object MediumInfoParserSpec:
  /** A test medium ID. */
  private val TestMediumID = MediumID("test://TestMediumURI.tst", None)

  /** A test medium name. */
  private val MediumName = "TestMedium"

  /** A test description for the test medium. */
  private val MediumDescription = "A description for the test medium."

  /** A test order mode. */
  private val OrderMode = "TestOrder"

  /**
    * Generates a string with an XML fragment with test playlist settings.
    *
    * @return the fragment
    */
  private def createMediumSettings(): String =
    s"""
      |<configuration>
      |  <name>$MediumName</name>
      |  <description>$MediumDescription</description>
      |  <order>
      |    <mode>$OrderMode</mode>
      |  </order>
      |</configuration>
      |""".stripMargin

  /**
    * Creates the content array for the test medium settings.
    *
    * @return an array with the content of the test medium settings
    */
  private def createMediumSettingsFileContent(): Array[Byte] =
    createMediumSettings().getBytes(StandardCharsets.UTF_8)

/**
  * Test class for ''MediumInfoParser''.
  */
class MediumInfoParserSpec extends AnyFlatSpec with Matchers:

  import MediumInfoParserSpec._

  "A MediumInfoParser" should "parse a valid description file" in:
    val parser = new MediumInfoParser
    val info = parser.parseMediumInfo(createMediumSettingsFileContent(), TestMediumID).get

    info.mediumID should be(TestMediumID)
    info.name should be(MediumName)
    info.description should be(MediumDescription)
    info.orderMode should be(OrderMode)
    info.checksum should be("")

  it should "support setting a specific checksum" in:
    val Checksum = "a_special_medium_checksum"
    val parser = new MediumInfoParser

    val info = parser.parseMediumInfo(createMediumSettingsFileContent(), TestMediumID,
      Checksum).get
    info.checksum should be(Checksum)

  it should "handle exceptions when parsing XML" in:
    val Content = "<configuration><name>Invalid</name><description>test</configuration>"
    val parser = new MediumInfoParser

    parser.parseMediumInfo(FileTestHelper.toBytes(Content),
      TestMediumID).isFailure shouldBe true
