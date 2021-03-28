/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem, typed}
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.fs.{CryptContentFileSystem, CryptNamesFileSystem}
import de.oliver_heger.linedj.archivehttp.io.{CookieManagementExtension, FileSystemMediaDownloader, HttpArchiveFileSystem, MediaDownloader}
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec

import java.security.{Key, SecureRandom}
import scala.util.Try

/**
  * An implementation of the [[MediaDownloaderFactory]] trait that creates
  * downloader objects based on CloudFiles ''FileSystem'' objects.
  *
  * While the ''FileSystem'' is provided by the [[HttpArchiveProtocolSpec]],
  * a correctly configured request sender actor is constructing using an
  * ''HttpRequestSenderFactory''.
  *
  * @param requestSenderFactory the factory for request actors
  */
class FileSystemMediaDownloaderFactory(val requestSenderFactory: HttpRequestSenderFactory)
  extends MediaDownloaderFactory {
  override def createDownloader[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]
  (protocolSpec: HttpArchiveProtocolSpec[ID, FILE, FOLDER], startupConfig: HttpArchiveStartupConfig,
   actorBaseName: String, optCryptKey: Option[Key])
  (implicit system: ActorSystem): Try[MediaDownloader] = {
    protocolSpec.createFileSystemFromConfig(startupConfig.archiveURI.toString(),
      startupConfig.archiveConfig.processorTimeout) map { fs =>
      val fsCrypt = optCryptKey.fold(fs)(wrapWithCryptFileSystem(fs, _))

      val senderConfig = createSenderConfig(startupConfig, actorBaseName)
      val spawner: Spawner = system
      val sender = if (protocolSpec.requiresMultiHostSupport)
        requestSenderFactory.createMultiHostRequestSender(spawner, senderConfig)
      else requestSenderFactory.createRequestSender(spawner, startupConfig.archiveURI, senderConfig)
      val cookieSender = if (startupConfig.needsCookieManagement)
        spawner.spawn(CookieManagementExtension(sender), optName = Some(actorBaseName + "_cookie"))
      else sender

      implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
      new FileSystemMediaDownloader(fsCrypt, cookieSender)
    }
  }

  /**
    * Extends the given file system with functionality to encrypt the content
    * of files and the names of files and folders. Encryption is done via the
    * AES algorithm.
    *
    * @param fs       the original file system
    * @param cryptKey the key to use for crypt operations
    * @tparam ID     the type of IDs
    * @tparam FILE   the type of files
    * @tparam FOLDER the type of folders
    * @return the extended file system
    */
  private def wrapWithCryptFileSystem[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]]
  (fs: HttpArchiveFileSystem[ID, FILE, FOLDER], cryptKey: Key):
  HttpArchiveFileSystem[ID, FILE, FOLDER] = {
    implicit val secRandom: SecureRandom = new SecureRandom
    val cryptNamesFs = new CryptNamesFileSystem(fs.fileSystem, Aes, cryptKey, cryptKey)
    val cryptContentFs = new CryptContentFileSystem(cryptNamesFs, Aes, cryptKey, cryptKey)
    fs.copy(fileSystem = cryptContentFs)
  }

  /**
    * Creates the configuration for the HTTP sender actor to be passed to the
    * sender factory based on the archive configuration.
    *
    * @param startupConfig the startup configuration of the archive
    * @param actorBaseName the base name for the sender actor
    * @return the configuration for the HTTP sender actor
    */
  private def createSenderConfig(startupConfig: HttpArchiveStartupConfig, actorBaseName: String):
  HttpRequestSenderConfig =
    HttpRequestSenderConfig(actorName = Some(actorBaseName),
      queueSize = startupConfig.requestQueueSize,
      retryAfterConfig = if (startupConfig.needsRetrySupport) Some(RetryAfterConfig()) else None)
}