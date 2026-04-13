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

package de.oliver_heger.linedj.archive.cloud

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.auth.AuthConfig
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.fs.resolver.CachePathComponentsResolver
import com.github.cloudfiles.crypt.fs.{CryptConfig, CryptContentFileSystem, CryptNamesConfig, CryptNamesFileSystem}
import de.oliver_heger.linedj.archive.cloud.auth.{AuthConfigFactory, Credentials}
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory.CloudArchiveFileSystem
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import de.oliver_heger.linedj.shared.archive.media.UriHelper
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.{ActorSystem, typed}

import java.security.SecureRandom
import scala.concurrent.{ExecutionContext, Future}

object DefaultCloudFileDownloaderFactory:
  /**
    * The suffix for credential keys for the decryption password of an
    * encrypted archive. If an archive is marked as encrypted, the factory
    * registers for a credential whose key consists of the name of the archive
    * plus this suffix.
    */
  final val CryptKeySuffix = ".crypt"

  /**
    * Returns a [[Set]] with all credential keys that are consumed by the cloud
    * archive with the given configuration. Only if all these keys are present,
    * the archive can be accessed. Having this information, is useful in some
    * cases. For instance, when access to the archive fails because of 
    * incorrect credentials, the current values of these credentials should be
    * removed, so that they can be entered again.
    *
    * @param archiveConfig the archive configuration
    * @return a [[Set]] with all credential keys that are required by this
    *         archive configuration
    */
  def credentialKeys(archiveConfig: CloudArchiveConfig): Set[String] =
    val authMethodKeys = archiveConfig.authMethod.credentialKeys
    archiveConfig.optCryptConfig.fold(authMethodKeys): _ =>
      authMethodKeys + (archiveConfig.archiveName + CryptKeySuffix)

  /**
    * Extends the given file system with functionality to encrypt the content
    * of files and the names of files and folders. Encryption is done via the
    * AES algorithm.
    *
    * @param fs           the original file system
    * @param config       the configuration of the archive
    * @param resolverFunc the credentials resolver function
    * @tparam ID     the type of IDs
    * @tparam FILE   the type of files
    * @tparam FOLDER the type of folders
    * @return the extended file system
    */
  private def wrapWithCryptFileSystem[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]
  (fs: CloudArchiveFileSystem[ID, FILE, FOLDER],
   config: CloudArchiveConfig)
  (resolverFunc: Credentials.ResolverFunc)
  (using ec: ExecutionContext, system: ActorSystem): Future[CloudArchiveFileSystem[ID, FILE, FOLDER]] =
    config.optCryptConfig match
      case Some(archiveCryptConfig) =>
        resolverFunc(config.archiveName + CryptKeySuffix) map : cryptSecret =>
          val cryptKey = Aes.keyFromString(cryptSecret.secret)
          val cryptConfig = CryptConfig(Aes, cryptKey, cryptKey, new SecureRandom)
          val namesConfig = CryptNamesConfig(cryptConfig = cryptConfig, ignoreUnencrypted = true)
          val resolver = CachePathComponentsResolver[ID, FILE, FOLDER](
            system,
            archiveCryptConfig.cryptCacheSize,
            archiveCryptConfig.cryptChunkSize
          )(using config.timeout)
          val cryptNamesFs = new CryptNamesFileSystem(fs, namesConfig, resolver)
          new CryptContentFileSystem(cryptNamesFs, cryptConfig)
      case None => Future.successful(fs)

  /**
    * Creates the configuration for the HTTP sender actor to be passed to the
    * sender factory based on the archive configuration.
    *
    * @param config     the  configuration of the archive
    * @param authConfig the config for the auth mechanism
    * @return the configuration for the HTTP sender actor
    */
  private def createSenderConfig(config: CloudArchiveConfig, authConfig: AuthConfig): HttpRequestSenderConfig =
    HttpRequestSenderConfig(
      queueSize = config.requestQueueSize,
      authConfig = authConfig,
      actorName = config.optActorBaseName.orElse(Some(UriHelper.urlEncode(config.archiveName))),
      retryAfterConfig = Some(RetryAfterConfig())
    )
end DefaultCloudFileDownloaderFactory

/**
  * A default implementation of the [[CloudFileDownloaderFactory]] trait.
  *
  * This class creates a file system with the properties as specified by the
  * provided [[CloudArchiveConfig]]. This file system is then used by the
  * [[CloudFileDownloader]] implementation this factory creates. For
  * authentication against the cloud server, this class delegates to an
  * [[AuthConfigFactory]] which has to be provided on construction time.
  *
  * If the archive configuration references an [[ArchiveCryptConfig]], this
  * class creates a corresponding encrypted file system (using AES as
  * encryption algorithm). It queries the encryption password from the given
  * [[Credentials.ResolverFunc]] using the archive name
  * as credentials key.
  *
  * @param authConfigFactory    the factory for the authentication config
  * @param requestSenderFactory the factory to create a request sender actor
  * @param system               the actor system
  */
class DefaultCloudFileDownloaderFactory(val authConfigFactory: AuthConfigFactory,
                                        val requestSenderFactory: HttpRequestSenderFactory)
                                       (using system: ActorSystem)
  extends CloudFileDownloaderFactory:

  import DefaultCloudFileDownloaderFactory.*

  override def createDownloader(config: CloudArchiveConfig): Future[CloudFileDownloader] =
    for
      authConfig <- authConfigFactory.createAuthConfig(config)
      fs <- Future.fromTry(config.fileSystemFactory.createFileSystem(config.archiveBaseUri.toString, config.timeout))
      fsCrypt <- wrapWithCryptFileSystem(fs, config)(authConfigFactory.resolverFunc)
    yield
      val senderConfig = createSenderConfig(config, authConfig)
      val spawner: Spawner = system
      val sender = if config.fileSystemFactory.requiresMultiHostSupport then
        requestSenderFactory.createMultiHostRequestSender(spawner, senderConfig)
      else
        requestSenderFactory.createRequestSender(spawner, config.archiveBaseUri, senderConfig)

      new FileSystemCloudFileDownloader(fsCrypt, sender)(using system.toTyped)
