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

import com.github.cloudfiles.core.http.Secret

import scala.concurrent.Future

/**
  * A module providing functionality to deal with credentials.
  *
  * To connect to a cloud archive, credentials are required in any form. How
  * these credentials are managed or obtained depends on the application that
  * interacts with archives. This module defines an abstraction for accessing
  * such credentials and provides an implementation of an actor for managing
  * credentials that connects application-specific code to obtain credentials
  * to clients that need these credentials.
  */
object Credentials:
  /**
    * An alias for a function that can resolve credentials (for cloud archives)
    * based on keys. It is used by different components to obtain the concrete
    * secrets to connect to a cloud archive. The function expects a string key
    * and returns a [[Future]] with the resolved [[Secret]].
    */
  type ResolverFunc = String => Future[Secret]
