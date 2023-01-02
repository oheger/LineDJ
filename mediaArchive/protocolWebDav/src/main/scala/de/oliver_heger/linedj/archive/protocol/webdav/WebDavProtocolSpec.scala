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

package de.oliver_heger.linedj.archive.protocol.webdav

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.cloudfiles.webdav.{DavConfig, DavFileSystem, DavModel}
import de.oliver_heger.linedj.archive.protocol.webdav.WebDavProtocolSpec.WebDavProtocolName
import de.oliver_heger.linedj.archivehttp.io.HttpArchiveFileSystem
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec

import scala.util.{Success, Try}

object WebDavProtocolSpec {
  /** The name to be returned for this protocol. */
  final val WebDavProtocolName = "webdav"
}

/**
  * Implementation of the WebDav protocol to be used by HTTP archives.
  *
  * This class provides information required for accessing media files from a
  * WebDav server. The actual access is done via a ''DavFileSystem'' from the
  * CloudFiles project. The source URI passed to this class is a plain URI
  * pointing to the archive's root folder. All URIs for media files, metadata
  * files, or the content file are resolved relative to this folder.
  */
class WebDavProtocolSpec extends HttpArchiveProtocolSpec[Uri, DavModel.DavFile, DavModel.DavFolder] {
  override def name: String = WebDavProtocolName

  /**
    * @inheritdoc All WebDav operations target the same host; so this
    *             implementation returns '''false'''.
    */
  override def requiresMultiHostSupport: Boolean = false

  override def createFileSystemFromConfig(sourceUri: String, timeout: Timeout):
  Try[HttpArchiveFileSystem[Uri, DavModel.DavFile, DavModel.DavFolder]] = {
    val rootUri = Uri(sourceUri)
    val davConfig = DavConfig(rootUri = rootUri, timeout = timeout)
    val fs = new DavFileSystem(davConfig)
    Success(HttpArchiveFileSystem(fs, rootUri.path))
  }
}
