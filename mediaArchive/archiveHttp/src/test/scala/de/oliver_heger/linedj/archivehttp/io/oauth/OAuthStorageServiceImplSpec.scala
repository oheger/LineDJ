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

package de.oliver_heger.linedj.archivehttp.io.oauth

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import de.oliver_heger.linedj.archivehttp.config.OAuthStorageConfig
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.{Files, Path}
import scala.xml.SAXParseException

object OAuthStorageServiceImplSpec {
  /** The base name of the OAuth test provider. */
  private val ProviderName = "TestProvider"

  /** A test OAuth configuration. */
  private val TestConfig = OAuthConfig(authorizationEndpoint = "https://test-idp.org/auth",
    tokenEndpoint = "https://test.idp.org/token", scope = "foo bar baz",
    redirectUri = "http://my-endpoint/get_code", clientID = "my-client")
}

/**
  * Test class for ''OAuthStorageServiceImpl''.
  */
class OAuthStorageServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper with AsyncTestHelper {
  def this() = this(ActorSystem("OAuthStorageServiceImplSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    tearDownTestFile()
    super.afterEach()
  }

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
  private def copyProviderResource(name: String, extension: String): Path = {
    val resFile = resolveResourceFile(name)
    Files.copy(resFile, createPathInDirectory(ProviderName + extension))
  }

  "OAuthStorageServiceImpl" should "load an OAuth configuration" in {
    copyProviderResource("OAuthConfig.xml", OAuthStorageServiceImpl.SuffixConfigFile)


    val config = futureResult(OAuthStorageServiceImpl.loadConfig(createStorageConfig("foo")))
    config should be(TestConfig)
  }

  it should "handle loading an invalid OAuth configuration" in {
    val storageConfig = createStorageConfig("invalid")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      FileTestHelper.TestData)

    expectFailedFuture[SAXParseException](OAuthStorageServiceImpl.loadConfig(storageConfig))
  }

  it should "handle loading an OAuth configuration with missing properties" in {
    val xml = <oauth-config>
      <foo>test</foo>
      <invalid>true</invalid>
    </oauth-config>
    val storageConfig = createStorageConfig("noProperties")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      xml.toString())

    expectFailedFuture[IllegalArgumentException](OAuthStorageServiceImpl.loadConfig(storageConfig))
  }

  it should "handle whitespace in XML correctly" in {
    val xml = <oauth-config>
      <client-id>
        {TestConfig.clientID}
      </client-id>
      <authorization-endpoint>
        {TestConfig.authorizationEndpoint}
      </authorization-endpoint>
      <token-endpoint>
        {TestConfig.tokenEndpoint}
      </token-endpoint>
      <scope>
        {TestConfig.scope}
      </scope>
      <redirect-uri>
        {TestConfig.redirectUri}
      </redirect-uri>
    </oauth-config>
    val storageConfig = createStorageConfig("formatted")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      xml.toString())

    val readConfig = futureResult(OAuthStorageServiceImpl.loadConfig(storageConfig))
    readConfig should be(TestConfig)
  }

  it should "load a client secret" in {
    copyProviderResource("OAuthSecret.sec", OAuthStorageServiceImpl.SuffixSecretFile)

    val secret = futureResult(OAuthStorageServiceImpl.loadClientSecret(createStorageConfig("secure_storage")))
    secret.secret should be("verySecretClient")
  }

  it should "load a file with token information" in {
    copyProviderResource("OAuthTokens.toc", OAuthStorageServiceImpl.SuffixTokenFile)
    val TestTokens = OAuthTokenData(accessToken = "testAccessToken", refreshToken = "testRefreshToken")

    val tokenData = futureResult(OAuthStorageServiceImpl.loadTokens(createStorageConfig("secret_tokens")))
    tokenData should be(TestTokens)
  }

  it should "handle an invalid tokens file" in {
    val storageConfig = createStorageConfig("wrong")
    val tokenFile = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
    writeFileContent(tokenFile, "foo")

    expectFailedFuture[IllegalStateException](OAuthStorageServiceImpl.loadTokens(storageConfig))
  }
}
