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

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{AuthConfig, BasicAuthConfig, OAuthTokenData, OAuthConfig as CloudOAuthConfig}
import de.oliver_heger.linedj.archive.cloud.CloudArchiveConfig
import de.oliver_heger.linedj.archive.cloud.auth.oauth.{OAuthConfig, OAuthStorageConfig, OAuthStorageService}
import org.apache.pekko.actor.ActorSystem

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

object DefaultAuthConfigFactory:
  /** The property to request the username for a basic auth config. */
  final val UsernameProperty = "username"

  /** The property to request the password for a basic auth config. */
  final val PasswordProperty = "password"

  /**
    * Returns the key for a specific credential property for a given method.
    *
    * @param methodName the name of the [[AuthMethod]]
    * @param property   the desired property
    * @return the full key of this credential for this method
    */
  private def authProperty(methodName: String, property: String): String = s"$methodName.$property"
end DefaultAuthConfigFactory

/**
  * A default implementation of the [[AuthConfigFactory]] trait.
  *
  * This class can create [[AuthConfig]] instances for the supported
  * authentication methods of cloud archives making use of a
  * [[DefaultAuthConfigFactory.CredentialResolverFunc]] function. The class
  * basically processes the authentication method in the archive's
  * configuration and requests the required credentials from the function.
  *
  * This class derives the keys to be passed to the credentials resolver
  * function using the following scheme where ''methodName'' refers to the
  * name of the authentication method:
  *
  *  - For [[BasicAuthMethod]], it requests two credentials with the keys
  *    ''methodName.username'', and ''methodName.password''.
  *  - For [[OAuthMethod]], it requests a credential with the same name as the
  *    authentication method; this is then used to decrypt the persistent OAuth
  *    configuration.
  *
  * @param storageService the service to load OAuth configurations and tokens
  * @param storagePath    the path where OAuth configurations are stored
  * @param resolverFunc   the function to resolve credentials
  * @param ec             the execution context
  */
class DefaultAuthConfigFactory(storageService:
                               OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData],
                               storagePath: Path)
                              (resolverFunc: Credentials.ResolverFunc)
                              (using ec: ExecutionContext, system: ActorSystem) extends AuthConfigFactory:

  import DefaultAuthConfigFactory.*

  override def createAuthConfig(config: CloudArchiveConfig): Future[AuthConfig] =
    config.authMethod match
      case BasicAuthMethod(realm) =>
        val futUser = resolverFunc(authProperty(realm, UsernameProperty))
        val futPwd = resolverFunc(authProperty(realm, PasswordProperty))
        for
          user <- futUser
          pwd <- futPwd
        yield BasicAuthConfig(user.secret, pwd)

      case OAuthMethod(realm) =>
        for
          cryptSecret <- resolverFunc(realm)
          storageConfig = OAuthStorageConfig(storagePath, realm, cryptSecret)
          config <- storageService.loadConfig(storageConfig)
          secret <- storageService.loadClientSecret(storageConfig)
          tokens <- storageService.loadTokens(storageConfig)
        yield CloudOAuthConfig(
          tokenEndpoint = config.tokenEndpoint,
          redirectUri = config.redirectUri,
          clientID = config.clientId,
          clientSecret = secret,
          initTokenData = tokens
        )
