/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.io.oauth

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait defining a service that supports loading information related to
  * OAuth.
  *
  * The service has functions to deal with basic properties of an OAuth
  * identity provider, but also with dynamic data like tokens.
  *
  * @tparam STORAGE_CONFIG the type representing the storage configuration
  * @tparam CONFIG         the type representing the OAuth configuration
  * @tparam CLIENT_SECRET  the type representing the client secret
  * @tparam TOKENS         the type representing token data
  */
trait OAuthStorageService[STORAGE_CONFIG, CONFIG, CLIENT_SECRET, TOKENS]:
  /**
    * Loads the ''OAuthConfig'' defined by the given storage config. If
    * successful, it can be obtained from the resulting future.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system to materialize streams
    * @return a ''Future'' with the ''OAuthConfig''
    */
  def loadConfig(storageConfig: STORAGE_CONFIG)
                (implicit ec: ExecutionContext, system: ActorSystem): Future[CONFIG]

  /**
    * Loads the OAuth client secret defined by the given storage configuration.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system to materialize streams
    * @return a ''Future'' with the OAuth client secret
    */
  def loadClientSecret(storageConfig: STORAGE_CONFIG)
                      (implicit ec: ExecutionContext, system: ActorSystem): Future[CLIENT_SECRET]

  /**
    * Loads token information defined by the given storage configuration.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system to materialize streams
    * @return a ''Future'' with the token material that has been loaded
    */
  def loadTokens(storageConfig: STORAGE_CONFIG)
                (implicit ec: ExecutionContext, system: ActorSystem): Future[TOKENS]
