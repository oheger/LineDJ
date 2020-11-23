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

package de.oliver_heger.linedj.archivehttp.impl.crypt

import java.nio.charset.StandardCharsets
import java.security.{Key, SecureRandom}
import java.util.Base64

import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.crypt.CryptStage
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

/**
  * A service offering functionality related to cryptographic operations.
  *
  * The functions provided by this service are used to decrypt file names and
  * content from encrypted media archives.
  */
object CryptService {
  /** Name of the cipher used for decryption. */
  val CipherName = "AES/CTR/NoPadding"

  /** The length of the initialization vector. */
  val IvLength = 16

  /**
    * Decrypts the given name. The name is expected to be Base64 encoded. It is
    * decoded to a byte array and then decrypted using the password provided.
    *
    * @param key       the key to be used for decryption
    * @param name      the name to be decrypted
    * @param secRandom the random object
    * @return the decrypted name
    */
  def decryptName(key: Key, name: String)(implicit secRandom: SecureRandom): String = {
    val cipher = createCipher()
    val cryptBytes = Base64.getUrlDecoder.decode(name)
    initCipher(key, secRandom, cipher, cryptBytes)

    val plainBytes = cipher.doFinal(cryptBytes, IvLength, cryptBytes.length - IvLength)
    new String(plainBytes, StandardCharsets.UTF_8)
  }

  /**
    * Decrypts the given source. The source is modified, so that its content is
    * automatically decrypted using the passed in key.
    *
    * @param key          the key o be used for decryption
    * @param source       the source to be decrypted
    * @param secureRandom the random object
    * @tparam Mat the type for materialization
    * @return the decrypted source
    */
  def decryptSource[Mat](key: Key, source: Source[ByteString, Mat])(implicit secureRandom: SecureRandom):
  Source[ByteString, Mat] =
    source.via(CryptStage.decryptStage(key, secureRandom))

  /**
    * Creates a new ''Cipher'' object for a decrypt operation as performed by
    * this service.
    *
    * @return the new ''Cipher'' object
    */
  private def createCipher(): Cipher = Cipher.getInstance(CipherName)

  /**
    * Initializes the given ''Cipher'' object for decryption using the chunk of
    * data provided. This function expects that the initialization vector is
    * contained at the beginning of the given data array.
    *
    * @param key        the key for decryption
    * @param secRandom  the random object
    * @param cipher     the ''Cipher'' to be initialized
    * @param cryptBytes the data to be processed
    */
  private def initCipher(key: Key, secRandom: SecureRandom, cipher: Cipher, cryptBytes: Array[Byte]): Unit = {
    val iv = new IvParameterSpec(cryptBytes, 0, IvLength)
    cipher.init(Cipher.DECRYPT_MODE, key, iv, secRandom)
  }
}
