/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.archive.metadata

import de.oliver_heger.linedj.archive.media.PathUriConverter
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingResult

import scala.collection.immutable.Seq

/**
  * An internally used helper class for storing and managing the metadata
  * of a medium.
  *
  * For each medium to be processed the [[MetaDataManagerActor]] creates an
  * instance of this class. The instance stores all paths that belong to the
  * medium and allows keeping track when this medium has been completed.
  *
  * @param mediumID the medium ID
  * @param converter the ''PathUriConverter''
  */
private class MediumDataHandler(mediumID: MediumID, converter: PathUriConverter):
  /**
    * A set with the paths of all files in this medium. This is used to
    * determine whether all data has been fetched.
    */
  private var mediumUris = Set.empty[MediaFileUri]

  /**
    * Notifies this object that the specified list of media files is going to
    * be processed. The file paths are stored so that it can be figured out
    * when all metadata has been fetched.
    *
    * @param files the files that are going to be processed
    */
  def expectMediaFiles(files: Seq[FileData]): Unit =
    mediumUris ++= files.map(file => converter.pathToUri(file.path))

  /**
    * Notifies this object that a processing result for the managed medium has
    * been received. The handler records that this result has arrived. The
    * return value indicates that the result was expected (a value of
    * '''false''' indicates an unknown medium file and should be ignored).
    * After invoking this method ''isComplete'' can be called to check whether
    * now all results have been received.
    *
    * @param result the received result
    * @return a flag whether this is a valid result
    */
  def resultReceived(result: MetadataProcessingResult): Boolean =
    if mediumUris contains result.uri then
      mediumUris -= result.uri
      true
    else false

  /**
    * Returns a flag whether all metadata for the represented medium has been
    * obtained.
    *
    * @return a flag whether all metadata is available
    */
  def isComplete: Boolean = mediumUris.isEmpty
