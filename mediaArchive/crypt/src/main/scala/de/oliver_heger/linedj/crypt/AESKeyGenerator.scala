/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.crypt

import java.nio.charset.StandardCharsets
import java.security.Key

import javax.crypto.spec.SecretKeySpec

import scala.annotation.tailrec

object AESKeyGenerator {
  /** Constant for the AES algorithm name. */
  val Algorithm = "AES"

  /** The default length of a key array in bytes. */
  val KeyLength = 16

  /**
    * Converts the given password string to a byte array of the given length
    * applying padding as necessary.
    *
    * @param password  the password to be converted
    * @param keyLength the desired key length
    * @return the byte array generated from the password string
    */
  def generateKeyArray(password: String, keyLength: Int = KeyLength): Array[Byte] = {
    val keyData = password.getBytes(StandardCharsets.UTF_8) take keyLength
    if (keyData.length < KeyLength) {
      val paddedData = new Array[Byte](keyLength)
      padKeyData(keyData, paddedData, 0)
    } else keyData
  }

  /**
    * Creates an AES compliant ''Key'' from the given binary key
    * representation. The array is expected to have a valid length.
    *
    * @param keyBytes the binary key representation
    * @return the resulting secret key spec
    */
  def generateKeySpec(keyBytes: Array[Byte]): SecretKeySpec =
    new SecretKeySpec(keyBytes, Algorithm)

  /**
    * Generates key data of the correct length. This is done by repeating the
    * original key data until the desired target length is reached.
    *
    * @param orgData the original (too short) key data
    * @param target  the target array
    * @param idx     the current index
    * @return the array with key data of the desired length
    */
  @tailrec private def padKeyData(orgData: Array[Byte], target: Array[Byte], idx: Int): Array[Byte] =
    if (idx == target.length) target
    else {
      target(idx) = orgData(idx % orgData.length)
      padKeyData(orgData, target, idx + 1)
    }
}

/**
  * An implementation of the ''KeyGenerator'' trait that produces an
  * AES-compliant secret key from a password string.
  *
  * The generator uses a key length of 128 bits. A passed in string is
  * converted to its byte array representation. Then the array is trimmed to
  * the correct length, i.e. for a shorter array parts are duplicated, a longer
  * array is truncated.
  */
class AESKeyGenerator extends KeyGenerator {

  import AESKeyGenerator._

  /**
    * Generates a key from the passed in password.
    *
    * @param password the password
    * @return the resulting key
    */
  override def generateKey(password: String): Key =
    generateKeySpec(generateKeyArray(password))

  override protected def createKeyFromEncodedForm(keyData: Array[Byte]): Key =
    generateKeySpec(keyData)
}
