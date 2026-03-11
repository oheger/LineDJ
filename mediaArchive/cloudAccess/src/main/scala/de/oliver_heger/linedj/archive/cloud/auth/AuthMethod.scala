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

package de.oliver_heger.linedj.archive.cloud.auth

/**
  * A trait defining the root of a hierarchy of classes representing the
  * authentication methods supported for cloud archives.
  *
  * The concrete subclasses correspond to authentication methods supported by
  * the CloudFiles library. They are rather high-level and do not define
  * attributes on their own other than the realm. The realm basically selects a
  * set of credentials that are to be used in a way as required by the
  * represented authentication mechanism.
  */
sealed trait AuthMethod:
  /**
    * Returns the name of the realm this authentication method is used for.
    *
    * @return the realm name
    */
  def realm: String

  /**
    * Returns a [[Set]] with the keys that need to be queried from a credential
    * resolver function to obtain the secrets required by this method. This
    * information is interesting for certain use cases, for instance, to reset
    * authentication information for a specific realm.
    *
    * @return a [[Set]] with the required credential keys
    */
  def credentialKeys: Set[String]
end AuthMethod

object BasicAuthMethod:
  /** The property to request the username for a basic auth config. */
  private val UsernameProperty = "username"

  /** The property to request the password for a basic auth config. */
  private val PasswordProperty = "password"

  /**
    * Returns the key for a specific credential property for a given method.
    *
    * @param methodName the name of the [[AuthMethod]]
    * @param property   the desired property
    * @return the full key of this credential for this method
    */
  private def authProperty(methodName: String, property: String): String = s"$methodName.$property"
end BasicAuthMethod

/**
  * A concrete [[AuthMethod]] implementation representing the ''BasicAuth''
  * authentication mechanism.
  *
  * @param realm the realm to authenticate against
  */
final case class BasicAuthMethod(override val realm: String) extends AuthMethod:

  import BasicAuthMethod.*

  override def credentialKeys: Set[String] = Set(usernameKey, passwordKey)

  /**
    * Returns the key of the username credential for this method.
    *
    * @return the key to query the credential for the username
    */
  def usernameKey: String = authProperty(realm, UsernameProperty)

  /**
    * Returns the key of the password credential for this method.
    *
    * @return the key to query the credential for the password
    */
  def passwordKey: String = authProperty(realm, PasswordProperty)
end BasicAuthMethod

/**
  * A concrete [[OAuthMethod]] implementation representing the ''OAuth''
  * authentication mechanism.
  *
  * @param realm the realm to authenticate against
  */
final case class OAuthMethod(override val realm: String) extends AuthMethod:
  override def credentialKeys: Set[String] = Set(realm)

  /**
    * Returns the key of the storage credential that is used to encrypt the
    * sensitive part of the OAuth IDP configuration.
    *
    * @return the key to query the credential for the storage encryption
    */
  def storageKey: String = realm
end OAuthMethod
