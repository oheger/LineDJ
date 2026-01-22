/*
 * Copyright 2015-2025 The Developers Team.
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
  def realm: String
end AuthMethod

/**
  * A concrete [[AuthMethod]] implementation representing the ''BasicAuth''
  * authentication mechanism.
  *
  * @param realm the realm to authenticate against
  */
final case class BasicAuthMethod(override val realm: String) extends AuthMethod

/**
  * A concrete [[OAuthMethod]] implementation representing the ''OAuth''
  * authentication mechanism.
  *
  * @param realm the realm to authenticate against
  */
final case class OAuthMethod(override val realm: String) extends AuthMethod
