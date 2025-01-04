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

package de.oliver_heger.linedj.archiveunion

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.{MediaMetadata, MetadataChunk}
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingResult, MetadataProcessingSuccess}

import scala.collection.immutable.Seq

/**
  * An internally used helper class for storing and managing the metadata
  * of a medium.
  *
  * When the metadata for a file becomes available this class stores it in an
  * internal map and also creates corresponding chunks. The way the URIs for
  * files are generated depends on the represented medium. Therefore, this
  * algorithm is deferred to concrete subclasses.
  *
  * @param mediumID the medium ID
  */
private class MediumDataHandler(mediumID: MediumID):
  /**
    * A set with the URIs of all files in this medium. This is used to
    * determine whether all data has been fetched.
    */
  private val mediumUris = collection.mutable.Set.empty[MediaFileUri]

  /** The current data available for the represented medium. */
  protected var currentData: List[MetadataChunk] = initialData()

  /** Stores data for the next chunk. */
  protected var nextChunkData = Map.empty[String, MediaMetadata]

  /**
    * Notifies this object that the specified list of media files is going to
    * be processed. The file paths are stored so that it can be figured out
    * when all metadata has been fetched.
    *
    * @param files the files that are going to be processed
    */
  def expectMediaFiles(files: Iterable[MediaFileUri]): Unit =
    mediumUris ++= files

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
    *         evaluate the lazy metadata chunk expression)
    */
  def storeResult(result: MetadataProcessingResult, chunkSize: Int, maxChunkSize: Int)
                 (f: (=> MetadataChunk) => Unit): Boolean =
    mediumUris -= result.uri
    val complete = isComplete
    nextChunkData = updateNextChunkData(result)

    processNextChunkData(chunkSize, maxChunkSize, complete, f)

  /**
    * Returns a flag whether all metadata for the represented medium has been
    * obtained.
    *
    * @return a flag whether all metadata is available
    */
  def isComplete: Boolean = mediumUris.isEmpty

  /**
    * Returns the metadata stored currently in this object.
    *
    * @return the data managed by this object
    */
  def metadata: Seq[MetadataChunk] = currentData

  /**
    * Resets the data of this handler. This method can be called at the start
    * of another scan operation.
    */
  def reset(): Unit =
    currentData = initialData()
    nextChunkData = Map.empty

  /**
    * Returns the metadata for the specified URI if available.
    *
    * @param uri the URI of the file in question
    * @return an ''Option'' with the metadata for this file
    */
  def metadataFor(uri: String): Option[MediaMetadata] =
    nextChunkData.get(uri) orElse:
      currentData.find(_.data.contains(uri)) map (_.data(uri))

  /**
    * Extracts the URI to be used when storing the specified result. The URI is
    * different for the global undefined list.
    *
    * @param result the metadata result
    * @return the URI to be used for the represented file
    */
  protected def extractUri(result: MetadataProcessingSuccess): String = result.uri.uri

  /**
    * Updates the current result object by adding the content of the given
    * map.
    *
    * @param data the data to be added
    * @param complete the new completion status
    * @param maxSize the maximum number of entries in a chunk
    * @return the new data object to be stored
    */
  protected def updateCurrentResult(data: Map[String, MediaMetadata], complete: Boolean,
                                    maxSize: Int): List[MetadataChunk] =
    val currentChunk = currentData.head.copy(data = currentData.head.data ++ data,
      complete = complete)
    val nextData = currentChunk :: currentData.tail
    val result = if currentChunk.data.size >= maxSize && !complete then
      createInitialChunk() :: nextData
    else nextData
    if complete then result.reverse else result

  /**
    * Processes the currently built-up chunk of data. If the chunk has now
    * reached its target size or if the scan is complete, it is added to the
    * results, and the chunk processing function is invoked.
    *
    * @param chunkSize    the chunk size
    * @param maxChunkSize the maximum size of chunks
    * @param f            the function for processing a new chunk of data
    * @param complete     flag whether this medium is now complete
    * @return the complete flag
    */
  protected def processNextChunkData(chunkSize: Int, maxChunkSize: Int, complete: Boolean,
                                     f: (=> MetadataChunk) => Unit): Boolean =
    if nextChunkData.size >= chunkSize || complete then
      f(MetadataChunk(mediumID, nextChunkData, complete))
      currentData = updateCurrentResult(nextChunkData, complete, maxChunkSize)
      nextChunkData = Map.empty
      complete
    else false

  /**
    * Updates the map with data for the next chunk with a received result.
    *
    * @param result the result
    * @return the updated next chunk data
    */
  private def updateNextChunkData(result: MetadataProcessingResult): Map[String, MediaMetadata] =
    result match
      case result@MetadataProcessingSuccess(_, _, metadata) =>
        nextChunkData + (extractUri(result) -> metadata)
      case _ => nextChunkData

  /**
    * Creates an initial chunk of metadata.
    *
    * @return the initial chunk
    */
  private def createInitialChunk(): MetadataChunk =
    MetadataChunk(mediumID, Map.empty, complete = false)

  /**
    * Creates empty initial data for this handler.
    *
    * @return a list with a single empty chunk of data
    */
  private def initialData(): List[MetadataChunk] =
    List(createInitialChunk())
