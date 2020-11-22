/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp

import java.nio.file.{Files, Path}

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, OAuthStorageConfig}
import de.oliver_heger.linedj.archivehttp.impl.io.HttpRequestActor
import de.oliver_heger.linedj.archivehttp.impl.io.oauth._
import de.oliver_heger.linedj.crypt.Secret
import de.oliver_heger.linedj.utils.ChildActorFactory
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContextExecutor

object OAuthConfigureFuncSpec {
  /** The name of the OAuth IDP used by tests. */
  private val ProviderName = "TestOAuthIDP"

  /** The password used for encrypted files. */
  private val Password = "secure_storage"

  /** A test OAuth configuration. */
  private val TestConfig = OAuthConfig(authorizationEndpoint = "https://test-idp.org/auth",
    tokenEndpoint = "https://test.idp.org/token", scope = "foo bar baz",
    redirectUri = "http://my-endpoint/get_code", clientID = "my-client")
}

/**
  * Test class for the standard authentication configure function provided by
  * [[HttpArchiveManagementActor]] that supports OAuth 2 authentication.
  */
class OAuthConfigureFuncSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with MockitoSugar with FileTestHelper
  with AsyncTestHelper {
  def this() = this(ActorSystem("OAuthConfigureFuncSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    tearDownTestFile()
    super.afterEach()
  }

  import OAuthConfigureFuncSpec._

  /**
    * Creates a storage configuration with default settings and the given
    * password.
    *
    * @return the storage configuration
    */
  private def createStorageConfig(): OAuthStorageConfig =
    OAuthStorageConfig(testDirectory, ProviderName, Secret(Password))

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

  /**
    * Copies all provider-related data files from the resources into the
    * managed test directory.
    */
  private def copyProviderResources(): Unit = {
    copyProviderResource("OAuthConfig.xml", OAuthStorageServiceImpl.SuffixConfigFile)
    copyProviderResource("OAuthSecret.sec", OAuthStorageServiceImpl.SuffixSecretFile)
    copyProviderResource("test_tokens.toc", OAuthStorageServiceImpl.SuffixTokenFile)
  }

  "The OAuth configure function" should "correctly decorate a request actor" in {
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    copyProviderResources()
    val idpActor = TestProbe()
    val requestActor = TestProbe()
    val oauthActor = TestProbe()
    val actorFactory = mock[ChildActorFactory]
    when(actorFactory.createChildActor(any())).thenReturn(idpActor.ref, oauthActor.ref)

    val authFunc = futureResult(HttpArchiveManagementActor.oauthConfigureFunc(createStorageConfig()))
    authFunc(requestActor.ref, actorFactory) should be(oauthActor.ref)
    val captor = ArgumentCaptor.forClass(classOf[Props])
    verify(actorFactory, times(2)).createChildActor(captor.capture())
    val propsIdp = captor.getAllValues.get(0)
    propsIdp should be(HttpRequestActor(TestConfig.tokenEndpoint, HttpArchiveConfig.DefaultRequestQueueSize))
    val propsOAuth = captor.getAllValues.get(1)
    propsOAuth.actorClass() should be(classOf[OAuthTokenActor])
    propsOAuth.args.head should be(requestActor.ref)
    propsOAuth.args(1) should be(idpActor.ref)
    propsOAuth.args(2) should be(TestConfig)
    val secret = propsOAuth.args(3).asInstanceOf[Secret]
    secret.secret should be("verySecretClient")
    propsOAuth.args(4) should be(OAuthTokenData(accessToken = "testAccessToken", refreshToken = "testRefreshToken"))
    propsOAuth.args(5) should be(OAuthTokenRetrieverServiceImpl)
  }
}
