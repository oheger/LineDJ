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

package de.oliver_heger.linedj.archivehttp.io

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.core.http.HttpRequestSender

import scala.concurrent.{ExecutionContext, Future}

/**
  * An implementation of the [[MediaDownloader]] trait that is based on a
  * ''FileSystem''.
  *
  * This implementation uses the ''FileSystem'' and the HTTP request sender
  * actor passed to the constructor to download media files from HTTP archives.
  * The URIs passed to the ''downloadMediaFile()'' function are interpreted as
  * relative paths to the files (based on the file system's root). These paths
  * are resolved, and the resulting files are downloaded.
  *
  * @param archiveFileSystem the ''FileSystem'' to access the HTTP archive
  * @param httpSender        the actor for sending HTTP requests
  * @param system            the actor system
  * @tparam ID the type of IDs in the ''FileSystem''
  */
class FileSystemMediaDownloader[ID](val archiveFileSystem: HttpArchiveFileSystem[ID, _, _],
                                    val httpSender: ActorRef[HttpRequestSender.HttpCommand])
                                   (implicit system: ActorSystem[_]) extends MediaDownloader {
  override def downloadMediaFile(uri: Uri): Future[Source[ByteString, Any]] = {
    implicit val ec: ExecutionContext = system.executionContext
    val op = for {
      id <- archiveFileSystem.fileSystem.resolvePath(fileSystemPath(uri))
      entity <- archiveFileSystem.fileSystem.downloadFile(id)
    } yield entity.dataBytes

    op.run(httpSender)
  }

  override def contentFileName: String = archiveFileSystem.contentFile

  /**
    * @inheritdoc This implementation stops the HTTP sender actor used by this
    *             downloader.
    */
  override def shutdown(): Unit = {
    httpSender ! HttpRequestSender.Stop
  }

  /**
    * Extracts the path to resolve from the given URI. If the URI's path starts
    * with the configured root path, this prefix is stripped.
    *
    * @param uri the URI to resolve
    * @return the path to pass to the file system
    */
  private def fileSystemPath(uri: Uri): String = {
    val uriPath = uri.path.toString()
    if (uriPath.startsWith(archiveFileSystem.rootPath)) uriPath.substring(archiveFileSystem.rootPath.length)
    else uriPath
  }
}
