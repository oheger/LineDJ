/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archiveunion

object MetaDataUnionActor {

  /**
    * A message processed by [[MetaDataUnionActor]] telling it that a component
    * of the media archive has been removed. This causes the actor to remove
    * all meta data associated with this archive component.
    *
    * @param archiveCompID the archive component ID
    */
  case class ArchiveComponentRemoved(archiveCompID: String)

}

/**
  * An actor class responsible for managing a union of the meta data for all
  * songs currently available in the media archive.
  */
class MetaDataUnionActor {

}