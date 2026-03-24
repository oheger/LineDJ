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

package de.oliver_heger.linedj.archive.cloud.spi

import com.github.cloudfiles.core.Model
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory.CloudArchiveFileSystem
import org.apache.pekko.util.Timeout

import scala.util.Try

object CloudArchiveFileSystemFactoryForTesting:
  final val FactoryName = "testCloudArchiveFileSystemFactory"
end CloudArchiveFileSystemFactoryForTesting

/**
  * A dummy implementation of the [[CloudArchiveFileSystemFactory]] trait for
  * testing of the service loader functionality.
  */
class CloudArchiveFileSystemFactoryForTesting extends CloudArchiveFileSystemFactory:
  override type ID = String

  override type File = Model.File[ID]

  override type Folder = Model.Folder[ID]

  override def name: String = CloudArchiveFileSystemFactoryForTesting.FactoryName

  override def requiresMultiHostSupport: Boolean = false

  override def createFileSystem(sourceUri: String, timeout: Timeout): Try[CloudArchiveFileSystem[ID, File, Folder]] =
    throw new UnsupportedOperationException("Unexpected invocation.")
