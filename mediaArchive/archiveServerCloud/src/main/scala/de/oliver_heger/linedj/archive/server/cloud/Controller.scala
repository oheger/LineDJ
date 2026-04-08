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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.server.ArchiveController
import de.oliver_heger.linedj.archive.server.ArchiveServerConfig.ConfigLoader
import de.oliver_heger.linedj.archive.server.MediaFileResolver.FileResolverFunc
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.utils.SystemPropertyAccess

import scala.concurrent.Future

/**
  * Implementation of an [[ArchiveController]] for the cloud archive server
  * application. This controller creates the components responsible for
  * managing the credentials of cloud archives and loading the archive data
  * once the credentials become available.
  *
  * @param credentialsManagerFactory the factory to create the credential
  *                                  manager
  * @param archiveManagerFactory     the factory to create the archive manager
  */
class Controller(credentialsManagerFactory: CloudArchiveCredentialsManager.Factory =
                 CloudArchiveCredentialsManager.newInstance,
                 archiveManagerFactory: CloudArchiveManager.Factory =
                 CloudArchiveManager.newInstance) extends ArchiveController:
  this: SystemPropertyAccess =>
  override type ArchiveConfig = CloudArchiveServerConfig

  override type CustomContext = Unit

  override def configLoader: ConfigLoader[ArchiveConfig] =
    CloudArchiveServerConfig.parseConfig

  override def fileResolverFunc(context: ArchiveController.ArchiveServerContext[ArchiveConfig, CustomContext]):
  FileResolverFunc = ???

  override def createCustomContext(context: ArchiveController.ArchiveServerContext[ArchiveConfig, Unit])
                                  (using services: ServerController.ServerServices):
  Future[Unit] = ???
