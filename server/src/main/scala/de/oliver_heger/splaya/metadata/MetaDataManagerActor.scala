/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.splaya.metadata

import java.nio.file.Path

import akka.actor.{ActorLogging, Actor, ActorRef, Props}
import de.oliver_heger.splaya.config.ServerConfig
import de.oliver_heger.splaya.media.{MediaFile, MediaScanResult, MediumID}
import de.oliver_heger.splaya.utils.ChildActorFactory

object MetaDataManagerActor {

  /**
   * A message supported by ''MetaDataManagerActor'' that queries for the meta
   * data of a specific medium.
   *
   * The actor returns the meta data currently available in form of a
   * ''MetaDataChunk'' message. If the ''registerAsListener'' flag is
   * '''true''', the sending actor will be notified when more meta data for
   * this medium becomes available until processing is complete.
   * @param mediumID ID of the medium in question (as returned from the media
   *                 manager actor)
   * @param registerAsListener flag whether the sending actor should be
   *                           registered as listener if meta data for this
   *                           medium is incomplete yet
   */
  case class GetMetaData(mediumID: String, registerAsListener: Boolean)

  /**
   * A message sent as answer for a ''GetMetaData'' request.
   *
   * This message contains either complete meta data of an medium (if it is
   * already available at request time) or a chunk which was recently updated.
   * The ''complete'' property defines whether the meta data has already been
   * fully fetched.
   *
   * @param mediumID the ID of the medium
   * @param data actual meta data; the map contains the URIs of media files as
   *             keys and the associated meta data as values
   * @param complete a flag whether now complete meta data is available for
   *                 this medium (even if this chunk may not contain the full
   *                 data)
   */
  case class MetaDataChunk(mediumID: String, data: Map[String, MediaMetaData], complete: Boolean)

  /**
   * A message sent as answer for a ''GetMetaData'' request if the specified
   * medium is not known.
   *
   * This actor should be in sync with the media manager actor. So all media
   * returned from there can be queried here. If the medium ID passed with a
   * ''GetMetaData'' cannot be resolved, an instance of this message is
   * returned.
   * @param mediumID the ID of the medium in question
   */
  case class UnknownMedium(mediumID: String)

  /**
   * Tells the meta data manager actor to remove a listener for the specified
   * medium.
   *
   * With this message a listener that has been registered via a
   * ''GetMetaData'' message can be explicitly removed. This is normally not
   * necessary because medium listeners are removed automatically when the
   * meta data for a medium is complete. However, if a client application is
   * about to terminate, it is good practice to remove listeners for media that
   * are still processed. If the specified listener is not registered for this
   * medium, the processing of this message has no effect.
   *
   * @param mediumID the ID of the medium
   * @param listener the listener to be removed
   */
  case class RemoveMediumListener(mediumID: String, listener: ActorRef)

  /**
   * A message sent to registered completion listeners notifying them that the
   * full meta data for a medium is now available.
   *
   * Clients interested in the whole meta data library can register co-called
   * completion listeners. They are then notified whenever all meta data for a
   * medium has been extracted. After receiving this message, the meta data can
   * be requested in a second step. This is more efficient than receiving
   * chunks for updates.
   *
   * Typically, during a single meta data extraction operation a message of
   * this type is produced once for each medium discovered. The only exception
   * is the undefined medium which can appear on multiple source directory
   * structures. In this case, multiple messages may be produced.
   *
   * @param mediumID the ID of the medium that has been completed
   */
  case class MediumMetaDataCompleted(mediumID: String)

  /**
   * Tells the meta data manager actor to add a completion listener.
   *
   * Each time all meta data for a specific medium has been extracted, the
   * specified listener actor receives a [[MediumMetaDataCompleted]] message.
   *
   * @param listener the listener actor to be registered
   */
  case class AddCompletionListener(listener: ActorRef)

  /**
   * Tells the meta data manager actor to remove a completion listener.
   *
   * With this message completion listeners can be removed again. The listener
   * to remove is specified as payload of the message. If this actor is not
   * registered as completion listener, this message has no effect.
   *
   * @param listener the completion listener to be removed
   */
  case class RemoveCompletionListener(listener: ActorRef)

  private class MetaDataManagerActorImpl(config: ServerConfig) extends
  MetaDataManagerActor(config) with ChildActorFactory

  /**
   * Returns creation properties for an actor instance of this type.
   * @param config the server configuration object
   * @return creation properties for a new actor instance
   */
  def apply(config: ServerConfig): Props = Props(classOf[MetaDataManagerActorImpl], config)

