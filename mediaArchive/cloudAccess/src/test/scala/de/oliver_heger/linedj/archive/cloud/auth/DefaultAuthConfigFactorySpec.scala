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

package de.oliver_heger.linedj.archive.cloud.auth

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{BasicAuthConfig, OAuthTokenData, OAuthConfig as CloudOAuthConfig}
import de.oliver_heger.linedj.archive.cloud.CloudArchiveConfig
import de.oliver_heger.linedj.archive.cloud.auth.oauth.{OAuthConfig, OAuthStorageConfig, OAuthStorageService}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.{Future, Promise}

object DefaultAuthConfigFactorySpec:
  /**
    * A config for a cloud archive that is used as a basis to create concrete
    * test configurations.
    */
  private val BaseArchiveConfig = CloudArchiveConfig(
    archiveBaseUri = "https://archive.example.com",
    archiveName = "TestCloudArchive",
    fileSystemFactory = null, // This is not needed for authentication.
    authMethod = null // This is set later.
  )

  /** The test path under which OAuth configurations are expected. */
  private val TestStoragePath = Paths.get("path", "to", "OAuth", "configs")

  /**
    * Returns a credentials resolver function that looks up credentials in the
    * provided map.
    *
    * @param credentials the map with credentials
    * @return the resolver function
    */
  private def resolverFunc(credentials: Map[String, Secret]): Credentials.ResolverFunc =
    key =>
      credentials.get(key) match
        case Some(value) => Future.successful(value)
        case None => Future.failed(new IllegalArgumentException("Unresolvable credential: " + key))

  /**
    * Returns a configuration for a cloud archive that is referencing a
    * specific [[AuthMethod]].
    *
    * @param authMethod the [[AuthMethod]]
    * @return the configuration with this method
    */
  private def createArchiveConfig(authMethod: AuthMethod): CloudArchiveConfig =
    BaseArchiveConfig.copy(authMethod = authMethod)
end DefaultAuthConfigFactorySpec

/**
  * Test class for [[DefaultAuthConfigFactory]].
  */
class DefaultAuthConfigFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar:
  def this() = this(ActorSystem("DefaultAuthConfigFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import DefaultAuthConfigFactorySpec.*

  /**
    * Creates a mock for a storage service.
    *
    * @return the mock storage service
    */
  private def createStorageServiceMock(): OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData] =
    mock

  "A DefaultAuthConfigFactory" should "construct a BasicAuth configuration" in :
    val MethodName = "my-basic-auth-realm"
    val authMethod = BasicAuthMethod(MethodName)
    val Username = Secret("Scott")
    val Password = Secret("Tiger")
    val credentials = Map(
      s"$MethodName.username" -> Username,
      s"$MethodName.password" -> Password
    )
    val resolver = resolverFunc(credentials)

    val factory = new DefaultAuthConfigFactory(createStorageServiceMock(), TestStoragePath)(resolver)
    factory.createAuthConfig(createArchiveConfig(authMethod)) map : authConfig =>
      authConfig should be(BasicAuthConfig(Username.secret, Password))

  it should "query basic auth credentials in parallel" in :
    val MethodName = "my-basic-auth-realm"
    val authMethod = BasicAuthMethod(MethodName)
    val Username = Secret("Scott")
    val Password = Secret("Tiger")
    val requestedCredentials = new LinkedBlockingQueue[String]
    val resolver: Credentials.ResolverFunc = key =>
      requestedCredentials.offer(key)
      Promise().future

    val factory = new DefaultAuthConfigFactory(createStorageServiceMock(), TestStoragePath)(resolver)
    factory.createAuthConfig(createArchiveConfig(authMethod))
    requestedCredentials.poll(3, TimeUnit.SECONDS)
    requestedCredentials.poll(3, TimeUnit.SECONDS) should not be null

  it should "construct an OAuth configuration" in :
    val MethodName = "my-test-IDP"
    val authMethod = OAuthMethod(MethodName)
    val IdpPassword = Secret("my-secret-IDP-pwd")
    val resolver = resolverFunc(Map(MethodName -> IdpPassword))
    val storageService = createStorageServiceMock()
    val oauthConfig = OAuthConfig(
      authorizationEndpoint = "https://authorization.example.org/",
      tokenEndpoint = "https://tokens.example.org/",
      scope = "testing",
      redirectUri = "https://redirect.example.org/",
      clientId = "testOAuthClient"
    )
    val TestClientSecret = Secret("someClientSecret")
    val tokenData = OAuthTokenData("someAccessToken", "someRefreshToken")
    val storageConfig = OAuthStorageConfig(TestStoragePath, MethodName, IdpPassword)
    val expConfig = CloudOAuthConfig(
      tokenEndpoint = oauthConfig.tokenEndpoint,
      redirectUri = oauthConfig.redirectUri,
      clientID = oauthConfig.clientId,
      clientSecret = TestClientSecret,
      initTokenData = tokenData
    )
    when(storageService.loadConfig(storageConfig)).thenReturn(Future.successful(oauthConfig))
    when(storageService.loadClientSecret(storageConfig)).thenReturn(Future.successful(TestClientSecret))
    when(storageService.loadTokens(storageConfig)).thenReturn(Future.successful(tokenData))

    val factory = new DefaultAuthConfigFactory(storageService, TestStoragePath)(resolver)
    factory.createAuthConfig(createArchiveConfig(authMethod)) map : authConfig =>
      authConfig should be(expConfig)
