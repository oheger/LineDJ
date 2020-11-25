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

package de.oliver_heger.linedj.archivehttpstart

import java.nio.file.Path
import java.security.Key

import akka.actor.ActorSystem
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.crypt.KeyGenerator

import scala.concurrent.Future

/**
  * Definition of a service for reading and writing the encrypted file with
  * password information for all archives (the "super password file").
  *
  * For writing the file, the service gets passed information about the
  * archives available and their credentials, and (optionally) the passwords to
  * unlock them.
  *
  * A read operation yields a collection of messages that need to be published
  * on the system message bus in order to log into the archives and unlock
  * them.
  */
trait SuperPasswordStorageService {
  /**
    * Writes an encrypted file containing the specified information about
    * realms and their credentials and archives and their unlock passwords.
    *
    * @param target        the path to the file to be written (an existing file
    *                      will be overwritten)
    * @param keyGenerator  the generator for keys
    * @param superPassword the password to encrypt the data
    * @param realms        a list of tuples with realm names and their credentials
    * @param lockData      a list of tuples with archive names and their passwords
    * @param system        the actor system
    * @return a future with the path to the file that has been written
    */
  def writeSuperPasswordFile(target: Path, keyGenerator: KeyGenerator, superPassword: String,
                             realms: Iterable[(String, UserCredentials)], lockData: Iterable[(String, Key)])
                            (implicit system: ActorSystem): Future[Path]

  /**
    * Reads an encrypted file with information about realm credentials and
    * archive passwords. The resulting information is provided in form of
    * messages that need to be published on the message bus in order to access
    * the archives involved.
    *
    * @param target        the path to the file to be read
    * @param keyGenerator  the generator for keys
    * @param superPassword the password to decrypt the data
    * @param system        the actor system
    * @return a future with the information that was read
    */
  def readSuperPasswordFile(target: Path, keyGenerator: KeyGenerator, superPassword: String)
                           (implicit system: ActorSystem): Future[Iterable[ArchiveStateChangedMessage]]
}
