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

package de.oliver_heger.linedj.archive.cloud.auth

import com.github.cloudfiles.core.http.auth.AuthConfig
import de.oliver_heger.linedj.archive.cloud.CloudArchiveConfig

import scala.concurrent.Future

/**
  * A trait defining a factory method to create the [[AuthConfig]] for a cloud
  * archive.
  *
  * An implementation is responsible to evaluate the [[CloudArchiveConfig]] of
  * the archive to figure out the required [[AuthMethod]] and obtain the
  * corresponding credentials.
  */
trait AuthConfigFactory:
  /**
    * Creates the [[AuthConfig]] to be used when connecting to the cloud
    * archive described by the passed in configuration. This includes obtaining
    * the required credentials. Since this is a complex operation and might
    * involve some asynchronous steps, the function returns a [[Future]].
    *
    * @param config the configuration of the affected archive
    * @return a [[Future]] with the [[AuthConfig]] for this archive
    */
  def createAuthConfig(config: CloudArchiveConfig): Future[AuthConfig]
