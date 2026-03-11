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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AuthMethodSpec extends AnyFlatSpec, Matchers:
  "BasicAuthMethod" should "return the required credential keys" in :
    val method = BasicAuthMethod("basicRealm")

    val keys = method.credentialKeys

    keys should contain only("basicRealm.username", "basicRealm.password")

  "OAuthMethod" should "return the required credential keys" in :
    val method = OAuthMethod("oAuthRealm")

    val keys = method.credentialKeys

    keys should contain only "oAuthRealm" 
    