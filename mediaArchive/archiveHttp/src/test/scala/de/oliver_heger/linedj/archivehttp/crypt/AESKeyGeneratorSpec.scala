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

package de.oliver_heger.linedj.archivehttp.crypt

import java.nio.charset.StandardCharsets

import javax.crypto.spec.SecretKeySpec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''AESKeyGenerator''.
  */
class AESKeyGeneratorSpec extends AnyFlatSpec with Matchers {
  "An AESKeyGenerator" should "produce a correct secret key spec" in {
    val Password = "0123456789abcdef"
    val generator = new AESKeyGenerator

    generator generateKey Password match {
      case spec: SecretKeySpec =>
        spec.getAlgorithm should be("AES")
        spec.getEncoded should be(Password.getBytes(StandardCharsets.UTF_8))
      case o => fail("Unexpected result: " + o)
    }
  }

  it should "truncate a password that is too long" in {
    val Password = "128_Bit_Password"

    val bytes = AESKeyGenerator.generateKeyArray(Password + "_thisIsSomeMoreData")
    bytes should have length AESKeyGenerator.KeyLength
  }

  it should "pad a short password to the desired key length" in {
    val Length = 8

    val bytes = AESKeyGenerator.generateKeyArray("foo", Length)
    bytes should have length Length
  }
}
