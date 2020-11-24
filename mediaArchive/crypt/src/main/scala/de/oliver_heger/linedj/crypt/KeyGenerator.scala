package de.oliver_heger.linedj.crypt

import java.security.Key
import java.util.Base64

/**
  * A trait defining a generator for keys used for crypt operations.
  *
  * An object implementing this trait can be used to convert a password entered
  * as text into a (secret) key to decrypt files from an encrypted HTTP
  * archive.
  */
trait KeyGenerator {
  /**
    * Generates a key from the passed in password.
    *
    * @param password the password
    * @return the resulting key
    */
  def generateKey(password: String): Key

  /**
    * Returns an encoded form of the passed in key. The function asks the key
    * for its encoded form and then applies a Base64 encoding on it.
    *
    * @param key the key to encode
    * @return
    */
  def encodeKey(key: Key): Array[Byte] = {
    Base64.getEncoder.encode(key.getEncoded)
  }

  /**
    * Returns a key from its (Base64) encoded form. This is the inverse
    * operation of ''encodeKey()''.
    *
    * @param keyData the (Base64) encoded key data
    * @return the resulting key
    */
  def decodeKey(keyData: Array[Byte]): Key = {
    val decodedData = Base64.getDecoder.decode(keyData)
    createKeyFromEncodedForm(decodedData)
  }

  /**
    * Creates a key from its (raw) encoded form. This function is called from
    * ''decodeKey()'' after the key data has been Base64-decoded. A concrete
    * implementation must now generate the resulting key.
    *
    * @param keyData the (raw) encoded key data
    * @return the resulting key
    */
  protected def createKeyFromEncodedForm(keyData: Array[Byte]): Key
}
