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

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{MediaMetaData, MetaDataChunk, MetaDataState}
import de.oliver_heger.linedj.shared.archive.union.{MediaFileUriHandler, MetaDataProcessingResult}

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
private class MediumDataHandler(mediumID: MediumID) {
  /**
    * A set with the names of all files in this medium. This is used to
    * determine whether all data has been fetched.
    */
  private val mediumPaths = collection.mutable.Set.empty[String]

  /** The current data available for the represented medium. */
  protected var currentData: List[MetaDataChunk] = initialData()

  /** Stores data for the next chunk. */
  protected var nextChunkData = Map.empty[String, MediaMetaData]

  /**
    * Notifies this object that the specified list of media files is going to
    * be processed. The file paths are stored so that it can be figured out
    * when all meta data has been fetched.
    *
    * @param files the files that are going to be processed
    */
  def expectMediaFiles(files: Iterable[FileData]): Unit = {
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
    mediumPaths -= result.path.toString
    val complete = isComplete
    nextChunkData = nextChunkData + (extractUri(result) -> result.metaData)

    processNextChunkData(chunkSize, maxChunkSize, complete, f)
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
    * Returns a ''MetaDataState'' object with statistical information about
    * the songs managed by this object. This is used for instance when the
    * medium is removed and the statistics of the archive has to be updated
    * accordingly.
    *
    * @return an object with statistics about the represented medium
    */
  def calculateStatistics(): MetaDataState = {
    val allMetaData = currentData flatMap (_.data.values)
    val initState = MetaDataState(mediaCount = 1, songCount = 0, size = 0,
      duration = 0, scanInProgress = false)

    allMetaData.foldLeft(initState)((s, d) => s + d)
  }

  /**
    * Resets the data of this handler. This method can be called at the start
    * of another scan operation.
    */
  def reset(): Unit = {
    currentData = initialData()
    nextChunkData = Map.empty
  }

  /**
    * Extracts the URI to be used when storing the specified result. The URI is
    * different for the global undefined list.
    *
    * @param result the meta data result
    * @return the URI to be used for the represented file
    */
  protected def extractUri(result: MetaDataProcessingResult): String = result.uri

  /**
    * Updates the current result object by adding the content of the given
    * map.
    *
    * @param data the data to be added
    * @param complete the new completion status
    * @param maxSize the maximum number of entries in a chunk
    * @return the new data object to be stored
    */
  protected def updateCurrentResult(data: Map[String, MediaMetaData], complete: Boolean,
                                    maxSize: Int): List[MetaDataChunk] = {
    val currentChunk = currentData.head.copy(data = currentData.head.data ++ data,
      complete = complete)
    val nextData = currentChunk :: currentData.tail
    val result = if(currentChunk.data.size >= maxSize && !complete)
      createInitialChunk() :: nextData
    else nextData
    if(complete) result.reverse else result
  }

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
                                     f: (=> MetaDataChunk) => Unit): Boolean = {
    if (nextChunkData.size >= chunkSize || complete) {
      f(MetaDataChunk(mediumID, nextChunkData, complete))
      currentData = updateCurrentResult(nextChunkData, complete, maxChunkSize)
      nextChunkData = Map.empty
      complete
    } else false
  }

  /**
    * Creates an initial chunk of meta data.
    *
    * @return the initial chunk
    */
  private def createInitialChunk(): MetaDataChunk =
    MetaDataChunk(mediumID, Map.empty, complete = false)

  /**
    * Creates empty initial data for this handler.
    *
    * @return a list with a single empty chunk of data
    */
  private def initialData(): List[MetaDataChunk] =
    List(createInitialChunk())
}

/**
  * A specialized ''MediaMetaData'' implementation responsible for the global
  * undefined medium. This implementation is slightly different from the
  * standard implementation. It uses a different mechanism to generate URIs.
  * It also has some functionality to update its content because parts of the
  * undefined medium list may be removed dynamically, or the list has to be
  * cleared when a new scan starts.
  */
private class UndefinedMediumDataHandler extends MediumDataHandler(MediumID.UndefinedMediumID) {
  /**
    * @inheritdoc This implementation returns always '''false'''. The global
    *             undefined list is explicitly completed at the end of a
    *             scan operation.
    */
  override def isComplete: Boolean = false

  /**
    * Removes all data managed by the specified archive component. This is
    * necessary when this component is no longer available.
    *
    * @param archiveComponentID the ID of the component in question
    * @param maxChunkSize       the maximum number of entries per chunk
    * @return a flag whether this handler still contains data after the remove
    *         operation
    */
  def removeDataFromComponent(archiveComponentID: String, maxChunkSize: Int): Boolean = {
    val updatedData = currentData.flatMap(_.data.filterNot { e =>
      isFromArchiveComponent(archiveComponentID, e._1)
    })
    val orgCount = currentData.foldLeft(0)((cnt, chunk) => cnt + chunk.data.size)
    if (orgCount != updatedData.size) {
      currentData = regroup(updatedData, maxChunkSize)
    }
    updatedData.nonEmpty
  }

  /**
    * Completes a scan operation. If there are pending results in a
    * not-yet-completed chunk, they are added to the final results. The
    * function for processing the new chunk of data is invoked (even if the
    * chunk is empty; because now the ''complete'' flag is set).
    *
    * @param f the function for processing the new chunk of data
    */
  def complete(f: (=> MetaDataChunk) => Unit): Unit = {
    processNextChunkData(0, Integer.MAX_VALUE, complete = true, f)
  }

  /**
    * Creates chunks of the correct size for the specified list of data. This
    * method can be called if the data has been changed.
    *
    * @param data         the (already updated) data
    * @param maxChunkSize the maximum number of entries per chunk
    * @return the list of chunks holding the updated data
    */
  private def regroup(data: List[(String, MediaMetaData)], maxChunkSize: Int):
  List[MetaDataChunk] = {
    val chunks = data.grouped(maxChunkSize).toList
    chunks.map(e => MetaDataChunk(MediumID.UndefinedMediumID,
      e.toMap, complete = e eq chunks.last))
  }

  /**
    * Extracts the URI to be used when storing the specified result. The URI is
    * different for the global undefined list.
    *
    * @param result the meta data result
    * @return the URI to be used for the represented file
    */
  override protected def extractUri(result: MetaDataProcessingResult): String =
    MediaFileUriHandler.generateUndefinedMediumUri(result.mediumID, result.uri)

  /**
    * Checks whether the specified URI belongs to the given archive component.
    *
    * @param archiveComponentID the archive component ID
    * @param uri                the URI
    * @return a flag whether this URI is managed by this component
    */
  private def isFromArchiveComponent(archiveComponentID: String, uri: String):
  Boolean =
    MediaFileUriHandler.extractRefUri(uri).exists(_.componentID == archiveComponentID)
}