  /** Constant for the undefined medium ID. */
  private val UndefinedMedium = ""

  /**
   * Converts the specified medium ID to a string. The string is the path
   * representation of the medium with a special treatment for the undefined
   * medium ID.
   * @param mid the medium ID
   * @return the corresponding string representation
   */
  private def mediumToString(mid: MediumID): String =
    mid.mediumDescriptionPath map (_.toString) getOrElse UndefinedMedium

  /**
   * Helper method for transforming a path to a string representation.
   * @param p the path
   * @return the string representing this path
   */
  private def pathToUri(p: Path): String = p.toString

  /**
   * An internally used helper class for storing and managing the meta data
   * of a medium.
   * @param mediumID the medium ID
   */
  private class MediumDataHandler(mediumID: String) {
    /**
     * A set with the names of all files in this medium. This is used to
     * determine whether all data has been fetched.
     */
    private val mediumPaths = collection.mutable.Set.empty[Path]

    /** The current data available for the represented medium. */
    private var currentData = createInitialChunk()

    /** Stores data for the next chunk. */
    private var nextChunkData = Map.empty[String, MediaMetaData]

    /**
     * Notifies this object that the specified list of media files is going to
     * be processed. The file paths are stored so that it can be figured out
     * when all meta data has been fetched.
     * @param files the files that are going to be processed
     */
    def expectMediaFiles(files: Seq[MediaFile]): Unit = {
      mediumPaths ++= files.map(_.path)
    }

    /**
     * Stores the specified result in this object. If the specified chunk size
     * is now reached or if the represented medium is complete, the passed in
     * function is invoked with a new chunk of data. It can then process the
     * chunk, e.g. notify registered listeners.
     * @param result the result to be stored
     * @param chunkSize the chunk size
     * @param f the function for processing a new chunk of data
     * @return a flag whether this medium is now complete (this value is
     *         returned explicitly so that it is available without having to
     *         evaluate the lazy meta data chunk expression)
     */
    def storeResult(result: MetaDataProcessingResult, chunkSize: Int)(f: (=> MetaDataChunk) =>
      Unit): Boolean = {
      mediumPaths -= result.path
      val complete = isComplete
      nextChunkData = nextChunkData + (pathToUri(result.path) -> result.metaData)

      if (nextChunkData.size >= chunkSize || complete) {
        f(createNextChunk(nextChunkData))
        currentData = updateCurrentResult(nextChunkData, complete)
        nextChunkData = Map.empty
        complete
      } else false
    }

    /**
     * Returns a flag whether all meta data for the represented medium has been
     * obtained.
     * @return a flag whether all meta data is available
     */
    def isComplete: Boolean = mediumPaths.isEmpty

    /**
     * Returns the meta data stored currently in this object.
     * @return the data managed by this object
     */
    def metaData: MetaDataChunk = currentData

    /**
     * Updates the current result object by adding the content of the given
     * map.
     * @param data the data to be added
     * @param complete the new completion status
     * @return the new data object to be stored
     */
    private def updateCurrentResult(data: Map[String, MediaMetaData], complete: Boolean):
    MetaDataChunk =
      currentData.copy(data = currentData.data ++ data, complete = complete)

    /**
     * Creates an initial chunk of meta data.
     * @return the initial chunk
     */
    private def createInitialChunk(): MetaDataChunk =
      MetaDataChunk(mediumID, Map.empty, complete = false)

    /**
     * Creates the next chunk with the data contained in the passed in map.
     * This chunk is passed to the caller to be further processed.
     * @param data the meta data that belongs to the next chunk
     * @return the chunk
     */
    private def createNextChunk(data: Map[String, MediaMetaData]): MetaDataChunk =
      MetaDataChunk(mediumID, data, isComplete)
  }

}

