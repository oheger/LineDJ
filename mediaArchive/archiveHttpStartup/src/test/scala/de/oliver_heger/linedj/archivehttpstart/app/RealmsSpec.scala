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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

/**
  * Test class for the different implementations of archive realms.
  */
class RealmsSpec extends AnyFlatSpec with Matchers:
  "BasicAuthRealm" should "return the correct user ID flag" in:
    val realm = BasicAuthRealm("foo")

    realm.needsUserID shouldBe true

  "OAuthRealm" should "return the correct user ID flag" in:
    val realm = OAuthRealm("foo", Paths.get("/data"), "my-IDP")

    realm.needsUserID shouldBe false

  it should "create a correct IDP config" in:
    val IdpDir = Paths.get("idp-data")
    val IdpName = "MyIdp"
    val Password = "IdpAccess"
    val realm = OAuthRealm("oauth", IdpDir, IdpName)

    val config = realm.createIdpConfig(Secret(Password))
    config.rootDir should be(IdpDir)
    config.baseName should be(IdpName)
    config.encPassword.secret should be(Password)
