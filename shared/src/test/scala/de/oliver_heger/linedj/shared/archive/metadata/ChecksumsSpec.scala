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

package de.oliver_heger.linedj.shared.archive.metadata

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[Checksums]].
  */
class ChecksumsSpec extends AnyFlatSpec with Matchers:
  "toHexString()" should "calculate a correct hex string" in :
    val input = (1 to 16).map(_.toByte).toArray
    val expectedHexString = "0102030405060708090a0b0c0d0e0f10"

    Checksums.toHexString(input) should be(expectedHexString)

  it should "use the provided encoding to calculate the hex string" in :
    val input = (1 to 16).map(_.toByte).toArray
    val expectedHexString = "0102030405060708090A0B0C0D0E0F10"

    Checksums.toHexString(input, Checksums.ChecksumEncoding.Upper) should be(expectedHexString)
