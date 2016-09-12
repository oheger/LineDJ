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

import java.io.IOException
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, MediumID}
import de.oliver_heger.linedj.utils.ChildActorFactory

object MediumProcessorActor {

  private class MediumProcessorActorImpl(data: EnhancedMediaScanResult, config: MediaArchiveConfig)
    extends MediumProcessorActor(data, config) with ChildActorFactory

  /**
   * Returns creation properties for a new actor instance.
    *
    * @param data data about the files to be processed
   * @param config the server configuration
   * @return properties for creating a new actor instance
   */
  def apply(data: EnhancedMediaScanResult, config: MediaArchiveConfig): Props =
    Props(classOf[MediumProcessorActorImpl], data, config)

  /**
    * Produces a sequence with all paths from the specified message that must
    * be processed by this actor.
    *
    * @param procMsg the ''ProcessMediaFiles'' message
    * @return a sequence with all paths to be processed
    */
  private def pathsToBeProcessed(procMsg: ProcessMediaFiles): List[(Path, MediaFileData)] =
    associateWithMediumID(procMsg.mediumID, procMsg.files) map (t => (t._1.path, MediaFileData(t
      ._1, t._2)))

  /**
    * Creates a reverse mapping from paths to URIs based on the URI path
    * mapping obtained from an enhanced scan result. This is necessary to map
    * the paths processed by ''MediumProcessorActor'' back to the logic URIs.
    *
    * @param uriPathMapping the URI path mapping
    * @return the path URI mapping
    */
  private def createPathUriMapping(uriPathMapping: Map[String, FileData]): Map[Path, String] =
    uriPathMapping map(e => (e._2.path, e._1))

  /**
   * Produces a list of pairs that assigns each file to its medium.
    *
   * @param mediumID the medium ID
   * @param files the list of files
   * @return a list of pairs with the associated medium paths
   */
  private def associateWithMediumID(mediumID: MediumID, files: List[FileData]): List[
    (FileData, MediumID)] = {
    val mediumPathList = List.fill(files.size)(mediumID)
    files zip mediumPathList
  }

  /**
   * An internally used data class for storing information about the files to
   * be processed.
   *
   * Normally, the processing is done based on the path to the file. For some
   * processing steps, however, additional information is required. This is
   * stored in instances of this class.
   *
   * @param file the original ''FileData'' object
   * @param mediumID the ID of the medium the file belongs to
   */
  private case class MediaFileData(file: FileData, mediumID: MediumID)
}

/**
 * An actor class for extracting the meta data for all media files contained on
 * a medium.
 *
 * When the media server starts up it scans a configurable set of root paths
 * for media files. In a second step, meta data has to be extracted from these
 * files. For each root path, an instance of this actor is created. Thus,
 * multiple paths can be processed in parallel.
 *
 * Meta data extraction for media files is a complex process that requires each
 * file to be read and the data to be processed in various ways. A bunch of
 * actors of different types is involved in this process. Instances of these
 * actor types are mostly created dynamically for the single media files to be
 * processed; so the system adapts dynamically to the current work-load. Only
 * the main reader actors are created initially; their number is defined by a
 * configuration setting. The main responsibility of this actor is to manage
 * all helper actors taking part in the meta data extraction process. They are
 * all children of this actor.
 *
 * The management of multiple actors has the consequence that this actor has to
 * react on a bunch of different messages. However, in most cases, message
 * handling is not complicated: the message just has to be passed to the
 * correct managed actor. Some helper objects are used for creating specific
 * child actors dynamically. For testing purposes, it is possible to pass such
 * objects to the constructor.
 *
 * This actor expects a number of constructor arguments defining the media
 * files to be processed and other meta data related to the extraction
 * process. The actual processing is triggered when a ''Process'' message is
 * received. The sender of this message will also be sent processing results.
 * Multiple messages of this type are ignored.
 *
 * @param data data about the files to be processed
 * @param config the media server configuration
 * @param optMp3ProcessorMap optional map for mp3 processor actors
 * @param optId3v2ProcessorMap optional map for ID3v2 processor actors
 * @param optId3v1ProcessorMap optional map for ID3v1 processor actors
 * @param optCollectorMap optional map for meta data collectors
 */