/**
 * The central actor class for managing meta data extraction.
 *
 * This actor is notified by the media manager actor when the content of a root
 * directory structure has been scanned; it then receives a
 * [[de.oliver_heger.splaya.media.MediaScanResult]] message. This message is
 * handled by creating a new child actor of type [[MediumProcessorActor]] which
 * is then told to extract all meta data for the files on this medium.
 *
 * The extracting of meta data over complex directory structures can be a time
 * consuming process; it may take a while until complete results are available.
 * Therefore, this manager actor takes a different approach for exposing
 * information to clients than the media manager actor (which may delay
 * responses until all information is there): it returns the information
 * currently available, but allows the caller to register itself as listener.
 * Listeners receive notifications when more meta data is available or when a
 * whole medium has been processed.
 *
 * There are two types of listeners. A meta data completion listener receives
 * notifications whenever a medium has been fully processed. At this time
 * the meta data for this medium can be queried, and complete data is
 * returned.
 *
 * A meta data medium listener is only interested for a specific medium. On the
 * first call to this actor it is checked whether the requested medium has
 * already been fully processed. If this is case, the meta data is returned
 * directly, and the caller is not registered as a listener. Otherwise, the
 * caller is sent the meta data currently available and is registered as
 * listener (if requested; this can be controlled by a flag). Listeners are
 * then notified when a configurable chunk of meta data becomes available. When
 * the monitored medium has been fully processed the listener registration is
 * removed automatically.
 *
 * More information about the messages supported by this actor and the overall
 * protocol can be found in the description of the message classes defined in
 * the companion object.
 *
 * @param config the central configuration object
 */
class MetaDataManagerActor(config: ServerConfig) extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import MetaDataManagerActor._

  /**
   * A map for storing the extracted meta data for all media.
   */
  private val mediaMap = collection.mutable.Map.empty[String, MediumDataHandler]

  /** Stores the listeners registered for specific media. */
  private val mediumListeners = collection.mutable.Map.empty[String, List[ActorRef]]

  /** A list with the currently registered completion listeners. */
  private var completionListeners = List.empty[ActorRef]

  override def receive: Receive = {
    case sr: MediaScanResult =>
      val processor = createChildActor(MediumProcessorActor(sr, config))
      processor ! ProcessMediaFiles
      sr.mediaFiles foreach prepareHandlerForMedium

    case result: MetaDataProcessingResult =>
      val mediumID = mediumToString(result.mediumID)
      mediaMap get mediumID foreach processMetaDataResult(mediumID, result)
      log.info("Received MetaDataProcessingResult for {}.", result.path)

    case GetMetaData(mediumID, registerAsListener) =>
      val optData = mediaMap get mediumID map (_.metaData)
      sender ! optData.getOrElse(UnknownMedium(mediumID))

      if (registerAsListener && !optData.map(_.complete).getOrElse(true)) {
        val newListeners = sender() :: mediumListeners.getOrElse(mediumID, Nil)
        mediumListeners(mediumID) = newListeners
      }

    case RemoveMediumListener(mediumID, listener) =>
      val listeners = mediumListeners.getOrElse(mediumID, Nil)
      val updatedListeners = listeners filterNot (_ == listener)
      if (updatedListeners.nonEmpty) {
        mediumListeners(mediumID) = updatedListeners
      } else {
        mediumListeners remove mediumID
      }

    case AddCompletionListener(listener) =>
      completionListeners = listener :: completionListeners

    case RemoveCompletionListener(listener) =>
      completionListeners = completionListeners filterNot (_ == listener)
  }

  /**
   * Prepares the handler object for a medium. If this medium is already known,
   * the existing handler is updated. Otherwise (which should be the default
   * case except for the undefined medium ID), a new handler object is
   * initialized.
   * @param e an entry from the map of media files from a scan result object
   */
  private def prepareHandlerForMedium(e: (MediumID, List[MediaFile])): Unit = {
    val mediumID = mediumToString(e._1)
    val handler = mediaMap.getOrElseUpdate(mediumID, new MediumDataHandler(mediumID))
    handler expectMediaFiles e._2
  }

  /**
   * Processes a meta data result that has been produced by a child actor. The
   * result is added to the responsible handler. If necessary, listeners are
   * notified.
   * @param mediumID the ID of the affected medium
   * @param result the processing result
   * @param handler the handler for this medium
   */
  private def processMetaDataResult(mediumID: String,
                                    result: MetaDataProcessingResult)(handler: MediumDataHandler)
  : Unit = {
    if (handler.storeResult(result, config.metaDataUpdateChunkSize)(handleCompleteChunk(mediumID)
    )) {
      mediumListeners remove mediumID
      if (completionListeners.nonEmpty) {
        val msg = MediumMetaDataCompleted(mediumID)
        completionListeners foreach (_ ! msg)
      }
    }
  }

  /**
   * Handles a new chunk of mata data that became available. This method
   * notifies the listeners registered for this medium.
   * @param mediumID the ID of the affected medium
   * @param chunk the chunk
   */
  private def handleCompleteChunk(mediumID: String)(chunk: => MetaDataChunk): Unit = {
    mediumListeners get mediumID foreach { l =>
      val chunkMsg = chunk
      l foreach (_ ! chunkMsg)
    }
  }
}
