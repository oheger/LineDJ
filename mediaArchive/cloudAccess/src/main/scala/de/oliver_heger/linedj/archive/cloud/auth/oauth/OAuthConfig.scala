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

package de.oliver_heger.linedj.archive.cloud.auth.oauth

import com.github.cloudfiles.core.http.Secret

import java.nio.file.Path

/**
  * A data class collecting the properties required for an OAuth client
  * application.
  *
  * An instance of this class stores the major part of the information required
  * for the implementation of an OAuth code flow against a specific OAuth2
  * identity provider.
  *
  * @param authorizationEndpoint the URI of the authorization endpoint
  * @param tokenEndpoint         the URI of the token endpoint
  * @param scope                 the scope value
  * @param redirectUri           the redirect URI
  * @param clientId              the client ID
  */
case class OAuthConfig(authorizationEndpoint: String,
                       tokenEndpoint: String,
                       scope: String,
                       redirectUri: String,
                       clientId: String)

/**
  * A data class with information required to access the persistent information
  * related to an OAuth identity provider.
  *
  * When loading or storing an OAuth configuration an instance of this class
  * must be provided. It specifies the storage location of the configuration
  * and also determines some other properties like the encryption status.
  *
  * The whole OAuth configuration for a specific identity provider (IDP) is
  * stored in multiple files in a directory. All files belonging to the same
  * configuration share the same base name. Sensitive information like tokens
  * or client secrets are encrypted using the password provided.
  *
  * @param rootDir     the root directory where all files are stored
  * @param baseName    the base name of the files of this configuration
  * @param encPassword the password to encrypt sensitive files
  */
case class OAuthStorageConfig(rootDir: Path,
                              baseName: String,
                              encPassword: Secret):
  /**
    * Returns a path in the root directory that is derived from the base name
    * with the given suffix. This is useful to locate concrete files related to
    * a specific IDP configuration.
    *
    * @param suffix the suffix to append to the base name
    * @return the path pointing to the file with the given suffix
    */
  def resolveFileName(suffix: String): Path =
    rootDir.resolve(baseName + suffix)
