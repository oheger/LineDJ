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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import com.github.cloudfiles.core.http.factory.HttpRequestSenderFactory
import de.oliver_heger.linedj.archive.cloud.auth.oauth.{OAuthConfig, OAuthStorageConfig, OAuthStorageService}
import de.oliver_heger.linedj.archive.cloud.auth.{Credentials, DefaultAuthConfigFactory}
import de.oliver_heger.linedj.archive.cloud.{CloudFileDownloaderFactory, DefaultCloudFileDownloaderFactory}
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef

import java.nio.file.Paths
import scala.concurrent.ExecutionContext

object DownloaderFactoryBean:
  /**
    * The name of the configuration property that defins the path under which
    * OAuth configuration data is stored.
    */
  final val PropOAuthStoragePath = "media.realms.oauthStoragePath"

  /** The name for the credentials manager actor used by this application. */
  private val CredentialsActorName = "archiveHttpStartupCredentials"
end DownloaderFactoryBean

/**
  * A class that can create a [[CloudFileDownloaderFactory]] instance and
  * provide it to the dependency injection framework.
  *
  * Creating such a factory using board means of the dependency injection
  * framework is quite tricky, especially due to Scala specifics like multiple
  * parameter lists and implicit parameters. Therefore, the required logic is
  * implemented in code.
  *
  * @param actorSystem        the current actor system
  * @param executionContext   the execution context
  * @param actorFactory       the factory to create actors
  * @param storageService     the service to load OAuth configurations
  * @param senderFactory      the factory to create an HTTP sender
  * @param clientApplicationContext the client application context
  */
case class DownloaderFactoryBean(actorSystem: ActorSystem,
                                 executionContext: ExecutionContext,
                                 actorFactory: ActorFactory,
                                 storageService:
                                 OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData],
                                 senderFactory: HttpRequestSenderFactory,
                                 clientApplicationContext: ClientApplicationContext):

  import Credentials.given
  import DownloaderFactoryBean.*

  /** Stores data for resolving credentials. */
  private val credentialsResolverData = Credentials.setUpCredentialsManager(actorFactory, CredentialsActorName)

  /**
    * Creates the [[CloudFileDownloaderFactory]] based on the constructor 
    * arguments passed to this instance.
    *
    * @return the newly created [[CloudFileDownloaderFactory]]
    */
  def createDownloaderFactory(): CloudFileDownloaderFactory = 
    given ActorSystem = actorSystem

    given ExecutionContext = executionContext

    val authFactory = DefaultAuthConfigFactory(
      storageService = storageService,
      storagePath = Paths.get(clientApplicationContext.managementConfiguration.getString(PropOAuthStoragePath))
    )(credentialsResolverData._2)
    DefaultCloudFileDownloaderFactory(authFactory, senderFactory)(credentialsResolverData._2)

  /**
    * Returns the credentials manager actor that is connected to the downloader
    * factory created by this instance.
    *
    * @return the credentials manager actor
    */
  def credentialsManager(): ActorRef[Credentials.CredentialData] = credentialsResolverData._1
