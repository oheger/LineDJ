package de.oliver_heger.linedj.crypt

import java.security.Key

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
}
