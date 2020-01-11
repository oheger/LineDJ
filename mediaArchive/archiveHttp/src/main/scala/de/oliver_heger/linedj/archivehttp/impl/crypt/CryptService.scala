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

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
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
    source.via(new CryptStage(key, secureRandom))

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

  /**
    * An internally used graph stage that decrypts the data passed through it.
    *
    * @param key    the key to be used for decryption
    * @param random the secure random object
    */
  private class CryptStage(key: Key, random: SecureRandom) extends GraphStage[FlowShape[ByteString, ByteString]] {
    /**
      * Definition of a processing function. The function expects a block of data
      * and a cipher and produces a block of data to be passed downstream.
      */
    type CryptFunc = (ByteString, Cipher) => ByteString

    val in: Inlet[ByteString] = Inlet[ByteString]("CryptStage.in")
    val out: Outlet[ByteString] = Outlet[ByteString]("CryptStage.out")

    override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        /** The cipher object managed by this class. */
        private lazy val cryptCipher = createCipher()

        /** The current processing function. */
        private var processingFunc: CryptFunc = initProcessing

        /**
          * A flag whether data has been received by this stage. This is used to
          * determine whether a final block of data has to be handled when
          * upstream ends.
          */
        private var dataProcessed = false

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val data = grab(in)
            val processedData = processingFunc(data, cryptCipher)
            push(out, processedData)
          }

          override def onUpstreamFinish(): Unit = {
            if (dataProcessed) {
              val finalBytes = cryptCipher.doFinal()
              if (finalBytes.nonEmpty) {
                push(out, ByteString(finalBytes))
              }
            }
            super.onUpstreamFinish()
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            pull(in)
          }
        })

        /**
          * Handles initialization of data processing. This is the ''CryptFunc''
          * that is called for the first block of data received. It initializes
          * the managed cipher object, pushes the first data block downstream,
          * and sets the actual processing function.
          *
          * @param data   the first chunk of data
          * @param cipher the cipher object
          * @return the data to be pushed downstream
          */
        private def initProcessing(data: ByteString, cipher: Cipher): ByteString = {
          dataProcessed = true
          processingFunc = cryptFunc
          val dataBytes = data.toArray
          initCipher(key, random, cipher, dataBytes)
          ByteString(cipher.update(dataBytes, IvLength, dataBytes.length - IvLength))
        }
      }

    /**
      * Returns the function executing decryption logic. This function is
      * called when initialization is done to process further data chunks.
      *
      * @return the function for processing data chunks
      */
    private def cryptFunc: CryptFunc = (chunk, cipher) => {
      val encData = cipher.update(chunk.toArray)
      ByteString(encData)
    }
  }

}
