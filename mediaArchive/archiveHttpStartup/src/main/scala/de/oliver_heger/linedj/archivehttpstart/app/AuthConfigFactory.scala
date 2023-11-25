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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{AuthConfig, BasicAuthConfig, OAuthTokenData, OAuthConfig => CloudOAuthConfig}
import de.oliver_heger.linedj.archivehttp.config.{OAuthStorageConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.io.oauth.{OAuthConfig, OAuthStorageService}
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future}

/**
  * A helper class that produces an [[AuthConfig]] from credential information
  * for a specific archive.
  *
  * An instance of this class is used when starting up HTTP archives. In order
  * to configure the archives to correctly access media files, the proper
  * authentication information must be present.
  *
  * @param oauthStorageService the service to access OAuth credentials
  */
class AuthConfigFactory(val oauthStorageService:
                        OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]):
  /**
    * Returns an ''AuthConfig'' for the given realm and credentials. The steps
    * necessary to obtain all the information required for the correct type of
    * ''AuthConfig'' are performed. As this may involve asynchronous
    * operations, result is a ''Future''.
    *
    * @param realm       the realm for which authentication is needed
    * @param credentials the credentials for this realm
    * @param system      the actor system
    * @return a ''Future'' with the ''AuthConfig'' that was created
    */
  def createAuthConfig(realm: ArchiveRealm, credentials: UserCredentials)
                      (implicit system: ActorSystem): Future[AuthConfig] =
    realm match
      case _: BasicAuthRealm =>
        Future.successful(BasicAuthConfig(credentials.userName, credentials.password))
      case r: OAuthRealm =>
        implicit val ec: ExecutionContext = system.dispatcher
        val storageConfig = r.createIdpConfig(credentials.password)
        for
          config <- oauthStorageService.loadConfig(storageConfig)
          secret <- oauthStorageService.loadClientSecret(storageConfig)
          tokens <- oauthStorageService.loadTokens(storageConfig)
        yield CloudOAuthConfig(tokenEndpoint = config.tokenEndpoint, redirectUri = config.redirectUri,
          clientID = config.clientID, clientSecret = secret, initTokenData = tokens)
