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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.archive.cloud.auth.Credentials
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

/**
  * Test class for [[CredentialsProvider]].
  */
class CredentialsProviderSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:
  "A CredentialsProvider" should "pass credentials for a BasicAuth realm" in :
    val RealmName = "Test_Basic_Auth"
    val credentials = UserCredentials("scott", Secret("tiger"))
    val realm = BasicAuthRealm(RealmName)
    val probeCredentialsActor = testKit.createTestProbe[Credentials.CredentialData]()

    val provider = new CredentialsProvider(probeCredentialsActor.ref)
    provider.passCredentials(realm, credentials)

    val expectedCredentials = List(
      (s"$RealmName.username", credentials.userName),
      (s"$RealmName.password", credentials.password.secret)
    )
    val providedCredentials = List(
      probeCredentialsActor.expectMessageType[Credentials.CredentialData],
      probeCredentialsActor.expectMessageType[Credentials.CredentialData]
    ).map(cd => cd.key -> cd.value.secret)
    providedCredentials should contain theSameElementsAs expectedCredentials

  it should "pass credentials for an OAuth realm" in :
    val RealmName = "Test_OAuth"
    val credentials = UserCredentials("usernameDoesNotMatter", Secret("idp-secret"))
    val realm = OAuthRealm(RealmName, Paths.get("path", "does", "not", "matter"))
    val probeCredentialsActor = testKit.createTestProbe[Credentials.CredentialData]()

    val provider = new CredentialsProvider(probeCredentialsActor.ref)
    provider.passCredentials(realm, credentials)

    probeCredentialsActor.expectMessage(Credentials.CredentialData(RealmName, credentials.password))

  it should "pass the encryption key for an archive" in :
    val ArchiveName = "ArchiveWithEncryptedData"
    val cryptKey = Secret("HideTheData!")
    val probeCredentialsActor = testKit.createTestProbe[Credentials.CredentialData]()

    val provider = new CredentialsProvider(probeCredentialsActor.ref)
    provider.passEncryptionKey(ArchiveName, cryptKey)

    probeCredentialsActor.expectMessage(Credentials.CredentialData(ArchiveName, cryptKey))
