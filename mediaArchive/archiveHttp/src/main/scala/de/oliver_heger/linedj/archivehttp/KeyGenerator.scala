/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp

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
