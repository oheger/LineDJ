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

package de.oliver_heger.linedj.archivehttp.impl.download

import java.nio.file.Path

/**
  * A trait that abstracts access to a current [[TempReadOperation]].
  *
  * [[TempFileActorManager]] uses an instance of this trait to create and
  * access such read operations if necessary. That way it does not have to
  * bother about creating actor instances and required configuration
  * properties.
  */
trait TempReadOperationHolder:
  /**
    * Returns an ''Option'' with the currently active ''TempReadOperation''.
    *
    * @return an ''Option'' with the active ''TempReadOperation''
    */
  def currentReadOperation: Option[TempReadOperation]

  /**
    * Returns the current ''TempReadOperation'' if it is defined or creates a
    * new one if a path to read from is available.
    *
    * @param optPath a function returning an optional path to read from
    * @return an ''Option'' with the existing or new read operation
    */
  def getOrCreateCurrentReadOperation(optPath: => Option[Path]): Option[TempReadOperation]

  /**
    * Resets the current read operation, so that the next time the operation
    * is queried a new one is created.
    */
  def resetReadOperation(): Unit
