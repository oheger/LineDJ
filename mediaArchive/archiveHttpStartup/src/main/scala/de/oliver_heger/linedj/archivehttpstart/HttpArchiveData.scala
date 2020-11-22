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

import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, OAuthStorageConfig}
import de.oliver_heger.linedj.crypt.Secret

/**
  * A trait representing a realm that can be assigned to an archive.
  *
  * A realm stores the credentials that are required to access an archive.
  * There can be multiple realm implementations corresponding to different
  * authentication mechanisms. Multiple archives can share the same realm if
  * they use the same authentication mechanism (e.g. if they are located on the
  * same physical server).
  */
sealed trait ArchiveRealm {
  /**
    * Returns a name for this realm. The name is used to reference the realm
    * from the declaration of the archive. Names are arbitrary strings, but
    * must be unique.
    *
    * @return the name of this realm
    */
  def name: String

  /**
    * Returns a flag whether the credentials for this realm contain a user ID.
    * For some realm implementations only a password is relevant. This flag is
    * evaluated when prompting the user to log-in into this realm.
    *
    * @return a flag whether a user ID is required during login
    */
  def needsUserID: Boolean
}

/**
  * Concrete realm implementation that represents the basic auth mechanism.
  *
  * Realms of this type require the user to log in with user name and password.
  * These credentials are used directly when communicating with the server that
  * hosts the archive.
  *
  * @param name the name of this realm
  */
case class BasicAuthRealm(override val name: String) extends ArchiveRealm {
  /**
    * @inheritdoc This implementation returns '''true''' as the user ID is
    *             part of the basic auth credentials.
    */
  override def needsUserID: Boolean = true
}

/**
  * Concrete realm implementation that represents the OAuth 2 authentication
  * mechanism.
  *
  * A realm of this type contains the basic information required to generate an
  * [[OAuthStorageConfig]], which describes the IDP to be used for
  * authentication requests. This class only requires a password as credentials
  * to unlock sensitive data related to the IDP.
  *
  * @param name    the name of this realm
  * @param rootDir the directory storing information about the IDP
  * @param idpName the name of the IDP
  */
case class OAuthRealm(override val name: String,
                      rootDir: Path,
                      idpName: String) extends ArchiveRealm {
  /**
    * @inheritdoc This implementation returns '''false''' because only a
    *             password is needed to decrypt IDP-related information.
    */
  override def needsUserID: Boolean = false

  /**
    * Creates an ''OAuthStorageConfig'' based on the information stored in this
    * object and the given ''Secret''.
    *
    * @param secret the ''Secret'' to decrypt IDP-related data
    * @return the ''OAuthStorageConfig''
    */
  def createIdpConfig(secret: Secret): OAuthStorageConfig =
    OAuthStorageConfig(rootDir, idpName, secret)
}

/**
  * A data class collecting information about a single HTTP archive to be
  * started up.
  *
  * @param config    the configuration of the archive
  * @param realm     the realm to be used for login
  * @param shortName a unique short name for this archive
  * @param protocol  the HTTP protocol for this archive
  * @param encrypted flag whether this archive is encrypted
  */
private case class HttpArchiveData(config: HttpArchiveConfig,
                                   realm: ArchiveRealm,
                                   shortName: String,
                                   protocol: String,
                                   encrypted: Boolean = false)
