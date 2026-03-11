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
import de.oliver_heger.linedj.archive.cloud.auth.Credentials
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Test class for [[CredentialsProvider]].
  */
class CredentialsProviderSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A CredentialsProvider" should "pass credentials for a BasicAuth realm" in :
    val RealmName = "Test_Basic_Auth"
    val credentials = UserCredentials("scott", Secret("tiger"))
    val realm = BasicAuthRealm(RealmName)
    var passedCredentials = List.empty[(String, String)]
    val credentialSetter = mock[Credentials.CredentialSetter]
    when(credentialSetter.setCredential(anyString(), any())).thenAnswer(
      (invocation: InvocationOnMock) =>
        val key = invocation.getArgument[String](0)
        val secret = invocation.getArgument[Secret](1)
        passedCredentials = (key, secret.secret) :: passedCredentials
        Future.successful(true)
    )

    val provider = new CredentialsProvider(credentialSetter)
    provider.passCredentials(realm, credentials)

    val expectedCredentials = List(
      (s"$RealmName.username", credentials.userName),
      (s"$RealmName.password", credentials.password.secret)
    )
    passedCredentials should contain theSameElementsAs expectedCredentials

  it should "pass credentials for an OAuth realm" in :
    val RealmName = "Test_OAuth"
    val credentials = UserCredentials("usernameDoesNotMatter", Secret("idp-secret"))
    val realm = OAuthRealm(RealmName)
    val credentialSetter = mock[Credentials.CredentialSetter]

    val provider = new CredentialsProvider(credentialSetter)
    provider.passCredentials(realm, credentials)

    verify(credentialSetter).setCredential(RealmName, credentials.password)

  it should "pass the encryption key for an archive" in :
    val ArchiveName = "ArchiveWithEncryptedData"
    val cryptKey = Secret("HideTheData!")
    val credentialSetter = mock[Credentials.CredentialSetter]

    val provider = new CredentialsProvider(credentialSetter)
    provider.passEncryptionKey(ArchiveName, cryptKey)

    verify(credentialSetter).setCredential(ArchiveName, cryptKey)
