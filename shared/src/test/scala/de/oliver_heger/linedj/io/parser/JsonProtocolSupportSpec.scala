/*
 * Copyright 2015-2026 The Developers Team.
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

import de.oliver_heger.linedj.io.parser.JsonProtocolSupportSpec.{TestResult, TestStatus}
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.*
import spray.json.DefaultJsonProtocol.*

object JsonProtocolSupportSpec extends DefaultJsonProtocol:
  /**
    * A test enumeration to be serialized to JSON.
    */
  enum TestStatus:
    case Succeeded, Failure, Unclear

  given RootJsonFormat[TestStatus] = JsonProtocolSupport.enumFormat(TestStatus.valueOf)

  /**
    * A simple data class to be serialized to JSON.
    *
    * @param status     the status of the test execution
    * @param durationMs the duration of the test
    */
  case class TestResult(status: TestStatus,
                        durationMs: Long)

  given RootJsonFormat[TestResult] = jsonFormat2(TestResult.apply)

/**
  * Test class for [[JsonProtocolSupport]].
  */
class JsonProtocolSupportSpec extends AnyFlatSpec with Matchers:
  "enumFormat" should "handle the conversion of an enum type" in :
    forEvery(TestStatus.values) { status =>
      val result = TestResult(status, 20250725121954L)
      val resultJson = result.toJson.prettyPrint
      val jsonAst = resultJson.parseJson
      val result2 = jsonAst.convertTo[TestResult]

      result2 should be(result)
    }

  it should "handle unexpected input" in :
    val invalidStatusJson =
      """
        |{
        |  "durationMs": 5000,
        |  "status": false
        |}""".stripMargin
    val jsonAst = invalidStatusJson.parseJson

    val exception = intercept[DeserializationException]:
      jsonAst.convertTo[TestResult]

    exception.getMessage should include("with an enum literal")
