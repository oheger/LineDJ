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

package de.oliver_heger.linedj.shared.archive.media

import de.oliver_heger.linedj.shared.archive.media.MediumID.UndefinedMediumID

/**
  * A data class representing the ID of a medium.
  *
  * A medium is a root of a directory structure containing media files. The URI
  * to this root directory identifies the medium in a unique way. The medium can
  * be described by a medium description file; in this case, meta information
  * is available about this medium.
  *
  * All media files that do not belong to a specific medium are collected as
  * well. The IDs for such mediums do not contain a path to a medium
  * description file. In addition, a synthetic medium is maintained which
  * combines all media without a description. This is a global list of media
  * files which cannot be associated to a specific medium.
  *
  * The media archive can consist of multiple components where each component
  * can contribute media files. To reflect this, a ''MediumID'' contains an ID
  * for the component that is responsible for the associated medium.
  *
  * @param mediumURI             the URI which identifies this medium
  * @param mediumDescriptionPath the optional path to the medium description
  *                              file (as string)
  * @param archiveComponentID    the ID of the providing archive component
  */
case class MediumID(mediumURI: String, mediumDescriptionPath: Option[String],
                    archiveComponentID: String = ""):
  /**
    * Returns a flag whether this ID represents the undefined medium of a
    * specific archive. This means that it does not have a path for a medium
    * description file, but is not the ID of the global undefined medium.
    *
    * @return a flag whether this is an archive's undefined medium
    */
  def isArchiveUndefinedMedium: Boolean =
    mediumDescriptionPath.isEmpty && this != UndefinedMediumID

object MediumID:
  /**
    * Constant for the ID for the synthetic medium which collects all media
    * files not assigned to a medium (i.e. for which no medium description file
    * is available).
    */
  val UndefinedMediumID: MediumID = MediumID("", None, "<undefined>")
