/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.FileTestHelper
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import scala.concurrent.Future

object CloudArchiveCredentialsManagerSpec:
  /**
    * Returns a JSON representation of the given map with credentials.
    *
    * @param credentials the map with credentials
    * @return the JSON representation of these credentials
    */
  private def toCredentialJson(credentials: Map[String, String]): String =
    credentials.map(toCredentialJson).mkString(start = "[", sep = ",", end = "]")

  /**
    * Returns a JSON representation of the given key/value-pair for a
    * credential.
    *
    * @param pair the key and value of a credential
    * @return the JSON representation for this pair
    */
  private def toCredentialJson(pair: (String, String)): String =
    s"""
       |{
       |  "key": "${pair._1}",
       |  "value": "${pair._2}"
       |}""".stripMargin
end CloudArchiveCredentialsManagerSpec

/**
  * Test class for [[CloudArchiveCredentialsManager]].
  */
class CloudArchiveCredentialsManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, BeforeAndAfterEach, Matchers, FileTestHelper:
  def this() = this(ActorSystem("CloudArchiveCredentialsManagerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import CloudArchiveCredentialsManagerSpec.*
  import de.oliver_heger.linedj.archive.cloud.auth.Credentials.given

  "A CloudArchiveCredentialsManager" should "read JSON files in the credentials directory" in :
    val credentials = Map(
      "key1" -> "value1",
      "anotherKey" -> "anotherValue",
      "secretKey" -> "secretValue",
      "foo" -> "bar"
    )
    writeFileContent(createPathInDirectory("plain-credentials.json"), toCredentialJson(credentials))

    val credentialsManager = CloudArchiveCredentialsManager(testDirectory, implicitly)
    val futResolved = credentials.map: (key, _) =>
      credentialsManager.resolverFunc(key).map(key -> _.secret)

    Future.sequence(futResolved) map : pairs =>
      pairs should contain theSameElementsAs credentials.toList

  it should "handle an invalid credentials directory" in :
    val nonExistingPath = Paths.get("a", "non", "existing", "directory")
    val credentialsManager = CloudArchiveCredentialsManager(nonExistingPath, implicitly, "invalidDir")

    // We can only test that an initialized credentials manager is returned.
    credentialsManager should not be null
    credentialsManager.resolverFunc should not be null