class MediumProcessorActor(data: EnhancedMediaScanResult, config: MediaArchiveConfig,
                           optMp3ProcessorMap: Option[ProcessorActorMap],
                           optId3v2ProcessorMap: Option[ProcessorActorMap],
                           optId3v1ProcessorMap: Option[ProcessorActorMap],
                           optCollectorMap: Option[MetaDataCollectorMap])
  extends Actor {
  me: ChildActorFactory =>

  import MediumProcessorActor._

  /** The central meta data extraction context. */
  private val extractionContext = new MetaDataExtractionContext(self, config)

  /** The processor actor map for ID3v2 processor actors. */
  private[metadata] val id3v2ProcessorMap = processorActorMap(optId3v2ProcessorMap,
    classOf[ID3FrameProcessorActor])

  /** The processor actor map for MP3 data processor actors. */
  private[metadata] val mp3ProcessorMap = processorActorMap(optMp3ProcessorMap,
    classOf[Mp3DataProcessorActor])

  /** The processor actor map for ID3v1 processor actors. */
  private[metadata] val id3v1ProcessorMap = processorActorMap(optId3v1ProcessorMap,
    classOf[ID3v1FrameProcessorActor])

  /** The map for meta data collectors. */
  private[metadata] val collectorMap = optCollectorMap getOrElse new MetaDataCollectorMap

  /** A map with information about the files currently processed. */
  private val currentProcessingData = collection.mutable.Map.empty[Path, MediaFileData]

  /**
   * A map keeping track which reader actor processes which file. This is
   * needed for error handling: If a reader fails, cleanup has to be performed,
   * and the currently processed file marked as finished.
   */
  private val readerActorMap = collection.mutable.Map.empty[ActorRef, Path]

  /**
    * A list with the reader actors that are free to process another request.
    */
  private var availableReaderActors = List.empty[ActorRef]

  /**
    * Stores a mapping from file paths to corresponding URIs. This is required
    * to generate the result objects.
    */
  private val pathUriMapping = createPathUriMapping(data.fileUriMapping)

  /** A list with all media files to be processed. */
  private var mediaFilesToProcess = List.empty[(Path, MediaFileData)]

  /** An option for the meta data manager actor which receives all results. */
  private var metaDataManager: Option[ActorRef] = None

  /**
   * Constructor to be used for default actor creation.
    *
   * @param data data about the files to be processed
   * @param config the media server configuration
   */
  def this(data: EnhancedMediaScanResult, config: MediaArchiveConfig) =
    this(data, config, None, None, None, None)

  override val supervisorStrategy = OneForOneStrategy() {
    case _: IOException => Stop
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    availableReaderActors = List.fill(processorCountFromConfig)(createChildReaderActor())
  }

  override def receive: Receive = {
    case p: ProcessMediaFiles =>
      if (metaDataManager.isEmpty) {
        metaDataManager = Some(sender())
      }
      mediaFilesToProcess = pathsToBeProcessed(p) ::: mediaFilesToProcess
      triggerProcessing()

    case msg: ProcessID3FrameData if validPath(msg.path) =>
      id3v2ProcessorMap.getOrCreateActorFor(msg.path, this) ! msg
      collectorMap.getOrCreateCollector(mediaFileForPath(msg.path)).expectID3Data(msg.frameHeader
        .version)
      if (msg.lastChunk) {
        id3v2ProcessorMap removeItemFor msg.path
      }

    case msg: ProcessMp3Data =>
      delegateMessageFromReader(msg, msg.path)

    case msg: ID3FrameMetaData if validPath(msg.path) =>
      handleProcessingResult(msg.path)(_.addID3Data(msg))

    case ID3v1MetaData(path, metaData) =>
      if (id3v1ProcessorMap.removeItemFor(path).isDefined) {
        handleProcessingResult(path)(_.setID3v1MetaData(metaData))
      }

    case msg: Mp3MetaData =>
      if (mp3ProcessorMap.removeItemFor(msg.path).isDefined) {
        handleProcessingResult(msg.path)(_.setMp3MetaData(msg))
      }

    case msg: MediaFileRead =>
      readerActorMap remove sender()
      processNextFile(sender())
      delegateMessageFromReader(msg, msg.path)

    case t: Terminated =>
      readerActorMap remove t.actor foreach handleTerminatedReadActor
  }

  /**
   * Creates a new child actor for reading media files.
    *
   * @return the new child reader actor
   */
  private def createChildReaderActor(): ActorRef = {
    val reader = createChildActor(Mp3FileReaderActor(extractionContext))
    context watch reader
    reader
  }

  /**
   * Delegates a message from the file reader actor to the corresponding
   * processor actors.
    *
   * @param msg the message
   * @param path the affected path
   */
  private def delegateMessageFromReader(msg: Any, path: Path): Unit = {
    if (validPath(path)) {
      mp3ProcessorMap.getOrCreateActorFor(path, this) ! msg
      id3v1ProcessorMap.getOrCreateActorFor(path, this) ! msg
    }
  }

  /**
   * Starts reading a new media file. Some status variables are updated
   * accordingly.
    *
   * @param reader the reader actor
   * @param fileInfo the object for the file to be read
   */
  private def initiateFileRead(reader: ActorRef, fileInfo: (Path, MediaFileData)): Unit = {
    reader ! ReadMediaFile(fileInfo._1)
    currentProcessingData += fileInfo
    readerActorMap += (reader -> fileInfo._1)
  }

  /**
   * Tells the specified actor to start processing of the next file in the list
   * (if available).
    *
   * @param reader the reader actor that just finished processing
   */
  private def processNextFile(reader: ActorRef): Unit = {
    availableReaderActors = reader :: availableReaderActors
    triggerProcessing()
  }

  /**
    * Determines the next files to be processed and returns the updated state.
    * This method is called when new data to be processed arrives or processing
    * of a file is complete. If possible, reader actors are passed new files to
    * be processed.
    *
    * @param readers a list with the reader actors currently available
    * @param files   a list with the files to be processed
    * @return a tuple with processing information and the updated lists
    */
  private def nextProcessingData(readers: List[ActorRef], files: List[(Path, MediaFileData)]):
  (List[(ActorRef, (Path, MediaFileData))], List[ActorRef], List[(Path, MediaFileData)]) = {
    val procData = readers zip files
    (procData, readers drop procData.size, files drop procData.size)
  }

  /**
    * Starts processing of media files if possible. If free reader actors and
    * media files are available, processing can continue.
    */
  private def triggerProcessing(): Unit = {
    val (procData, readers, files) = nextProcessingData(availableReaderActors, mediaFilesToProcess)
    procData foreach(d => initiateFileRead(d._1, d._2))
    availableReaderActors = readers
    mediaFilesToProcess = files
  }

  /**
   * Handles a meta data processing result that came in. This method checks
   * whether now all meta data for the specified file is available. If so, the
   * meta data manager is notified. The sending actor is stopped.
    *
   * @param p the path to the file the meta data belongs to
   * @param f a function for updating the collected meta data
   */
  private def handleProcessingResult(p: Path)(f: MetaDataPartsCollector => Option[MediaMetaData])
  : Unit = {
    for {manager <- metaDataManager
         metaData <- f(collectorMap.getOrCreateCollector(mediaFileForPath(p)))
    } {
      sendProcessingResult(manager, p, metaData)
    }
    context stop sender()
  }

  /**
   * Obtains the ''FileData'' object associated with the given path. This
   * method can be used to convert a path (which is used by most processor
   * classes) back to a ''FileData'' object with all information available
   * about the file. Note: When this method is called it has already been
   * verified that the path is valid.
    *
   * @param p the path
   * @return the associated ''FileData'' object
   */
  private def mediaFileForPath(p: Path): FileData = {
    currentProcessingData(p).file
  }

  /**
    * Sends a processing result message to the manager actor and updates some
    * internal fields indicating that the given path has now been processed.
    *
    * @param manager  the meta data manager actor
    * @param p        the path
    * @param metaData the meta data to be sent
    */
  private def sendProcessingResult(manager: ActorRef, p: Path, metaData: MediaMetaData): Unit = {
    manager ! MetaDataProcessingResult(p, currentProcessingData(p).mediumID, pathUriMapping(p),
      metaData)
    collectorMap removeItemFor p
    currentProcessingData -= p
  }

  /**
   * Handles a crashed read actor. In this case, some cleanup has to be done
   * for the affected file. An empty result message is sent to the manager
   * actor. A new child reader actor is created replacing the crashed one.
    *
   * @param p the path of the file processed by the crashed actor
   */
  private def handleTerminatedReadActor(p: Path): Unit = {
    List(mp3ProcessorMap, id3v1ProcessorMap, id3v2ProcessorMap) foreach { m =>
      m removeItemFor p foreach context.stop
    }
    metaDataManager foreach (sendProcessingResult(_, p, MediaMetaData()))
    processNextFile(createChildReaderActor())
  }

  /**
   * Checks whether the given path is valid, i.e. it is currently processed.
    *
   * @param path the path to be checked
   * @return a flag whether this is a valid path
   */
  private def validPath(path: Path) = currentProcessingData contains path

  /**
   * Obtains the ''ProcessorActorMap'' for a specific actor class. If it has
   * been defined explicitly, it is used. Otherwise, a new map is created based
   * on the given parameters.
    *
   * @param optMap an option for the map
   * @param actorClass the actor class
   * @return the resulting ''ProcessorActorMap''
   */
  private def processorActorMap(optMap: Option[ProcessorActorMap], actorClass: Class[_]):
  ProcessorActorMap =
    optMap getOrElse new ProcessorActorMap(Props(actorClass, extractionContext))

  /**
   * Obtains the number of reader actors from the configuration. Handles an
   * unknown root path.
    *
   * @return the number of processing actors for the path to be processed
   */
  private def processorCountFromConfig: Int = {
    config.rootFor(data.scanResult.root) map (_.processorCount) getOrElse 1
  }
}
