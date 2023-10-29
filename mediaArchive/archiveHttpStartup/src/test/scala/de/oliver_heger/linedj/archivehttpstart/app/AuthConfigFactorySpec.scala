/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{BasicAuthConfig, OAuthTokenData, OAuthConfig => CloudOAuthConfig}
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.archivehttp.config.{OAuthStorageConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.io.oauth.{OAuthConfig, OAuthStorageService}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future}

object AuthConfigFactorySpec {
  /** The user ID to access a test archive. */
  private val User = "myArchiveUser"

  /** The test password. */
  private val Password = Secret("myPassword")

  /** Name of a realm used by tests. */
  private val RealmName = "myTestReal"
}

/**
  * Test class for ''AuthConfigFactory''.
  */
class AuthConfigFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper {
  def this() = this(ActorSystem("AuthConfigFactorySpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import AuthConfigFactorySpec._

  "AuthConfigFactory" should "create a configuration for BasicAuth" in {
    val service = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]]
    val realm = BasicAuthRealm(RealmName)
    val credentials = UserCredentials(User, Password)
    val factory = new AuthConfigFactory(service)

    val authConfig = futureResult(factory.createAuthConfig(realm, credentials))
    authConfig should be(BasicAuthConfig(User, Password))
  }

  it should "create a configuration for OAuth" in {
    val IdpName = "MyIDP"
    val StoragePath = Paths.get("/my/idp/data")
    val service = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]]
    val realm = OAuthRealm(RealmName, StoragePath, IdpName)
    val credentials = UserCredentials(null, Password)
    val oauthConfig = OAuthConfig(authorizationEndpoint = "https://authorization.example.org/",
      tokenEndpoint = "https://tokens.example.org/", scope = "testing", redirectUri = "https://redirect.example.org/",
      clientID = "testOAuthClient")
    val tokenData = OAuthTokenData("someAccessToken", "someRefreshToken")
    val storageConfig = realm.createIdpConfig(Password)
    val expConfig = CloudOAuthConfig(tokenEndpoint = oauthConfig.tokenEndpoint, redirectUri = oauthConfig.redirectUri,
      clientID = oauthConfig.clientID, clientSecret = Password, initTokenData = tokenData)
    implicit val ec: ExecutionContext = system.dispatcher
    when(service.loadConfig(storageConfig)).thenReturn(Future.successful(oauthConfig))
    when(service.loadClientSecret(storageConfig)).thenReturn(Future.successful(Password))
    when(service.loadTokens(storageConfig)).thenReturn(Future.successful(tokenData))
    val factory = new AuthConfigFactory(service)

    val authConfig = futureResult(factory.createAuthConfig(realm, credentials))
    authConfig should be(expConfig)
  }
}
