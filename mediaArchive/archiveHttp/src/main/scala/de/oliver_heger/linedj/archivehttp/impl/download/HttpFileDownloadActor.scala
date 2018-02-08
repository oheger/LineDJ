/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.download

import java.nio.file.{Path, Paths}

import akka.actor.Props
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor.DownloadTransformFunc

object HttpFileDownloadActor {
  /**
    * A dummy chunk size to be passed to the super class. For this actor class
    * no chunk size is needed because the HTTP response takes care about this.
    * Nevertheless some value must be passed to the super constructor.
    */
  private val DummyChunkSize = 1024

  /**
    * Returns a ''Props'' object for creating a new instance of
    * ''HttpFileDownloadActor''.
    *
    * @param response      the response to be processed (must be successful)
    * @param uri           the URI of the file requested
    * @param transformFunc the transformation function
    * @return ''Props'' to create a new actor
    */
  def apply(response: HttpResponse, uri: Uri, transformFunc: DownloadTransformFunc): Props =
    Props(classOf[HttpFileDownloadActor], response, extractPathFromUri(uri), transformFunc)

  /**
    * Returns a ''Path'' based on the provided URI. This is needed for the
    * transformation logic implemented by the base class.
    *
    * @param uri the download URI
    * @return the path derived from this URI
    */
  private def extractPathFromUri(uri: Uri): Path =
    Paths.get(uri.path.toString())
}

/**
  * An actor implementation for downloading files from an HTTP archive.
  *
  * This actor class is used to read data files served by an HTTP server and
  * make them available to download clients via the protocol of a file download
  * actor. The major part of the functionality is already provided by the base
  * class. This implementation is mainly concerned with creating the correct
  * source for the stream to be processed.
  */
class HttpFileDownloadActor(response: HttpResponse, path: Path, trans: DownloadTransformFunc)
  extends MediaFileDownloadActor(path, HttpFileDownloadActor.DummyChunkSize, trans) {
  /**
    * @inheritdoc This implementation returns the source of the HTTP response's
    *             entity.
    */
  override protected def createSource(): Source[ByteString, Any] =
    response.entity.dataBytes
}
