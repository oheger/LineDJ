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

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem

/**
  * A data class storing all the relevant information for using a ''FileSystem''
  * to access the data of an HTTP archive.
  *
  * The class combines an ''ExtensibleFileSystem'' with some metadata that is
  * required to process HTTP archives, such as the root path and the name of
  * the file with the archive's ToC.
  *
  * @param fileSystem  the file system to access media files in this archive
  * @param rootPath    the root path of this archive; this is needed to
  *                    correctly deal with relative and absolute URIs to media
  *                    files
  * @param contentFile the name of the file with the archive's content
  * @tparam ID     the type of IDs in the file system
  * @tparam FILE   the type of files in the file system
  * @tparam FOLDER the type of folders in the file system
  */
case class HttpArchiveFileSystem[ID, FILE <: Model.File[ID],
  FOLDER <: Model.Folder[ID]](fileSystem: ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                              rootPath: String,
                              contentFile: String)
