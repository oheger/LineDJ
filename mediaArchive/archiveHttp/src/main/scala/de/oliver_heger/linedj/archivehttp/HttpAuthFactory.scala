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

package de.oliver_heger.linedj.archivehttp

import akka.stream.ActorMaterializer
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig.AuthConfigureFunc
import de.oliver_heger.linedj.archivehttp.config.{OAuthStorageConfig, UserCredentials}

import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait defining factory functions for creating authentication mechanisms
  * for an HTTP archive.
  *
  * Using this trait [[AuthConfigureFunc]] objects can be created that are
  * required to initialize an HTTP archive.
  */
trait HttpAuthFactory {

  /**
    * Returns a ''Future'' with a function to configure authentication that
    * implements the basic auth scheme.
    *
    * @param credentials the credentials to be applied
    * @return the basic auth configure function
    */
  def basicAuthConfigureFunc(credentials: UserCredentials): Future[AuthConfigureFunc]

  /**
    * Returns a ''Future'' with a function to configure authentication based on
    * OAuth 2.0. This function tries to load all relevant OAuth-specific
    * information as specified by the passed in configuration. This is done
    * asynchronously and may fail. The resulting function can then extend a
    * plain HTTP request actor to send a correct access token in the
    * ''Authorization'' header.
    *
    * @param storageConfig the OAuth storage configuration
    * @param ec            the execution context
    * @param mat           the object to materialize streams
    * @return a ''Future'' with the OAuth configure function
    */
  def oauthConfigureFunc(storageConfig: OAuthStorageConfig)(implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[AuthConfigureFunc]
}
