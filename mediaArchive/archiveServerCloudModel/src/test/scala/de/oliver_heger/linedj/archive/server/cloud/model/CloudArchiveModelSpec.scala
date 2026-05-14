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

package de.oliver_heger.linedj.archive.server.cloud.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.*

/**
  * Test class for [[CloudArchiveModel]].
  */
class CloudArchiveModelSpec extends AnyFlatSpec, Matchers, CloudArchiveModel.CloudArchiveJsonSupport:
  /**
    * Implements a generic check for a JSON serialization round-trip. This
    * should verify whether correct JSON formats are in place for the type
    * under test.
    *
    * @param obj the object to be tested
    * @tparam T the type of this object
    */
  private def checkSerialization[T: JsonFormat](obj: T): Unit =
    val jsonAst = obj.toJson
    val json = jsonAst.prettyPrint

    val jsonAst2 = json.parseJson
    val obj2 = jsonAst2.convertTo[T]

    obj2 should be(obj)

  "JSON serialization" should "work for CredentialsInfo" in :
    val info = CloudArchiveModel.CredentialsInfo(
      fileCredentials = Set("file1", "anotherFile", "oneMoreFile"),
      archiveCredentials = Set("testArchiveUsername", "testArchivePassword")
    )

    checkSerialization(info)

  it should "work for SetCredentialsResponse" in :
    val info = CloudArchiveModel.CredentialsInfo(
      fileCredentials = Set("file1", "anotherFile", "oneMoreFile"),
      archiveCredentials = Set("testArchiveUsername", "testArchivePassword")
    )
    val response = CloudArchiveModel.SetCredentialsResponse(
      invalidKeys = Set("invalidKey", "not-valid-key", "BrokenKey"),
      info = info
    )

    checkSerialization(response)

  it should "work for CloudArchiveStateResponse" in :
    val response = CloudArchiveModel.CloudArchiveStateResponse(
      waitingArchives = Set("wait1", "waitOther"),
      loadedArchives = Set("activeArchive", "loadedArchive", "readyArchive"),
      failedArchives = Set(
        CloudArchiveModel.FailedArchive(
          name = "errorArc",
          failure = "Failed to load archive.",
          attempts = 2
        ),
        CloudArchiveModel.FailedArchive(
          name = "failureArc",
          failure = "Failed to access archive.",
          attempts = 8
        )
      )
    )

    checkSerialization(response)
