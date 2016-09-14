/*
 * Copyright 2015-2016 The Developers Team.
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

import java.nio.file.Path

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{MediaMetaData, MetaDataChunk}

/**
  * An internally used helper class for storing and managing the meta data
  * of a medium.
  *
  * When the meta data for a file becomes available this class stores it in an
  * internal map and also creates corresponding chunks. The way the URIs for
  * files are generated depends on the represented medium. Therefore, this
  * algorithm is deferred to concrete subclasses.
  *
  * @param mediumID the medium ID
  */
private abstract class MediumDataHandler(mediumID: MediumID) {
  /**
    * A set with the names of all files in this medium. This is used to
    * determine whether all data has been fetched.
    */
  private val mediumPaths = collection.mutable.Set.empty[Path]

  /** The current data available for the represented medium. */
  private var currentData = List(createInitialChunk())

  /** Stores data for the next chunk. */
  private var nextChunkData = Map.empty[String, MediaMetaData]

  /**
    * Notifies this object that the specified list of media files is going to
    * be processed. The file paths are stored so that it can be figured out
    * when all meta data has been fetched.
    *
    * @param files the files that are going to be processed
    */
  def expectMediaFiles(files: Seq[FileData]): Unit = {
    mediumPaths ++= files.map(_.path)
  }

  /**
    * Stores the specified result in this object. If the specified chunk size
    * is now reached or if the represented medium is complete, the passed in
    * function is invoked with a new chunk of data. It can then process the
    * chunk, e.g. notify registered listeners.
    *
    * @param result the result to be stored
    * @param chunkSize the chunk size
    * @param maxChunkSize the maximum size of chunks
    * @param f the function for processing a new chunk of data
    * @return a flag whether this medium is now complete (this value is
    *         returned explicitly so that it is available without having to
    *         evaluate the lazy meta data chunk expression)
    */
  def storeResult(result: MetaDataProcessingResult, chunkSize: Int, maxChunkSize: Int)
                 (f: (=> MetaDataChunk) => Unit): Boolean = {
    mediumPaths -= result.path
    val complete = isComplete
    nextChunkData = nextChunkData + (extractUri(result) -> result.metaData)

    if (nextChunkData.size >= chunkSize || complete) {
      f(createNextChunk(nextChunkData))
      currentData = updateCurrentResult(nextChunkData, complete, maxChunkSize)
      nextChunkData = Map.empty
      complete
    } else false
  }

  /**
    * Returns a flag whether all meta data for the represented medium has been
    * obtained.
    *
    * @return a flag whether all meta data is available
    */
  def isComplete: Boolean = mediumPaths.isEmpty

  /**
    * Returns the meta data stored currently in this object.
    *
    * @return the data managed by this object
    */
  def metaData: Seq[MetaDataChunk] = currentData

  /**
    * Extracts the URI to be used when storing the specified result. The URI is
    * different for the global undefined list.
    *
    * @param result the meta data result
    * @return the URI to be used for the represented file
    */
  protected def extractUri(result: MetaDataProcessingResult): String

  /**
    * Updates the current result object by adding the content of the given
    * map.
    *
    * @param data the data to be added
    * @param complete the new completion status
    * @param maxSize the maximum number of entries in a chunk
    * @return the new data object to be stored
    */
  private def updateCurrentResult(data: Map[String, MediaMetaData], complete: Boolean, maxSize: Int):
  List[MetaDataChunk] = {
    val currentChunk = currentData.head.copy(data = currentData.head.data ++ data, complete = complete)
    val nextData = currentChunk :: currentData.tail
    val result = if(currentChunk.data.size >= maxSize && !complete)
      createInitialChunk() :: nextData
    else nextData
    if(complete) result.reverse else result
  }

  /**
    * Creates an initial chunk of meta data.
    *
    * @return the initial chunk
    */
  private def createInitialChunk(): MetaDataChunk =
    MetaDataChunk(mediumID, Map.empty, complete = false)

  /**
    * Creates the next chunk with the data contained in the passed in map.
    * This chunk is passed to the caller to be further processed.
    *
    * @param data the meta data that belongs to the next chunk
    * @return the chunk
    */
  private def createNextChunk(data: Map[String, MediaMetaData]): MetaDataChunk =
    MetaDataChunk(mediumID, data, isComplete)
}
