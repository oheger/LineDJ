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

import java.security.{Key, SecureRandom}
import java.util.concurrent.atomic.AtomicLong

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import de.oliver_heger.linedj.crypt.CryptStage.IvLength
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

object CryptStage {
  /** The length of the initialization vector. */
  final val IvLength = 16

  /** The length of keys required by the encryption algorithm. */
  final val KeyLength = 16

  /** The name of the cipher used for encrypt / decrypt operations. */
  final val CipherName = "AES/CTR/NoPadding"

  /** The name of the encryption algorithm. */
  final val AlgorithmName = "AES"

  /** A counter for keeping track on the number of processed bytes. */
  private val processedBytesCount = new AtomicLong

  /**
    * Convenience method to generate a ''CryptStage'' that can be used to
    * encrypt data with the given key.
    *
    * @param key    the key for encryption
    * @param random the source of randomness
    * @return the stage to encrypt data
    */
  def encryptStage(key: Key, random: SecureRandom): CryptStage =
    new CryptStage(EncryptOpHandler, key, random)

  /**
    * Convenience method to generate a ''CryptStage'' that can be used to
    * decrypt data with the given key.
    *
    * @param key    the key for decryption
    * @param random the source of randomness
    * @return the state to decrypt data
    */
  def decryptStage(key: Key, random: SecureRandom): CryptStage =
    new CryptStage(DecryptOpHandler, key, random)

  /**
    * Returns the number of bytes that have been encrypted or decrypted in
    * total by instances of this stage class. This information is mainly used
    * for testing or statistical purposes.
    *
    * @return the number of bytes processed by instances of ''CryptStage''
    */
  def processedBytes: Long = processedBytesCount.get()

  /**
    * Resets the counter for the number of bytes processed by instances of
    * ''CryptStage''.
    */
  def resetProcessedBytes(): Unit = {
    processedBytesCount set 0
  }

  /**
    * Updates the counter for the bytes processed.
    *
    * @param buf the current buffer with data to be processed
    */
  private def updateProcessed(buf: ByteString): Unit = {
    processedBytesCount.addAndGet(buf.length)
  }
}

/**
  * An abstract base class for stages that encrypt or decrypt data.
  *
  * This class implements the major part of the stream processing logic.
  * Derived classes only have to handle their specific transformation.
  *
  * This class assumes that cryptographic operations are executed in two
  * phases: First an initialization has to be done, typically when the first
  * chunk of data arrives. Then the actual stream processing takes place in
  * which data is processed chunk-wise. The phases are represented by
  * transformation functions that need to be provided by concrete sub classes.
  *
  * @param cryptOpHandler the operation handler
  * @param key            the key to be used for the operation
  * @param random         the source for randomness
  */
class CryptStage(val cryptOpHandler: CryptOpHandler, key: Key, random: SecureRandom)
  extends GraphStage[FlowShape[ByteString, ByteString]] {

  import CryptStage._

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
      private lazy val cryptCipher = Cipher.getInstance(CipherName)

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
        updateProcessed(data)
        val result = cryptOpHandler.initCipher(key, cryptCipher, data, random)
        processingFunc = cryptFunc
        result
      }
    }

  /**
    * Returns the function executing encryption / decryption logic. This
    * function is called when initialization is done to process further data
    * chunks.
    *
    * @return the function for processing data chunks
    */
  private def cryptFunc: CryptFunc = (chunk, cipher) => {
    val encData = cipher.update(chunk.toArray)
    updateProcessed(chunk)
    ByteString(encData)
  }
}

/**
  * A trait defining an operation (such as encryption or decryption) to be
  * executed by a ''CryptStage''.
  *
  * Each ''CryptStage'' is associated with such a handler. The handler provides
  * functionality to initialize a cipher object for the operation to be
  * executed.
  */
trait CryptOpHandler {
  /**
    * Initializes the given cipher for the specific operation to be performed.
    * This method is called when first data arrives and processing has to be
    * setup.
    *
    * @param encKey    the key to encrypt / decrypt
    * @param cipher    the cipher to be initialized
    * @param chunk     the first chunk of data
    * @param secRandom the secure random object to init the cipher
    * @return the next chunk to be passed downstream
    */
  def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom): ByteString
}

/**
  * A concrete ''CryptOpHandler'' implementation for encrypting data.
  */
object EncryptOpHandler extends CryptOpHandler {
  /**
    * @inheritdoc This implementation initializes the cipher for encryption.
    *             It creates a random initialization vector for this purpose.
    *             This IV is passed downstream as part of the initial chunk
    *             of data.
    */
  override def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom): ByteString = {
    val ivBytes = new Array[Byte](IvLength)
    secRandom.nextBytes(ivBytes)
    val iv = new IvParameterSpec(ivBytes)
    cipher.init(Cipher.ENCRYPT_MODE, encKey, iv)

    val encData = cipher.update(chunk.toArray)
    ByteString(ivBytes) ++ ByteString(encData)
  }
}

/**
  * A concrete ''CryptOpHandler'' implementation for decrypting data.
  */
object DecryptOpHandler extends CryptOpHandler {
  /**
    * @inheritdoc This implementation expects that the initialization vector is
    *             at the beginning of the passed in data chunk. Based on this
    *             information, the cipher can be initialized and the first
    *             block decoded. If the IV cannot be extracted, an
    *             ''IllegalStateException'' exception is thrown.
    */
  override def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom): ByteString = {
    val data = chunk.toArray
    if (data.length < IvLength)
      throw new IllegalStateException("Illegal initial chunk! The chunk must at least contain the IV.")
    val iv = new IvParameterSpec(data, 0, IvLength)
    cipher.init(Cipher.DECRYPT_MODE, encKey, iv, secRandom)

    ByteString(cipher.update(data, IvLength, data.length - IvLength))
  }
}
