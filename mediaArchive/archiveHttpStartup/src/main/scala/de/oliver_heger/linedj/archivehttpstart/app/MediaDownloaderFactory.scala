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

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.http.auth.AuthConfig
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec
import org.apache.pekko.actor.ActorSystem

import java.security.Key
import scala.util.Try

/**
  * A trait defining a factory interface for creating a [[MediaDownloader]] for
  * a specific protocol.
  *
  * A concrete implementation has to construct such a downloader object based
  * on the properties of a [[HttpArchiveProtocolSpec]] and the current
  * configuration of the application.
  */
trait MediaDownloaderFactory {
  /**
    * Creates a fully initialized ''MediaDownloader'' that can download media
    * files via a specific protocol from a specific HTTP archive. The operation
    * may fail, for instance if the configuration has invalid settings.
    *
    * @param protocolSpec  the spec for the protocol to use
    * @param startupConfig the configuration for the archive affected
    * @param authConfig    the config for the auth mechanism
    * @param optCryptKey   optional key for encryption
    * @param actorBaseName a base name for naming actors
    * @return a ''Try'' with the ''MediaDownloader'' for this archive
    */
  def createDownloader(protocolSpec: HttpArchiveProtocolSpec,
                       startupConfig: HttpArchiveStartupConfig,
                       authConfig: AuthConfig,
                       actorBaseName: String,
                       optCryptKey: Option[Key])
                      (implicit system: ActorSystem): Try[MediaDownloader]
}
