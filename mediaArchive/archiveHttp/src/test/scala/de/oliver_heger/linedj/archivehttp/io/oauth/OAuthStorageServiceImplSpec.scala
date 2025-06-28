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

package de.oliver_heger.linedj.archivehttp.io.oauth

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import de.oliver_heger.linedj.archivehttp.config.OAuthStorageConfig
import org.apache.commons.configuration.ConfigurationException
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.{AnyFlatSpecLike, AsyncFlatSpecLike}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import spray.json.{DeserializationException, JsonParser}

import java.nio.file.{Files, Path}

object OAuthStorageServiceImplSpec:
  /** The base name of the OAuth test provider. */
  private val ProviderName = "TestProvider"

  /** A test OAuth configuration. */
  private val TestConfig = OAuthConfig(
    authorizationEndpoint = "https://test-idp.example.com/auth",
    tokenEndpoint = "https://test-idp.example.com/token",
    scope = "foo bar baz",
    redirectUri = "https://my-endpoint.example.com/get_code", 
    clientId = "my-client"
  )

/**
  * Test class for ''OAuthStorageServiceImpl''.
  */
class OAuthStorageServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper:
  def this() = this(ActorSystem("OAuthStorageServiceImplSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import OAuthStorageServiceImplSpec._
  import system.dispatcher

  /**
    * Creates a storage configuration with default settings and the given
    * password.
    *
    * @param password the password for encryption
    * @return the storage configuration
    */
  private def createStorageConfig(password: String): OAuthStorageConfig =
    OAuthStorageConfig(testDirectory, ProviderName, Secret(password))

  /**
    * Copies the given provider-related data file from the resources into the
    * managed test directory.
    *
    * @param name      the name of the resource file
    * @param extension the file extension to be used
    * @return the path to the copied file
    */
  private def copyProviderResource(name: String, extension: String): Path =
    val resFile = resolveResourceFile(name)
    Files.copy(resFile, createPathInDirectory(ProviderName + extension))

  "OAuthStorageServiceImpl" should "load an OAuth configuration" in:
    copyProviderResource("OAuthConfig.json", OAuthStorageServiceImpl.SuffixConfigFile)


    OAuthStorageServiceImpl.loadConfig(createStorageConfig("foo")) map { config =>
      config should be(TestConfig)
    }

  it should "handle loading an invalid OAuth configuration" in:
    val storageConfig = createStorageConfig("invalid")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      FileTestHelper.TestData)

    recoverToSucceededIf[JsonParser.ParsingException] {
      OAuthStorageServiceImpl.loadConfig(storageConfig)
    }

  it should "handle loading an OAuth configuration with missing properties" in:
    val xml =
      """
        |{
        |  "client-id": "my-client",
        |  "scope": "foo bar baz",
        |  "redirect-uri": "https://my-endpoint.example.com/get_code"
        |}
        |""".stripMargin
    val storageConfig = createStorageConfig("noProperties")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile), xml)

    recoverToSucceededIf[DeserializationException] {
      OAuthStorageServiceImpl.loadConfig(storageConfig)
    }
  
  it should "load a client secret" in:
    copyProviderResource("OAuthSecret.sec", OAuthStorageServiceImpl.SuffixSecretFile)

    OAuthStorageServiceImpl.loadClientSecret(createStorageConfig("secure_storage")) map { secret =>
      secret.secret should be("verySecretClient")
    }

  it should "load a file with token information" in:
    copyProviderResource("OAuthTokens.toc", OAuthStorageServiceImpl.SuffixTokenFile)
    val TestTokens = OAuthTokenData(accessToken = "testAccessToken", refreshToken = "testRefreshToken")

    OAuthStorageServiceImpl.loadTokens(createStorageConfig("secret_tokens")) map { tokenData =>
      tokenData should be(TestTokens)
    }

  it should "handle an invalid tokens file" in:
    val storageConfig = createStorageConfig("wrong")
    val tokenFile = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
    writeFileContent(tokenFile, "foo")

    recoverToSucceededIf[IllegalStateException] {
      OAuthStorageServiceImpl.loadTokens(storageConfig)
    }
