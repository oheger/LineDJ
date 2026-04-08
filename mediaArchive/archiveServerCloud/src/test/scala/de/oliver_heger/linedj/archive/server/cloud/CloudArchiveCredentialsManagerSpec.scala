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
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.concurrent.Future

object CloudArchiveCredentialsManagerSpec:
  /** The name of the encrypted test file. */
  private val CryptFileName = "test-credentials"

  /** The secret used to encrypt the test file. */
  private val CryptFileSecret = "test"

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

  /**
    * Returns a [[Source]] with the JSON representation of the provided
    * credentials.
    *
    * @param credentials the map with credentials
    * @return a [[Source]] to read these credentials from a stream
    */
  private def toCredentialSource(credentials: Map[String, String]): Source[ByteString, Any] =
    Source(ByteString(toCredentialJson(credentials)).grouped(32).toList)
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

  /**
    * Copies the encrypted test file from the resources into the test temp
    * folder.
    *
    * @return the path to the copied file
    */
  private def copyCryptTestFile(): Path =
    val fileName = CryptFileName + ".json.crypt"
    val url = getClass.getResource("/" + fileName)
    val sourcePath = Paths.get(url.toURI)
    val targetPath = createPathInDirectory(fileName)
    Files.copy(sourcePath, targetPath)

  /**
    * Queries the given credential manager for a given credential key
    * repeatedly until it is present or not, depending on the passed in flag.
    * This is needed by some test cases that check whether an incorrect
    * credential has been reset. To avoid an infinite waiting loop, the
    * function gives up after some time. This also helps dealing with race
    * conditions where a state change is missed.
    *
    * @param manager the credential manager
    * @param key     the key of the affected credential
    * @param present flag whether the key should be present or absent
    * @return a [[Future]] that completes when this credential is no longer
    *         available
    */
  private def waitForCredentialState(manager: CloudArchiveCredentialsManager,
                                     key: CloudArchiveCredentialsManager.CredentialKey,
                                     present: Boolean): Future[Done] =
    val startTime = System.nanoTime()
    def waiting(): Future[Done] =
      manager.pendingCredentials flatMap : keys =>
        if System.nanoTime() - startTime < TimeUnit.SECONDS.toNanos(1) && keys.contains(key) != present then
          waiting()
        else
          Future.successful(Done)

    waiting()

  "A CloudArchiveCredentialsManager" should "read JSON files in the credentials directory" in :
    val credentials = Map(
      "key1" -> "value1",
      "anotherKey" -> "anotherValue",
      "secretKey" -> "secretValue",
      "foo" -> "bar"
    )
    writeFileContent(createPathInDirectory("plain-credentials.json"), toCredentialJson(credentials))

    val credentialsManager = CloudArchiveCredentialsManager.newInstance(testDirectory, implicitly)
    val futResolved = credentials.map: (key, _) =>
      credentialsManager.resolverFunc(key).map(key -> _.secret)

    Future.sequence(futResolved) map : pairs =>
      pairs should contain theSameElementsAs credentials.toList

  it should "complete the initFuture when the object has been initialized" in :
    copyCryptTestFile()
    val credentialsManager = CloudArchiveCredentialsManager.newInstance(testDirectory, implicitly, "initFuture")

    credentialsManager.initFuture map : d =>
      d should be(Done)

  it should "handle an invalid credentials directory" in :
    val nonExistingPath = Paths.get("a", "non", "existing", "directory")
    val credentialsManager = CloudArchiveCredentialsManager.newInstance(nonExistingPath, implicitly, "invalidDir")

    // We can only test that an initialized credentials manager is returned.
    credentialsManager should not be null
    credentialsManager.resolverFunc should not be null

  it should "fail the initFuture if there is an initialization error" in :
    val nonExistingPath = Paths.get("a", "non", "existing", "directory")
    val credentialsManager = CloudArchiveCredentialsManager.newInstance(nonExistingPath, implicitly, "failedInit")

    recoverToExceptionIf[IOException](credentialsManager.initFuture) map : exception =>
      exception.getMessage should include(nonExistingPath.toString)

  it should "handle encrypted JSON files in the credentials directory" in :
    copyCryptTestFile()
    val credentialsManager = CloudArchiveCredentialsManager.newInstance(testDirectory, implicitly, "cryptFile")

    val credentials = Map(CryptFileName -> CryptFileSecret)
    for
      _ <- credentialsManager.initFuture
      _ <- credentialsManager.setCredentials(toCredentialSource(credentials))
      secret <- credentialsManager.resolverFunc("foo")
    yield
      secret.secret should be("bar")

  it should "support overriding a wrong secret for an encrypted credentials file" in :
    copyCryptTestFile()
    val credentialsManager = CloudArchiveCredentialsManager.newInstance(
      testDirectory,
      implicitly, 
      "cryptFileInvalidSecret"
    )

    val invalidCredentials = Map(CryptFileName -> "anIncorrectSecret")
    val credentials = Map("file://" + CryptFileName -> CryptFileSecret)
    val fileKey = CloudArchiveCredentialsManager.CredentialKey(
      CryptFileName,
      CloudArchiveCredentialsManager.CredentialKeyType.File
    )
    val futSecret = credentialsManager.resolverFunc("foo")
    for
      _ <- credentialsManager.initFuture
      _ <- credentialsManager.setCredentials(toCredentialSource(invalidCredentials))
      _ <- waitForCredentialState(credentialsManager, fileKey, present = false)
      _ <- waitForCredentialState(credentialsManager, fileKey, present = true)
      _ <- credentialsManager.setCredentials(toCredentialSource(credentials))
      secret <- futSecret
    yield
      secret.secret should be("bar")

  it should "provide information about pending credential keys" in :
    copyCryptTestFile()
    val credentialsManager = CloudArchiveCredentialsManager.newInstance(testDirectory, implicitly, "credentialKeys")
    val archiveCredentials = List("test.user", "test.password", "my-test-archive")
    archiveCredentials.foreach(credentialsManager.resolverFunc.apply)

    for
      _ <- credentialsManager.initFuture
      keys <- credentialsManager.pendingCredentials
    yield
      val fileKey = CloudArchiveCredentialsManager.CredentialKey(
        CryptFileName,
        CloudArchiveCredentialsManager.CredentialKeyType.File
      )
      val expectedKeys = fileKey :: archiveCredentials.map: k =>
        CloudArchiveCredentialsManager.CredentialKey(k, CloudArchiveCredentialsManager.CredentialKeyType.Archive)
      keys should contain theSameElementsAs expectedKeys

  it should "report the outcome of a setCredentials operation" in :
    val pendingCredentials = List("archive.user", "archive.password", "passwords-file")
    val credentialsManager = CloudArchiveCredentialsManager.newInstance(
      testDirectory,
      implicitly, 
      "setCredentialsResult"
    )
    pendingCredentials.foreach(credentialsManager.resolverFunc.apply)
    val credentialsToSet = "unknown" :: "invalid" :: "don't-know" :: pendingCredentials
    val credentials = credentialsToSet.map(key => key -> s"$key-value").toMap

    credentialsManager.setCredentials(toCredentialSource(credentials)) map : result =>
      result.totalCredentialsCount should be(credentials.size)
      result.invalidKeys should contain theSameElementsAs credentialsToSet.filterNot(pendingCredentials.contains)

  it should "not set invalid credentials" in :
    val CredentialKey = "theCred"
    val CredentialValue = "the-expected-value"
    val credentialManager = CloudArchiveCredentialsManager.newInstance(testDirectory, implicitly, "setInvalidCred")

    credentialManager.setCredentials(toCredentialSource(Map(CredentialKey -> "wrong-value")))
    val futValue = credentialManager.resolverFunc(CredentialKey)
    credentialManager.setCredentials(toCredentialSource(Map(CredentialKey -> CredentialValue)))

    futValue map : secret =>
      secret.secret should be(CredentialValue)
