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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthTokenData, OAuthConfig as CloudOAuthConfig}
import com.github.cloudfiles.core.http.factory.HttpRequestSenderFactory
import de.oliver_heger.linedj.archive.cloud.auth.OAuthMethod
import de.oliver_heger.linedj.archive.cloud.auth.oauth.{OAuthConfig, OAuthStorageConfig, OAuthStorageService}
import de.oliver_heger.linedj.archive.cloud.{CloudArchiveConfig, DefaultCloudFileDownloaderFactory}
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.pekko.actor as classic
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future}

/**
  * Test class for [[DownloaderFactoryBean]].
  */
class DownloaderFactoryBeanSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar:
  def this() = this(classic.ActorSystem("DownloaderFactoryBeanSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "A DownloaderFactoryBean" should "create a correct downloader factory" in :
    val storagePath = Paths.get("oauth", "storage", "path")
    val executionContext = implicitly[ExecutionContext]
    val actorFactory = ActorFactory.defaultActorFactory
    val storageService = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]]
    val senderFactory = mock[HttpRequestSenderFactory]
    val appConfig = new PropertiesConfiguration
    appConfig.setProperty(DownloaderFactoryBean.PropOAuthStoragePath, storagePath.toString)
    val clientAppContext = mock[ClientApplicationContext]
    Mockito.when(clientAppContext.managementConfiguration).thenReturn(appConfig)
    val bean = DownloaderFactoryBean(
      system,
      executionContext,
      actorFactory,
      storageService,
      senderFactory,
      clientAppContext
    )

    val downloaderFactory = bean.createDownloaderFactory().asInstanceOf[DefaultCloudFileDownloaderFactory]
    downloaderFactory.requestSenderFactory should be(senderFactory)

    val archiveConfig = CloudArchiveConfig(
      archiveBaseUri = Uri("https://archive.example.com/music"),
      archiveName = "testArchive",
      fileSystemFactory = mock,
      authMethod = OAuthMethod("test-idp")
    )
    val oauthSecret = Secret("secret-oauth-config")
    val storageConfig = OAuthStorageConfig(storagePath, "test-idp", oauthSecret)
    val oauthConfig = OAuthConfig(
      authorizationEndpoint = "https://idp.example.com/authorization",
      tokenEndpoint = "https://idp.example.com/token",
      scope = "testScope",
      redirectUri = "https://redirect.example.com",
      clientId = "testClient"
    )
    val clientSecret = Secret("oauth-client-secret")
    val tokenData = OAuthTokenData("someAccessToken", "someRefreshToken")
    val expectedAuthConfig = CloudOAuthConfig(
      tokenEndpoint = oauthConfig.tokenEndpoint,
      redirectUri = oauthConfig.redirectUri,
      clientID = oauthConfig.clientId,
      clientSecret = clientSecret,
      initTokenData = tokenData
    )
    Mockito.when(storageService.loadConfig(storageConfig)).thenReturn(Future.successful(oauthConfig))
    Mockito.when(storageService.loadClientSecret(storageConfig)).thenReturn(Future.successful(clientSecret))
    Mockito.when(storageService.loadTokens(storageConfig)).thenReturn(Future.successful(tokenData))
    val credentialSetter = bean.credentialSetter()
    credentialSetter.setCredential(storageConfig.baseName, oauthSecret)

    downloaderFactory.authConfigFactory.createAuthConfig(archiveConfig) map : authConfig =>
      authConfig should be(expectedAuthConfig)
