/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.archive.cloud.auth.Credentials
import de.oliver_heger.linedj.archivehttp.config.UserCredentials

/**
  * A helper class to pass the credentials for a specific realm to a
  * credentials setter object.
  *
  * This class mainly implements a work-around that allows for an easy
  * integration of the ''cloudAccess'' module with the HTTP archive startup
  * application - without major changes on this application. The preferred way
  * to deal with credentials would be to directly pass them to the setter when
  * they become available and to request futures for the required credentials
  * from the resolver function. When these futures complete, the associated 
  * archives can start.
  *
  * But, since this workflow does not really fit to the current application
  * design, the integration is done differently: The application manages the
  * required credentials itself. When they are available, during the archive
  * startup operation, they are passed to the credentials manager, so that they
  * can be obtained from the factory for the cloud file downloader.
  *
  * @param credentialsSetter the object to set credentials
  */
class CredentialsProvider(credentialsSetter: Credentials.CredentialSetter):
  /**
    * Passes the credentials for a specific realm to the credentials manager
    * actor referenced by this instance. The keys used for these credentials
    * depend on the type of the realm.
    *
    * @param realm       the affected realm
    * @param credentials the credentials for this realm
    */
  def passCredentials(realm: ArchiveRealm, credentials: UserCredentials): Unit =
    realm match
      case BasicAuthRealm(name) =>
        passCredential(s"$name.username", Secret(credentials.userName))
        passCredential(s"$name.password", credentials.password)

      case OAuthRealm(name) =>
        passCredential(name, credentials.password)

  /**
    * Passes the key to encrypt/decrypt an archive to the credentials manager
    * actor referenced by this instance. This function gets called only for
    * encrypted archives.
    *
    * @param archiveName the name of the archive
    * @param key         the [[Secret]] for the encryption key
    */
  def passEncryptionKey(archiveName: String, key: Secret): Unit =
    passCredential(archiveName, key)

  /**
    * Passes the given combination of key and secret value for a credential to
    * the associated credentials manager actor.
    *
    * @param key   the key of the credential
    * @param value the secret value of the credential
    */
  private def passCredential(key: String, value: Secret): Unit =
    credentialsSetter.setCredential(key, value)
