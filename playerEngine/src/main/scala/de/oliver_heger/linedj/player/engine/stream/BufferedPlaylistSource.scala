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

package de.oliver_heger.linedj.player.engine.stream

import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink, Source}
import org.apache.pekko.stream.stage.*
import org.apache.pekko.util.{ByteString, Timeout}

import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * A module implementing a file-based buffer for audio sources in a playlist.
  *
  * This module provides a function that turns an ordinary source for a
  * playlist into a source that buffers audio data on the file system first
  * before it is played. Buffering is done by creating files in a configurable
  * folder with a configurable size. The implementation creates two of such
  * files and populates them with the audio sources in the playlist as long as
  * the configured maximum size is not reached. Downstream processing is then
  * based on reading data from the first of these files. When the file has been
  * read completely, it is deleted, and reading of the next file starts. At
  * that time, there is again space in the buffer, and a new file is created
  * and populated with data from audio sources. So, while audio processing
  * (typically playback) takes place, the buffer is filled up again.
  */
object BufferedPlaylistSource:
  /**
    * A default name for this source. This is used for the configuration if no
    * other name was specified. The name is used to derive the names of some
    * resources (especially actors) created dynamically.
    */
  final val DefaultSourceName = "bufferedSource"

  /**
    * A timeout value to be used when actually no timeout is required from the
    * business logic point of view, but syntactically one is needed.
    */
  private given InfiniteTimeout: Timeout = 30.days

  /**
    * Constant for the default chunk size when reading from a buffer file.
    */
  private val ReadChunkSize = 16384

  /**
    * Constant for a special data chunks that indicates the end of the current
    * stream. This is used by the bridge sources and sinks to manage the
    * lifecycle of streams.
    */
  private val EndOfStreamChunk: DataChunkResponse.DataChunk =
    DataChunkResponse.DataChunk(ByteString.empty, Int.MaxValue)

  /** Pattern for generating names for buffer files. */
  private val BufferFilePattern = "buffer%02d.dat"

  /**
    * A function for providing a [[Sink]] for writing to a file in the buffer.
    * Such a function can be passed to a buffered source in its configuration.
    * It is then invoked whenever a new file in the buffer needs to be created.
    */
  type BufferSinkFunc = Path => Sink[ByteString, Future[Any]]

  /**
    * A function for providing a [[Source]] for reading from a file in the
    * buffer. Analogously to [[BufferSinkFunc]], such a function can be passed
    * to the configuration of a buffered source to customize the reading from
    * the buffer.
    */
  type BufferSourceFunc = Path => Source[ByteString, Any]

  /**
    * A function to delete buffer files. It is invoked every time a file in the
    * buffer has been fully processed and can be deleted.
    */
  type BufferDeleteFunc = Path => Unit

  /**
    * Definition of a trait that is used for the sources exposed downstream by
    * the buffered source. It allows resolving the source from the buffer. For
    * this resolve operation, a lot of internal details about the way buffering
    * is implemented are required. The main purpose of this trait is to hide
    * this complexity and to prevent that internal data types need to be
    * exposed.
    *
    * @tparam SRC the type of the sources of audio streams
    */
  trait SourceInBuffer[SRC]:
    /**
      * Returns the original source of the current audio stream.
      *
      * @return the original source
      */
    def originalSource: SRC

    /**
      * Resolves this source by performing the necessary steps to open a stream
      * that loads data from buffer files.
      *
      * @return a ''Future'' with the resolved source
      */
    def resolveSource(): Future[AudioStreamPlayerStage.AudioStreamSource]
  end SourceInBuffer

  /**
    * A class defining the configuration of a [[BufferedPlaylistSource]].
    *
    * @param streamPlayerConfig the configuration for playing audio streams;
    *                           this is required in order to retrieve data
    *                           sources for stream elements
    * @param bufferFolder       the folder in which to create buffer files
    * @param bufferFileSize     the maximum size of each buffer file
    * @param bufferSinkFunc     a function to obtain a [[Sink]] to files in the
    *                           buffer
    * @param bufferSourceFunc   a function to obtain a [[Source]] to read a
    *                           file in the buffer
    * @param bufferDeleteFunc   a function to delete a file in the buffer
    * @param sourceName         a name of this source; based on this name, the
    *                           names of some resources (typically actors)
    *                           created dynamically are derived; so if multiple
    *                           sources are active at the same time, the names
    *                           need to be changed to be unique
    * @tparam SRC the type of the elements in the playlist
    * @tparam SNK the type expected by the sink of the stream
    */
  case class BufferedPlaylistSourceConfig[SRC, SNK](streamPlayerConfig:
                                                    AudioStreamPlayerStage.AudioStreamPlayerConfig[SRC, SNK],
                                                    bufferFolder: Path,
                                                    bufferFileSize: Int,
                                                    bufferSinkFunc: BufferSinkFunc = defaultBufferSink,
                                                    bufferSourceFunc: BufferSourceFunc = defaultBufferSource,
                                                    bufferDeleteFunc: BufferDeleteFunc = defaultBufferDelete,
                                                    sourceName: String = DefaultSourceName)

  /**
    * Creates a new [[BufferedPlaylistSource]] that wraps a given source. This
    * means that all the audio sources provided by this source are passed
    * through the configured buffer. In downstream, they can be obtained via a
    * special [[AudioStreamPlayerStage.SourceResolverFunc]]. A configuration
    * created by the [[mapConfig mapConfig]] function contains such a resolver
    * function.
    *
    * @param config the configuration for the buffered source
    * @param source the source to wrap
    * @param system the actor system
    * @tparam SRC the type of elements in the playlist
    * @tparam SNK the type expected by the sink of audio streams
    * @tparam MAT the materialized type of the source
    * @return the new buffered source
    */
  def apply[SRC, SNK, MAT](config: BufferedPlaylistSourceConfig[SRC, SNK],
                           source: Source[SRC, MAT])
                          (using system: classic.ActorSystem): Source[SourceInBuffer[SRC], MAT] =
    val fillStage = new FillBufferFlowStage(config)
    val readStage = new ReadBufferFlowStage(config)
    source.viaMat(fillStage)(Keep.left)
      .viaMat(readStage)(Keep.left)

  /**
    * Returns a configuration for an [[AudioStreamPlayerStage]] that is derived
    * from the passed in configuration, but contains a
    * [[AudioStreamPlayerStage.SourceResolverFunc]] that can resolve sources
    * written into the buffer used by a [[BufferedPlaylistSource]].
    *
    * @param config the original configuration
    * @tparam SRC the original materialized type of the source
    * @tparam SNK the type expected by the sink of the stream
    * @return the mapped configuration
    */
  def mapConfig[SRC, SNK](config: AudioStreamPlayerStage.AudioStreamPlayerConfig[SRC, SNK]):
  AudioStreamPlayerStage.AudioStreamPlayerConfig[SourceInBuffer[SRC], SNK] =
    val mappedSinkFunc: AudioStreamPlayerStage.SinkProviderFunc[SourceInBuffer[SRC], SNK] = src =>
      config.sinkProviderFunc(src.originalSource)
    config.copy(sourceResolverFunc = resolveSourceInBuffer, sinkProviderFunc = mappedSinkFunc)

  /**
    * A default function for opening a file for writing in the buffer. This is
    * used by [[BufferedPlaylistSourceConfig]] if no custom function is
    * provided for this purpose.
    *
    * @param path the path to the buffer file
    * @return a [[Sink]] for writing to this file
    */
  def defaultBufferSink(path: Path): Sink[ByteString, Future[Any]] = FileIO.toPath(path)

  /**
    * A default function for opening a file for reading in the buffer. This is
    * used by [[BufferedPlaylistSourceConfig]] if no custom function is
    * provided for this purpose.
    *
    * @param path the path to the buffer file
    * @return a [[Source]] for reading from this file
    */
  def defaultBufferSource(path: Path): Source[ByteString, Any] = FileIO.fromPath(path)

  /**
    * A default function for deleting files in the buffer. This is used by
    * [[BufferedPlaylistSourceConfig]] if no custom function is provided for
    * this purpose. This function uses basic Java functionality to delete the
    * file from disk.
    *
    * @param path the path to the buffer file
    */
  def defaultBufferDelete(path: Path): Unit =
    Files.delete(path)

  /**
    * A data class describing an audio source that has been written into a
    * buffer file. If a buffer file contains data from a source, an instance of
    * this class is added to the corresponding [[BufferFileWritten]] message.
    * The end index of the source is only available after it has been fully
    * written. If only parts of the source ended up in the buffer file, it is
    * -1.
    *
    * @param source      the source
    * @param url         the URL for this source
    * @param startOffset the offset (in the overall playlist stream) where
    *                    this source has started
    * @param endOffset   the offset (in the overall playlist stream) where
    *                    this source has ended; can be -1 if the source is
    *                    still processed
    * @tparam SRC the type to represent sources
    */
  private[stream] case class BufferedSource[SRC](source: SRC,
                                                 url: String,
                                                 startOffset: Long,
                                                 endOffset: Long)

  /**
    * A data class used as output by [[FillBufferFlowStage]] that describes the
    * content of a buffer file. An instance of this class is issued downstream
    * whenever a file has been fully written. It contains information about the
    * audio sources contained in the file and their sizes. From the last
    * source, typically only partial data is contained in the file; therefore,
    * the size property will be undefined in most cases.
    *
    * @param sources a list with information about audio sources contained in
    *                the file
    * @tparam SRC the type to represent sources
    */
  private[stream] case class BufferFileWritten[SRC](sources: List[BufferedSource[SRC]])

  /**
    * A flow stage implementation that is responsible for routing data from a
    * playlist source to temporary buffer files.
    *
    * The stage processes the (audio) data streams from its source one by one.
    * Via special source and sink implementations and an actor that bridges
    * between them, the data is written into temporary files with a
    * configurable size. Corresponding metadata is passed downstream that
    * allows extracting the data again from these files, so that it can be
    * associated with the original data streams.
    *
    * @param config the buffer configuration
    * @param system the actor system
    * @tparam SRC the type of the data from the source
    * @tparam SNK the type of the data expected by the stream sink
    */
  private[stream] class FillBufferFlowStage[SRC, SNK](config: BufferedPlaylistSourceConfig[SRC, SNK])
                                                     (using system: classic.ActorSystem)
    extends GraphStage[FlowShape[SRC, BufferFileWritten[SRC]]]:
    private val in: Inlet[SRC] = Inlet("FillBufferFlowStage.in")
    private val out: Outlet[BufferFileWritten[SRC]] = Outlet("FillBufferFlowStage.out")

    override def shape: FlowShape[SRC, BufferFileWritten[SRC]] = new FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with StageLogging:
        /**
          * A callback that is invoked when a source from upstream has been
          * resolved by the resolver function in the configuration.
          */
        private val onSourceResolved =
          getAsyncCallback[(SRC, Try[AudioStreamPlayerStage.AudioStreamSource])](handleSourceResolved)

        /**
          * A callback that is invoked whenever an audio source from upstream
          * has been fully copied into the buffer.
          */
        private val onSourceCompleted = getAsyncCallback[Try[BufferedSource[SRC]]](handleSourceCompleted)

        /**
          * A callback that is invoked when a buffer file has been fully
          * written. If more data is available upstream, the next file can be
          * created if there is still capacity in the buffer.
          */
        private val bufferFileCompleteCallback = getAsyncCallback[Try[Any]](handleBufferFileCompleted)

        /** The actor for source/sink multiplexing. */
        private var bridgeActor: ActorRef[SourceSinkBridgeCommand] = _

        /** A buffer for elements to be pushed downstream. */
        private var dataBuffer: List[BufferFileWritten[SRC]] = Nil

        /** Stores the sources added to the current buffer file. */
        private var sourcesInCurrentBufferFile = List.empty[BufferedSource[SRC]]

        /** Stores information about the source that is currently processed. */
        private var currentSource: Option[(SRC, AudioStreamPlayerStage.AudioStreamSource)] = None

        /** A counter for the buffer files that have been created. */
        private var bufferFileCount = 0

        /**
          * A counter to track the bytes that have been written into the buffer
          * (in total). This is used to determine the start and end offsets of
          * the single data sources.
          */
        private var bytesProcessed = 0L

        /**
          * A counter for the number of processed sources. This is used to
          * detect and handle an empty playlist.
          */
        private var sourcesProcessed = 0

        /**
          * A flag that allows keeping track whether a buffer file is currently
          * written. Write operations are paused if the buffer directory
          * already contains 2 buffer files.
          */
        private var bufferFileWriteInProgress = false

        /**
          * A flag to record whether a data source from upstream is currently
          * processed. This is used to make sure that this stage does not
          * complete too early.
          */
        private var sourceInProgress = false

        /**
          * A flag that reports whether a pull request for upstream is
          * currently pending. This is used to figure out whether the
          * ''onUpstreamFinish'' callback has to take additional action to push
          * the last buffer file downstream.
          */
        private var sourceRequested = false

        /**
          * A flag indicating that the playlist (upstream) is finished. This
          * stage typically cannot complete immediately, but has to wait until
          * all ongoing processing is done, and the produced results have been
          * pushed to downstream.
          */
        private var playlistFinished = false

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            sourceInProgress = true
            sourcesProcessed += 1
            val src = grab(in)
            fillSourceIntoBuffer(src)

          // Note: This must be overridden, since the base implementation immediately completes the stage.
          override def onUpstreamFinish(): Unit =
            log.info("onUpstreamFinish")
            playlistFinished = true
            if !sourceInProgress then handleBufferFileCompleted(Success(()))
        )

        setHandler(out, new OutHandler:
          override def onPull(): Unit = {
            dataBuffer match
              case ::(head, next) =>
                push(out, head)
                dataBuffer = next
                createAndFillBufferFile()
              case Nil =>
                completeIfDone()
          }
        )

        override def preStart(): Unit =
          super.preStart()
          pull(in)

          if !Files.isDirectory(config.bufferFolder) then
            log.info("Buffer folder '{}' does not exist. Creating it now.", config.bufferFolder)
            Files.createDirectories(config.bufferFolder)

          bridgeActor = spawnBridgeActor(system, config, config.bufferFileSize, "fillBridgeActor")

        override def postStop(): Unit =
          bridgeActor ! StopBridgeActor

          super.postStop()

        /**
          * Checks the end conditions, and if no operations are in progress
          * anymore, completes this stage.
          *
          * @return a flag whether this stage is now complete
          */
        private def completeIfDone(): Boolean =
          if playlistFinished && !sourceInProgress && !bufferFileWriteInProgress && dataBuffer.isEmpty then
            log.info("Playlist source finished after processing {} source(s).", sourcesProcessed)
            completeStage()
            true
          else
            false

        /**
          * Push the given data downstream. This is done directly if possible,
          * i.e. when the out port has been pulled. Otherwise, the data is
          * buffered, so that it can be provided when there is demand.
          *
          * @param data the data to be pushed
          */
        private def pushData(data: BufferFileWritten[SRC]): Unit =
          if isAvailable(out) then
            push(out, data)
          else
            dataBuffer = dataBuffer :+ data

        /**
          * Initiates filling of the given data source into a buffer file. In
          * the first step, the source needs to be resolved.
          *
          * @param source the data source from upstream
          */
        private def fillSourceIntoBuffer(source: SRC): Unit =
          config.streamPlayerConfig.sourceResolverFunc(source).onComplete { triedResolvedSource =>
            onSourceResolved.invoke(source -> triedResolvedSource)
          }

        /**
          * A callback method that is invoked when the current data source from
          * upstream has been resolved. If this was successful, a stream can
          * now be opened for the source to fill its content into the buffer.
          * Otherwise, the source is just skipped.
          *
          * @param srcData a tuple with original source and the result of the
          *                resolve operation
          */
        private def handleSourceResolved(srcData: (SRC, Try[AudioStreamPlayerStage.AudioStreamSource])): Unit =
          srcData._2 match
            case Failure(exception) =>
              log.error(exception, "Could not resolve source '{}'. Skipping it.", srcData._1)
              requestNextSource()
            case Success(resolvedSource) =>
              log.info("Source '{}' has been resolved successfully with URI '{}'.", srcData._1, resolvedSource.url)
              currentSource = Some((srcData._1, resolvedSource))
              createAndFillBufferFile()
              resolvedSource.source.runWith(
                Sink.fromGraph(
                  new BridgeSink(
                    bridgeActor,
                    srcData._1,
                    resolvedSource.url,
                    bytesProcessed,
                    ignoreErrors = true
                  )
                )
              ).onComplete(onSourceCompleted.invoke)

        /**
          * A callback method that is invoked when one of the data sources from
          * upstream has been fully read. Then the next source needs to be
          * requested.
          *
          * @param triedResult the result from reading the source
          */
        private def handleSourceCompleted(triedResult: Try[BufferedSource[SRC]]): Unit =
          // The current source should always be defined when this function is called.
          currentSource.foreach { (source, streamSource) =>
            log.info("Current source '{}' is complete: {}.", source, triedResult)
            val bufferedSource = triedResult.getOrElse(
              // Note: This case should actually not occur, since BufferedSink handles errors gracefully.
              BufferedSource(source, streamSource.url, bytesProcessed, bytesProcessed)
            )
            bytesProcessed = bufferedSource.endOffset
            sourcesInCurrentBufferFile = bufferedSource :: sourcesInCurrentBufferFile

            requestNextSource()
          }

        /**
          * Request the next source from upstream to continue filling the
          * buffer. Handle the end of the playlist stream correctly.
          */
        private def requestNextSource(): Unit =
          sourceInProgress = false
          if playlistFinished then
            bridgeActor ! PlaylistEnd
            completeIfDone()
          else
            pull(in)
          currentSource = None

        /**
          * Creates a new file in the buffer if possible and starts a stream
          * that populates it from the sources obtained from upstream. This
          * stream completes when the maximum size of the buffer file was
          * reached or the playlist stream ends. If the buffer is full (i.e. it
          * contains already two files), no immediate action is triggered.
          */
        private def createAndFillBufferFile(): Unit =
          if !bufferFileWriteInProgress && dataBuffer.size < 1 && (sourceInProgress || sourceRequested) then
            bufferFileWriteInProgress = true
            val startOffset = bufferFileCount * config.bufferFileSize
            bufferFileCount += 1
            val bufferFile = resolveBufferFile(config, bufferFileCount)
            log.info("Creating buffer file {}.", bufferFile)

            val bufferFileSource = Source.fromGraph(
              new BridgeSource(bridgeActor, startOffset, config.bufferFileSize, bufferFileCount)
            )
            val bufferFileSink = config.bufferSinkFunc(bufferFile)
            bufferFileSource.runWith(bufferFileSink).onComplete(bufferFileCompleteCallback.invoke)

        /**
          * A callback method that is invoked when a buffer file has been fully
          * written. Depending on the provided completion reason, the next
          * actions need to be taken.
          *
          * @param triedResult the result from the source for the file
          */
        private def handleBufferFileCompleted(triedResult: Try[Any]): Unit =
          bufferFileWriteInProgress = false
          log.info("Current buffer file has been fully written: {}.", triedResult)

          triedResult match
            case Success(result) =>
              val (allSourcesInFile, nextFile) = correctSourcesInCurrentBufferFile(
                sourcesInCurrentBufferFile.reverse,
                currentSource.map(src => BufferedSource(src._1, src._2.url, bytesProcessed, -1)),
                bufferFileCount,
                config.bufferFileSize
              )
              if allSourcesInFile.nonEmpty then
                pushData(BufferFileWritten(allSourcesInFile))
              sourcesInCurrentBufferFile = nextFile

              if !completeIfDone() then createAndFillBufferFile()
            case Failure(exception) =>
              failStage(exception)
  end FillBufferFlowStage

  /**
    * A flow stage implementation that is responsible for reading audio sources
    * from the file-based buffer.
    *
    * This stage follows [[FillBufferFlowStage]] in the buffered playlist
    * stream. It uses similar techniques to expose the original audio sources
    * from the temporary buffer files that have been written previously.
    *
    * @param config the configuration for the buffered source
    * @param system the actor system
    * @tparam SRC the type of the original source
    * @tparam SNK the type expected by the sink of the strea,
    */
  private[stream] class ReadBufferFlowStage[SRC, SNK](config: BufferedPlaylistSourceConfig[SRC, SNK])
                                                     (using system: classic.ActorSystem)
    extends GraphStage[FlowShape[BufferFileWritten[SRC], SourceInBuffer[SRC]]]:
    private val in: Inlet[BufferFileWritten[SRC]] = Inlet("ReadBufferFlowStage.in")
    private val out: Outlet[SourceInBuffer[SRC]] = Outlet("ReadBufferFlowStage.out")

    override def shape: FlowShape[BufferFileWritten[SRC], SourceInBuffer[SRC]] = new FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with StageLogging:
        /**
          * A callback function that gets invoked when the response from the
          * bridge actor arrives for starting a new buffer file.
          */
        private val onNextBufferFileConfig =
          getAsyncCallback[Try[(BufferFileWritten[SRC], NextBufferFileConfig)]](handleNextBufferFileConfig)

        /**
          * A callback function that gets invoked when an audio source has been
          * read from the buffer.
          */
        private val onSourceComplete = getAsyncCallback[Try[BridgeSourceResult]](handleSourceCompleted)

        /**
          * A callback function that gets invoked when a buffer file has been
          * read completely.
          */
        private val onBufferFileComplete = getAsyncCallback[Try[Any]](handleBufferFileCompleted)

        /** The actor for source/sink multiplexing. */
        private var bridgeActor: ActorRef[SourceSinkBridgeCommand] = _

        /** Stores the sources contained in the current buffer file. */
        private var bufferedSources = List.empty[BufferedSource[SRC]]

        /** A counter for the buffer files that have been processed. */
        private var bufferFileCount = 0

        /** A counter for the sources that have been processed. */
        private var sourceCount = 0

        /**
          * A counter for the number of sources that are currently in progress.
          * This is needed, since there can be overlap with a source that has
          * just been started and one that is about to complete. In order to
          * correctly determine when the stage can be completed, it has to be
          * checked that no source is active anymore.
          */
        private var inProgressCount = 0

        /**
          * A flag that indicates whether currently a [[BufferFileWritten]]
          * object is processed. This is needed to record that some processing
          * is done, and the state cannot be completed yet.
          */
        private var fileWrittenInProgress = false

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            val fileWritten = grab(in)
            log.info("Received buffer file: {}.", fileWritten)
            fileWrittenInProgress = true
            bridgeActor.ask[NextBufferFileConfig] { ref =>
                createPrepareReadBufferFile(ref, fileWritten)
              }.map(config => (fileWritten, config))
              .onComplete(onNextBufferFileConfig.invoke)

          // Note: This must be overridden, since the base implementation immediately completes the stage.
          override def onUpstreamFinish(): Unit =
            log.info("onUpstreamFinished - all buffer files have been written.")
            completeStageIfDone()
        )

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            pushNextSource()
        )

        override def preStart(): Unit =
          super.preStart()
          bridgeActor = spawnBridgeActor(system, config, Long.MaxValue, "readBridgeActor")
          pull(in)

        override def postStop(): Unit =
          bridgeActor ! StopBridgeActor
          cleanUpBufferFolder()

          super.postStop()

        /**
          * Cleans up remaining buffer files from the buffer folder.
          */
        private def cleanUpBufferFolder(): Unit =
          (1 to bufferFileCount + 2).filter { index =>
            val file = resolveBufferFile(config, index)
            Files.exists(file)
          }.foreach(deleteBufferFile(log, config, _))

        /**
          * Handles the response from the bridge actor when starting a new
          * buffer file. The response determines how to exactly handle this
          * file.
          *
          * @param triedConfig the response from the actor plus the content of
          *                    the current file (should be successful)
          */
        private def handleNextBufferFileConfig(triedConfig: Try[(BufferFileWritten[SRC], NextBufferFileConfig)]): Unit =
          fileWrittenInProgress = false
          triedConfig.foreach { (fileWritten, fileConfig) =>
            bufferFileCount += 1
            bufferedSources = bufferedSources :++ fileWritten.sources.drop(fileConfig.skipSources)

            if fileConfig.skipFile then
              log.info("Skipping file {} since it contains only data from a skipped source.", bufferFileCount)
              deleteBufferFile(log, config, bufferFileCount)
              requestNextFile()
            else
              readNextBufferFile()
              pushNextSource()
          }

        /**
          * Starts reading of the next buffer file. For this file, a source is
          * created together with a sink that passes the content to the bridge
          * actor. From there, the data can be divided again to the single data
          * sources.
          */
        private def readNextBufferFile(): Unit =
          val bufferFile = resolveBufferFile(config, bufferFileCount)
          log.info("Start reading of buffer file '{}'.", bufferFile)

          val bufferFileSource = config.bufferSourceFunc(bufferFile)
          val bufferFileSink = Sink.fromGraph(
            new BridgeSink(bridgeActor, bufferFile, bufferFile.toString, 0, ignoreErrors = false)
          )
          bufferFileSource.runWith(bufferFileSink).onComplete(onBufferFileComplete.invoke)

        /**
          * Pushes an element downstream that allows reading the next audio
          * source in the current buffer file if this is currently possible.
          */
        private def pushNextSource(): Unit =
          if isAvailable(out) && bufferedSources.nonEmpty && inProgressCount == 0 then
            val currentSource = bufferedSources.head
            bufferedSources = bufferedSources.tail
            sourceCount += 1
            inProgressCount += 1
            val size = sourceSize(currentSource)
            log.info("Start processing audio source '{}' with size {}..", currentSource.source, size)

            push(out, new SourceInBuffer[SRC]:
              override def originalSource: SRC = currentSource.source

              override def resolveSource(): Future[AudioStreamPlayerStage.AudioStreamSource] = Future {
                val promiseResult = Promise[BridgeSourceResult]()
                promiseResult.future.onComplete(onSourceComplete.invoke)

                val source = Source.fromGraph(new BridgeSource(bridgeActor,
                  currentSource.startOffset,
                  size,
                  sourceCount,
                  promiseResult))
                AudioStreamPlayerStage.AudioStreamSource(currentSource.url, source)
              }
            )

        /**
          * A callback function that is invoked when the stream for reading a
          * buffer file completes. If possible, the next buffer file is
          * requested from upstream.
          *
          * @param result the result received from the stream
          */
        private def handleBufferFileCompleted(result: Try[Any]): Unit =
          log.info("Buffer file {} was read completely with result {}.", bufferFileCount, result)
          deleteBufferFile(log, config, bufferFileCount)

          result match
            case Success(_) => requestNextFile()
            case Failure(exception) => failStage(exception)

        /**
          * Requests data about the next buffer file from upstream if this is
          * possible.
          */
        private def requestNextFile(): Unit =
          if !completeStageIfDone() then
            if !isClosed(in) then
              pull(in)

        /**
          * A callback function that is invoked when an audio source completes.
          * Here it needs to be handled whether bytes have to be skipped or the
          * playlist is complete.
          *
          * @param triedResult the result from the source
          */
        private def handleSourceCompleted(triedResult: Try[BridgeSourceResult]): Unit =
          log.info("Current source completed with result {}.", triedResult)
          inProgressCount -= 1

          triedResult.recover {
            case e: BridgeSourceFailure => e.result
          }.foreach { result =>
            bridgeActor ! SourceCompleted(result)
          }

          if !completeStageIfDone() then pushNextSource()

        /**
          * Checks whether all sources from upstream have been processed. If
          * so, this stage is now completed. The return value indicates whether
          * processing is done.
          */
        private def completeStageIfDone(): Boolean =
          if isClosed(in) && !fileWrittenInProgress && bufferedSources.isEmpty && inProgressCount == 0 then
            log.info("All sources have been processed. Completing stage.")
            completeStage()
            true
          else
            false
  end ReadBufferFlowStage

  /**
    * An internal [[Sink]] implementation that passes data from upstream to a
    * bridge actor instance. This is used to combine the data of multiple
    * sources.
    *
    * @param bridgeActor  the bridge actor instance
    * @param source       the source that is currently processed
    * @param url          the URL for the current source
    * @param startOffset  the start offset for the current source
    * @param system       the implicit actor system
    * @param ignoreErrors a flag whether to ignore an error from upstream; this
    *                     is typically '''true''' when writing into a buffer
    *                     file and '''false''' otherwise
    * @tparam SRC the type to represent sources
    */
  private class BridgeSink[SRC](bridgeActor: ActorRef[SourceSinkBridgeCommand],
                                source: SRC,
                                url: String,
                                startOffset: Long,
                                ignoreErrors: Boolean)
                               (using system: ActorSystem[_])
    extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[BufferedSource[SRC]]]:
    private val in: Inlet[ByteString] = Inlet("BridgeSink")

    override def shape: SinkShape[ByteString] = SinkShape(in)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
    (GraphStageLogic, Future[BufferedSource[SRC]]) =
      val promiseMat = Promise[BufferedSource[SRC]]()
      val logic = new GraphStageLogic(shape) with StageLogging:
        /**
          * A callback to handle processed notifications from the bridge actor.
          */
        private val onProcessedCallback = getAsyncCallback[Try[DataChunkProcessed]](chunkProcessed)

        /** A counter for the bytes passed through this sink. */
        private var bytesProcessed = 0L

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            given ec: ExecutionContext = system.executionContext

            val data = grab(in)
            bytesProcessed += data.size
            bridgeActor.ask[DataChunkProcessed](ref => {
                AddDataChunk(Some(ref), data)
              })
              .onComplete(onProcessedCallback.invoke)

          override def onUpstreamFinish(): Unit =
            super.onUpstreamFinish()
            log.info("Source '{}' finished after {} bytes.", source, bytesProcessed)

          override def onUpstreamFailure(ex: Throwable): Unit =
            super.onUpstreamFailure(ex)
            log.error(ex, "Source '{}' failed after {} bytes.", source, bytesProcessed)
            if !ignoreErrors then promiseMat.failure(ex)
        )

        override def preStart(): Unit =
          pull(in)

        override def postStop(): Unit =
          promiseMat.trySuccess(BufferedSource(source, url, startOffset, startOffset + bytesProcessed))

        /**
          * A function that is invoked when the bridge actor sends a
          * notification about a processed data chunk. Then the next chunk can
          * be requested from upstream.
          */
        private def chunkProcessed(result: Try[DataChunkProcessed]): Unit =
          result match
            case Success(value) => pull(in)
            case Failure(exception) => cancelStage(exception)

      (logic, promiseMat.future)
    end createLogicAndMaterializedValue
  end BridgeSink

  /**
    * An enumeration defining the reasons for the completion of a
    * [[BridgeSource]].
    */
  private[stream] enum BridgeSourceCompletionReason:
    case PlaylistStreamEnd
    case LimitReached

  /**
    * A data class defining the result object produced by a [[BridgeSource]].
    * This is used to determine the next steps to be performed after the source
    * was read. For instance, if the source was read only partly, some special
    * handling is required.
    *
    * @param bytesRead        the number of bytes that were read
    * @param startOffset      the offset where this source starts
    * @param size             the size of the source or -1 if this is not yet
    *                         known
    * @param sourceIndex      the index of this source
    * @param completionReason the reason for the completion
    */
  private[stream] case class BridgeSourceResult(bytesRead: Long,
                                                startOffset: Long,
                                                size: Long,
                                                sourceIndex: Int,
                                                completionReason: BridgeSourceCompletionReason)

  /**
    * An exception class to signal a problem with a bridge source. When reading
    * from the buffer, the exception must still contain information about the
    * current state of the source when it terminated. Note that early
    * termination (for instance with the ''take'' operator) also causes the
    * source to be completed with an exception (a special cancellation
    * exception in this case).
    *
    * @param result the [[BridgeSourceResult]] describing the state of the
    *               source when it terminated
    * @param cause  the original exception that caused the failure
    */
  private[stream] class BridgeSourceFailure(val result: BridgeSourceResult,
                                            cause: Throwable) extends Throwable(cause)

  /**
    * An internal [[Source]] implementation that reads its data from a bridge
    * actor instance. It is possible to limit the number of bytes issued by
    * this source. This is necessary for instance to make sure that files in
    * the buffer do not exceed their configured capacity. When reading sources
    * from the buffer that span multiple buffer files, the size of the source
    * is not known initially. In this case, the value -1 is passed, and the
    * limit is set later on via the bridge actor.
    *
    * The source materializes an object the contains information about the data
    * that has been processed. This can be used to determine whether the whole
    * source has been processed or only parts of it. To distinguish between the
    * cases that the end of the playlist was reached or the limit of the
    * source, there is a flag with the reason for its end.
    *
    * Since the materialized value of the source is only available if there is
    * full control over the whole stream (which is for instance not the case
    * when reading data from a buffer file), there is an alternative way to
    * obtain the result produced by the source: The [[Promise]] that is also
    * used to provide the materialized result can be passed when constructing
    * an instance. Then a client can react when it gets completed.
    *
    * @param bridgeActor  the bridge actor instance
    * @param startOffset  the offset where this source starts
    * @param initialLimit the initial number of bytes to be issued
    *                     (this may be changed later); can be less than 0
    *                     to indicate that no limit is enforced
    * @param index        the index of this source; this is a sequence number
    *                     that allows to distinguish between sources
    * @param promiseMat   the promise to use for generating the result
    * @param system       the actor system
    */
  private class BridgeSource(bridgeActor: ActorRef[SourceSinkBridgeCommand],
                             startOffset: Long,
                             initialLimit: Long,
                             index: Int,
                             promiseMat: Promise[BridgeSourceResult] = Promise())
                            (using system: ActorSystem[_])
    extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[BridgeSourceResult]]:
    private val out: Outlet[ByteString] = Outlet("BridgeSource")

    override def shape: SourceShape[ByteString] = SourceShape(out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
    (GraphStageLogic, Future[BridgeSourceResult]) =
      val logic = new GraphStageLogic(shape):
        /**
          * A callback to handle data chunks that have been retrieved
          * asynchronously from the bridge actor.
          */
        private val onDataCallback = getAsyncCallback[Try[DataChunkResponse]](dataAvailable)

        /** The number of bytes that has been emitted so far. */
        private var bytesProcessed = 0L

        /**
          * Stores data to complete this stage the next time a request from
          * downstream arrives.
          */
        private var optResult: Option[BridgeSourceCompletionReason] = None

        /** The maximum number of bytes to issue. */
        private var limit = initialLimit

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            optResult match
              case Some(result) =>
                completeStageWithResult(result)
              case None =>
                requestNextChunk()

          override def onDownstreamFinish(cause: Throwable): Unit =
            val result = createSourceResult(BridgeSourceCompletionReason.LimitReached)
            val sourceFailure = new BridgeSourceFailure(result, cause)
            promiseMat.failure(sourceFailure)
            super.onDownstreamFinish(cause)
        )

        override def preStart(): Unit =
          super.preStart()
          if limit < 0 then
            bridgeActor ! GetSourceLimit

        /**
          * Queries the bridge actor for the next chunk for data.
          */
        private def requestNextChunk(): Unit =
          given ec: ExecutionContext = system.executionContext

          val maxSize = if limit < 0 then ReadChunkSize
          else math.min(limit - bytesProcessed, ReadChunkSize).toInt
          bridgeActor.ask[DataChunkResponse](ref => GetNextDataChunk(ref, maxSize, index))
            .onComplete(onDataCallback.invoke)

        /**
          * Updates the limit. This function gets called when the size of the
          * current source becomes available.
          *
          * @param nextLimit the limit to set
          */
        private def updateLimit(nextLimit: Long): Unit =
          limit = nextLimit

        /**
          * Handles responses from the bridge actor for requests to the next
          * chunk of data. The chunk is passed downstream if it is not empty.
          * An empty chunk indicates the end of the stream. An update of the
          * source limit is handled by resending the request with the new
          * limit. Failures are handled by failing this stage.
          *
          * @param triedChunk a ''Try'' with the next chunk of data
          */
        private def dataAvailable(triedChunk: Try[DataChunkResponse]): Unit =
          triedChunk match
            case Success(DataChunkResponse.EndOfData) =>
              completeStageWithResult(BridgeSourceCompletionReason.PlaylistStreamEnd)
            case Success(DataChunkResponse.DataChunk(chunk, _)) =>
              push(out, chunk)
              bytesProcessed += chunk.size
              completeIfLimitReached(immediate = false)
            case Failure(exception) => cancelStage(exception)

        /**
          * Checks whether the current source limit has been reached. If so,
          * this stage is completed accordingly. If the ''immediate'' flag is
          * '''true''', completion happens directly. Otherwise, state is set to
          * complete the stage when the next request for data from downstream
          * comes in. This is necessary to make sure that the last chunk of
          * data is processed correctly.
          *
          * @param immediate flag whether to immediately complete this stage
          * @return a flag whether the limit has been reached
          */
        private def completeIfLimitReached(immediate: Boolean = true): Boolean =
          if limit >= 0 && bytesProcessed >= limit then
            if immediate then
              completeStageWithResult(BridgeSourceCompletionReason.LimitReached)
            else
              optResult = Some(BridgeSourceCompletionReason.LimitReached)
            true
          else
            false

        /**
          * Sets this stage to completed and constructs the materialized value
          * with a proper result that contains the given completion reason.
          *
          * @param reason the completion reason for this stage
          */
        private def completeStageWithResult(reason: BridgeSourceCompletionReason): Unit =
          completeStage()
          val result = createSourceResult(reason)
          promiseMat.success(result)

        /**
          * Creates a [[BridgeSourceResult]] from the current state of this
          * source with the given reason.
          *
          * @param reason the completion reason for this stage
          * @return the result for this source
          */
        private def createSourceResult(reason: BridgeSourceCompletionReason): BridgeSourceResult =
          BridgeSourceResult(bytesProcessed, startOffset, limit, index, reason)

      (logic, promiseMat.future)
  end BridgeSource

  /**
    * A data type describing a response sent by the bridge actor on a
    * [[GetNextDataChunk]] request.
    *
    * Typically, the actor sends the next data chunk back. It can, however,
    * happen that the size of a source becomes known while a request for the
    * next chunk is pending. In this case, it could be that the size of the
    * source invalidates the request, as the provided limit may be too big. To
    * handle such a situation, the actor sends a special response that contains
    * the new limit and ignores the original request. The client has to resend
    * the request with a potentially updated limit.
    */
  private[stream] enum DataChunkResponse:
    /**
      * A response containing the requested data chunk.
      *
      * @param data        the data
      * @param sourceIndex the index of the target source
      */
    case DataChunk(data: ByteString,
                   sourceIndex: Int)

    /**
      * A response indicating to the receiver that no more data is available.
      * The receiver should then stop itself.
      */
    case EndOfData

  /**
    * A message class sent by the bridge actor as a reply to an
    * [[AddDataChunk]] command. The message tells the receiver that now
    * capacity is available to receive another chunk of data.
    */
  private case class DataChunkProcessed()

  /**
    * A message class sent by the bridge actor as a reply to a
    * [[PrepareReadBufferFile]] command. It contains instructions how to deal
    * with the new buffer file to be read.
    *
    * @param skipSources the number of sources to skip from the beginning of
    *                    the list
    * @param skipFile    a flag whether the whole file should be skipped; this
    *                    is the case if it contains only a single source which
    *                    has already been canceled
    */
  private case class NextBufferFileConfig(skipSources: Int,
                                          skipFile: Boolean)

  /**
    * The base trait of a hierarchy of commands processed by the actor that
    * bridges between multiple sources and sinks.
    */
  private sealed trait SourceSinkBridgeCommand

  /**
    * A command class for passing a chunk of data to the bridge actor. When the
    * chunk has been passed to its receiver, the actor responds with a
    * [[DataChunkProcessed]] message requesting the next chunk. Under normal
    * conditions, the ''replyTo'' field should be defined. Only if a special
    * message at the end of the stream is passed, it can be empty; then no
    * notification about a processed chunk is needed anymore.
    *
    * @param replyTo the optional actor to send the response to
    * @param data    the data in this chunk
    */
  private case class AddDataChunk(replyTo: Option[ActorRef[DataChunkProcessed]],
                                  data: ByteString) extends SourceSinkBridgeCommand

  /**
    * A command class processed by the bridge actor that requests the next
    * chunk of data. When such a chunk becomes available, the actor sends it to
    * the specified receiver.
    *
    * @param replyTo     the actor to send the chunk to
    * @param maxSize     the maximum size of the chunk
    * @param sourceIndex the index of the current source
    */
  private case class GetNextDataChunk(replyTo: ActorRef[DataChunkResponse],
                                      maxSize: Int,
                                      sourceIndex: Int) extends SourceSinkBridgeCommand

  /**
    * A simple data class that stores the start and end offsets of an audio
    * source.
    *
    * @param startOffset the source's start offset
    * @param endOffset   the source's end offset
    */
  private case class SourceOffsets(startOffset: Long,
                                   endOffset: Long)

  /**
    * A command class processed by the bridge actor that notifies it that a new
    * buffer file is now started. Information about the content of this file is
    * passed. Based on this information, the actor can update its internal
    * state. It sends a response with instructions how to handle this file.
    *
    * @param replyTo the actor to send the response to
    * @param offsets a list with the offsets of the sources in the file
    */
  private case class PrepareReadBufferFile(replyTo: ActorRef[NextBufferFileConfig],
                                           offsets: List[SourceOffsets]) extends SourceSinkBridgeCommand

  /**
    * A command to indicate to the bridge actor that the limit for the current
    * source is not known. The actor then passes the limit to the source as
    * soon as it becomes available.
    */
  private case object GetSourceLimit extends SourceSinkBridgeCommand

  /**
    * A command that notifies the bridge actor that a source has been
    * completed - either regularly or before it was fully read. Information
    * about the result of this source is provided. The actor then configures
    * skipping of data if necessary.
    *
    * @param result the result produced by the source
    */
  private case class SourceCompleted(result: BridgeSourceResult) extends SourceSinkBridgeCommand

  /**
    * A command that notifies the bridge actor that the playlist is now
    * complete. The actor reacts on this command by sending an end-of-data
    * response to consumers, so that active streams complete themselves.
    */
  private case object PlaylistEnd extends SourceSinkBridgeCommand

  /**
    * A command to notify the bridge actor to stop itself.
    */
  private case object StopBridgeActor extends SourceSinkBridgeCommand

  /**
    * A data class holding the current state of a bridge actor. It simplifies
    * passing around the different pieces of information that need to be
    * managed.
    *
    * @param bufferFileSize    the size of a buffer file
    * @param unknownSourceSize the size to assume for a source if the size is
    *                          not known yet
    * @param chunks            the current buffer of chunks
    * @param producer          the reference to the producer of the chunk
    * @param consumer          the reference to the consumer of chunks
    * @param limitUpdate       a pending update of the limit for the currently
    *                          processed source
    * @param limitUnknown      a flag that indicates whether for the current
    *                          source the limit is not known; then this source
    *                          is sent a notification when there is an update
    * @param fileOverlap       a flag that determines whether the last source
    *                          in the current buffer file overlaps into the
    *                          next file
    * @param bytesProcessed    the number of bytes that have been passed
    *                          through this actor instance
    * @param currentProcessed  the number of bytes that have been passed
    *                          through this actor for the current source
    * @param skipSource        the index of the source that is currently
    *                          skipped; this is part of the mechanism to handle
    *                          sources that were canceled early
    * @param fileRequest       an optional pending request to prepare the next
    *                          buffer file
    * @param fileCount         the number of buffer files processed
    * @param sourceCount       the number of sources encountered so far
    * @param sourceSizes       a map storing the known sizes of sources
    */
  private case class BridgeActorState(bufferFileSize: Int,
                                      unknownSourceSize: Long,
                                      chunks: List[DataChunkResponse.DataChunk],
                                      producer: Option[ActorRef[DataChunkProcessed]],
                                      consumer: Option[GetNextDataChunk],
                                      limitUpdate: Option[Long],
                                      limitUnknown: Boolean,
                                      fileOverlap: Boolean,
                                      bytesProcessed: Long,
                                      currentProcessed: Long,
                                      skipSource: Int,
                                      fileRequest: Option[PrepareReadBufferFile],
                                      fileCount: Int,
                                      sourceCount: Int,
                                      sourceSizes: Map[Int, Long]):
    /**
      * Returns a flag whether all data from the current buffer file has been
      * read. This is used to determine whether a [[PrepareReadBufferFile]]
      * request can be processed. This can only be safely done when all the
      * data of the file has been consumed. Note that at that time the file has
      * been fully copied into the buffer; the question is only if there are
      * chunks missing that have not yet been read.
      *
      * @return a flag if the current buffer file has been fully read
      */
    def isFileFullyRead: Boolean = chunks.isEmpty

    /**
      * Updates information about the sizes of sources based on the given list
      * of offsets.
      *
      * @param offsets the list with [[SourceOffsets]] objects
      * @return the updated state
      */
    def updateSourceSizes(offsets: List[SourceOffsets]): BridgeActorState =
      val (nextCount, nextSizes) = offsets.foldLeft((sourceCount, sourceSizes)) { (current, ofs) =>
        if ofs.endOffset > 0 then
          (current._1 + 1, current._2 + (current._1 -> (ofs.endOffset - ofs.startOffset)))
        else
          current
      }

      copy(sourceSizes = nextSizes)

    /**
      * Returns the size of the source with the given index. If it is already
      * known, it is returned from the map of source sizes; otherwise result is
      * the unknown source size.
      *
      * @param sourceIdx the index of the source in question
      * @return the size of this source
      */
    def sourceSize(sourceIdx: Int): Long =
      sourceSizes.getOrElse(sourceIdx, unknownSourceSize)

    /**
      * Returns an updated [[BridgeActorState]] with the given chunk added.
      * This function correctly handles the sizes of sources and splits chunks
      * if they contain data for multiple sources. In this case, also the other
      * properties of the state are updated accordingly. The function does not
      * handle the skip position.
      *
      * @param data the data to be added
      * @return an updated state with the given chunk added
      */
    def addChunk(data: ByteString): BridgeActorState =
      if data.isEmpty then this
      else
        val nextProcessed = bytesProcessed + data.size
        val size = sourceSize(sourceCount)
        if currentProcessed + data.size >= size then
          val (chunk1, chunk2) = data.splitAt((size - currentProcessed).toInt)
          copy(
            chunks = addChunkWithSkipping(chunk1),
            sourceCount = sourceCount + 1,
            currentProcessed = 0,
            bytesProcessed = nextProcessed,
            sourceSizes = updateForCompletedSource(sourceSizes, sourceCount)
          ).addChunk(chunk2)
        else
          copy(
            chunks = addChunkWithSkipping(data),
            currentProcessed = currentProcessed + data.size,
            bytesProcessed = nextProcessed
          )

    /**
      * Returns a flag whether data from the current source is currently
      * skipped.
      *
      * @return a flag whether skipping is active
      */
    def isSkipping: Boolean = skipSource == sourceCount

    /**
      * Returns an updated list of chunks that contains the given new chunk
      * unless skipping is active for the current source.
      *
      * @param chunk the new chunk to add
      * @return a list with the updated chunks
      */
    private def addChunkWithSkipping(chunk: ByteString): List[DataChunkResponse.DataChunk] =
      if isSkipping then
        chunks
      else
        chunks :+ DataChunkResponse.DataChunk(chunk, sourceCount)

  end BridgeActorState

  /**
    * Returns a new [[BridgeActorState]] instant to be used for a newly
    * created bridge actor.
    *
    * @param fileSize    the size of the buffer files
    * @param unknownSize the size to use if the real size of a source is not
    *                    yet known
    */
  private def initialBridgeActorState(fileSize: Int, unknownSize: Long) = BridgeActorState(
    bufferFileSize = fileSize,
    unknownSourceSize = unknownSize,
    chunks = Nil,
    producer = None,
    consumer = None,
    limitUpdate = None,
    limitUnknown = false,
    fileOverlap = false,
    bytesProcessed = 0,
    currentProcessed = 0,
    skipSource = -1,
    fileRequest = None,
    fileCount = 0,
    sourceCount = 1,
    sourceSizes = Map.empty
  )

  /**
    * Creates a [[PrepareReadBufferFile]] object from the given parameters.
    *
    * @param replyTo the actor to send the response to
    * @param file    the object describing the next buffer file
    * @tparam SRC the type of the sources from upstream
    * @return the [[PrepareReadBufferFile]] object
    */
  private def createPrepareReadBufferFile[SRC](replyTo: ActorRef[NextBufferFileConfig],
                                               file: BufferFileWritten[SRC]): PrepareReadBufferFile =
    val offsets = file.sources.map { source =>
      SourceOffsets(source.startOffset, source.endOffset)
    }
    PrepareReadBufferFile(
      replyTo = replyTo,
      offsets = offsets
    )

  /**
    * Applies the given skip position to the list of data chunks. So, data
    * affected by the skip until position is removed from the list.
    *
    * @param chunks    the list of chunks to process
    * @param offset    the current offset; this is where the last chunk in the
    *                  list ends
    * @param skipUntil the position until which to skip
    * @return the list of chunks with the skip position applied
    */
  private[stream] def applySkipUntil(chunks: List[DataChunkResponse.DataChunk],
                                     offset: Long,
                                     skipUntil: Long): List[DataChunkResponse.DataChunk] =
    if skipUntil < 0 || chunks.isEmpty then chunks
    else if skipUntil >= offset then Nil
    else
      val startOffset = chunks.foldRight(offset) { (chunk, pos) => pos - chunk.data.size }

      @tailrec def skipChunks(currentChunks: List[DataChunkResponse.DataChunk], pos: Long):
      List[DataChunkResponse.DataChunk] =
        val headChunk = currentChunks.head
        val endPos = pos + headChunk.data.size
        if endPos <= skipUntil then skipChunks(currentChunks.tail, endPos)
        else if pos == skipUntil then currentChunks
        else
          val splitData = headChunk.data.splitAt((skipUntil - pos).toInt)._2
          DataChunkResponse.DataChunk(splitData, headChunk.sourceIndex) :: currentChunks.tail

      skipChunks(chunks, startOffset)

  /**
    * Returns an updated map of source sizes after the source with the given
    * index has completed. This function removes information for obsolete
    * sources.
    *
    * @param sourceSizes the map with source sizes
    * @param sourceIdx   the index of the completed source
    * @return the updated map with sizes
    */
  private def updateForCompletedSource(sourceSizes: Map[Int, Long], sourceIdx: Int): Map[Int, Long] =
    sourceSizes.filter(_._1 > sourceIdx)

  /**
    * Returns the behavior of an actor that bridges between multiple sources
    * and sinks. For the use case at hand, multiple sources may need to be
    * combined to be filled into a buffer file. Also, the content of sources
    * may need to be split to fit into multiple buffer files. This actor helps
    * to achieve this. It can be used to implement virtual sources and sinks
    * that receive their data from multiple sources and transfer their data
    * into multiple sinks. The idea is that a specialized sink implementation
    * pushes its data to an actor instance, while a specialized source
    * implementation retrieves it from there. Then sinks and sources can be
    * replaced independently of each other.
    *
    * Per default, an actor instance buffers a single chunk of data that is set
    * by a producer sink and queried by a consumer source. In special cases,
    * however, multiple chunks can be present in the buffer, for instance at
    * the end of the stream or when a chunk needs to be split that does not fit
    * into a buffer file.
    *
    * Since the buffering of stream data in files is a complex problem that is
    * subject to many subtle race conditions, the actor also plays an important
    * role for the coordination of buffer files. This is especially true when
    * reading from files. Here a number of non-trivial issues have to be
    * solved, e.g. sources whose size is initially unknown, sources that can
    * be skipped before they are fully read, messages of completed files and
    * sources arriving in non-deterministic order, etc. To address those
    * issues, the protocol of the actor has been extended.
    *
    * @param bufferFileSize the size of a buffer file
    * @param unknownSize    the size to use for sources for which the real
    *                       size is not known
    * @return the behavior of the bridge actor
    */
  private def sourceSinkBridgeActor(bufferFileSize: Int, unknownSize: Long): Behavior[SourceSinkBridgeCommand] =
    handleBridgeCommand(initialBridgeActorState(bufferFileSize, unknownSize))

  /**
    * The actual command handler function of the bridge actor.
    *
    * @param state the current state of the actor
    * @return the updated behavior function
    */
  private def handleBridgeCommand(state: BridgeActorState): Behavior[SourceSinkBridgeCommand] =
    Behaviors.receive {
      case (ctx, AddDataChunk(replyTo, data)) =>
        val stateWithChunk = state.addChunk(data)
        val nextChunks = stateWithChunk.chunks
        val (nextProducer, nextConsumer) = if nextChunks.isEmpty then
          replyTo.foreach(_ ! DataChunkProcessed())
          (None, state.consumer)
        else
          state.consumer.foreach(ctx.self ! _)
          (replyTo, None)
        handleBridgeCommand(
          stateWithChunk.copy(
            chunks = nextChunks,
            producer = nextProducer,
            consumer = nextConsumer
          )
        )

      case (ctx, msg@GetNextDataChunk(replyTo, _, srcIdx)) =>
        val nextState = state.chunks match
          case h :: t if h.sourceIndex <= srcIdx =>
            if h.sourceIndex == srcIdx then
              replyTo ! h
            else
              // Data from the previous source needs to be skipped.
              ctx.self ! msg
            val nextProducer = if t.isEmpty then
              state.producer.foreach(_ ! DataChunkProcessed())
              None
            else state.producer
            state.copy(
              chunks = t,
              producer = nextProducer,
              consumer = None
            )
          case _ :: _ => // The current source is complete.
            ctx.log.info("Sending EndOfData for source '{}'.", state.sourceCount)
            replyTo ! DataChunkResponse.EndOfData
            state.copy(consumer = None)
          case _ =>
            if state.sourceCount > srcIdx then
              replyTo ! DataChunkResponse.EndOfData
              state
            else
              state.copy(consumer = Some(msg))
        handleBridgeCommand(replyPrepareReadBufferFile(ctx, nextState))

      case (ctx, msg: PrepareReadBufferFile) if state.isFileFullyRead =>
        ctx.log.info("PrepareReadBufferFile {} at end of file.", msg)
        val nextState = handlePrepareReadBufferFile(ctx, msg, state)
        handleBridgeCommand(nextState)

      case (ctx, msg: PrepareReadBufferFile) =>
        ctx.log.info("PrepareReadBufferFile {}.", msg)
        handleBridgeCommand(state.copy(fileRequest = Some(msg)))

      case (ctx, GetSourceLimit) =>
        ctx.log.info("Limit for current source is unavailable. Stored value is {}.", state.limitUpdate)
        handleBridgeCommand(state.copy(limitUnknown = true))

      //      case (ctx, SourceCompleted(result)) if result.size < 0 || result.bytesRead < result.size =>
      //        ctx.log.info("Handling a canceled source with result {} at position {}.", result, state.bytesProcessed)
      //        val resultSize = if result.size >= 0 then result.size
      //        else state.limitUpdate getOrElse result.size
      //        val skipPos = if resultSize < 0 then
      //          (state.fileCount + 1L) * state.bufferFileSize + 1
      //        else
      //          result.startOffset + resultSize
      //        ctx.log.info("Setting skip position to {}.", skipPos)
      //        val skippedChunks = applySkipUntil(state.chunks, state.bytesProcessed, skipPos)
      //        val nextProducer = if state.producer.isDefined && skippedChunks.isEmpty then
      //          state.producer.get ! DataChunkProcessed()
      //          None
      //        else state.producer
      //        handleBridgeCommand(
      //          replyPrepareReadBufferFile(
      //            ctx,
      //            state.copy(
      //              chunks = skippedChunks,
      //              skipUntil = skipPos,
      //              producer = nextProducer,
      //              limitUpdate = None,
      //              limitUnknown = false
      //            )
      //          )
      //        )

      case (ctx, SourceCompleted(result)) =>
        ctx.log.info("Handling a completed source with index {}.", result.sourceIndex)
        if result.sourceIndex == state.sourceCount then
          if state.currentProcessed < state.sourceSize(result.sourceIndex) then
            ctx.log.info("Source '{}' has been canceled early.", result.sourceIndex)
            state.producer.foreach(_ ! DataChunkProcessed())
            handleBridgeCommand(state.copy(chunks = Nil, producer = None, skipSource = state.sourceCount))
          else Behaviors.same
        else Behaviors.same

      case (ctx, PlaylistEnd) =>
        ctx.log.info("Received PlaylistEnd command.")
        state.consumer.foreach(ctx.self ! _)
        handleBridgeCommand(state.copy(chunks = state.chunks :+ EndOfStreamChunk))

      case (ctx, StopBridgeActor) =>
        ctx.log.info("Bridge actor stopped.")
        Behaviors.stopped
    }

  /**
    * Handles a [[PrepareReadBufferFile]] message and computes the next actor
    * state.
    *
    * @param ctx   the actor context
    * @param msg   the message to handle
    * @param state the current actor state
    * @return the next actor state
    */
  private def handlePrepareReadBufferFile(ctx: ActorContext[SourceSinkBridgeCommand],
                                          msg: PrepareReadBufferFile,
                                          state: BridgeActorState): BridgeActorState =
    val skipSources = if state.fileOverlap then 1 else 0
    val nextOverlap = msg.offsets.last.endOffset < 0
    val skipFile = state.isSkipping && nextOverlap && msg.offsets.size == 1
    val fileSkipIncrement = if skipFile then state.bufferFileSize else 0
    val nextFileCount = state.fileCount + 1

    val nextState = state.copy(
      fileOverlap = nextOverlap,
      bytesProcessed = state.bytesProcessed + fileSkipIncrement,
      currentProcessed = state.currentProcessed + fileSkipIncrement,
      fileRequest = None,
      fileCount = nextFileCount
    )

    val bufferFileConfig = NextBufferFileConfig(skipSources, skipFile)
    ctx.log.info("Sending config for next buffer file: {}.", bufferFileConfig)
    msg.replyTo ! bufferFileConfig
    val nextStateWithSizes = nextState.updateSourceSizes(msg.offsets)
    ctx.log.info("Updates source sizes: {}.", nextStateWithSizes.sourceSizes)
    nextStateWithSizes

  /**
    * Checks whether there is a pending request to prepare a buffer file. If
    * this is the case and all condition are met, the request is now answered.
    *
    * @param ctx   the actor context
    * @param state the current actor state
    * @return the updated actor state
    */
  private def replyPrepareReadBufferFile(ctx: ActorContext[SourceSinkBridgeCommand],
                                         state: BridgeActorState): BridgeActorState =
    state.fileRequest.filter(_ => state.isFileFullyRead)
      .map(request => handlePrepareReadBufferFile(ctx, request, state))
      .getOrElse(state)

  /**
    * Generates the name of the buffer file with the given index.
    *
    * @param index the index
    * @return the name of the buffer file with this index
    */
  private def bufferFileName(index: Int): String = String.format(BufferFilePattern, index)

  /**
    * Returns a [[Path]] to a buffer file with a specific index.
    *
    * @param config the configuration of the buffered source
    * @param index  the index of the buffer file
    * @tparam SRC the type of stream sources
    * @tparam SNK the type of stream sinks
    * @return the [[Path]] to the requested buffer file
    */
  private def resolveBufferFile[SRC, SNK](config: BufferedPlaylistSourceConfig[SRC, SNK],
                                          index: Int): Path =
    config.bufferFolder.resolve(bufferFileName(index))

  /**
    * Deletes a file from the buffer.
    *
    * @param log    the logging adapter
    * @param config the configuration of the buffered source
    * @param index  the index of the buffer file to delete
    * @tparam SRC the type of stream sources
    * @tparam SNK the type of stream sinks
    */
  private def deleteBufferFile[SRC, SNK](log: LoggingAdapter,
                                         config: BufferedPlaylistSourceConfig[SRC, SNK],
                                         index: Int): Unit =
    val bufferFile = resolveBufferFile(config, index)
    log.info("Deleting buffer file {}.", bufferFile)
    try
      config.bufferDeleteFunc(bufferFile)
    catch
      case e: Exception =>
        log.error(e, "Could not delete buffer file {}.", bufferFile)

  /**
    * Determines the sources that are actually stored in the current
    * buffer file. Due to race conditions (an end of a data source is
    * reported before the buffer file completed notification is
    * processed), it can happen that the data in the tracked list of
    * sources is incorrect - it then contains more data than was written
    * to the file. This function cleans this up and returns a corrected
    * list of sources plus a list with sources floating over to the next
    * buffer file.
    *
    * @param sourcesInFile    the data of sources written to the current file
    * @param optCurrentSource the optional current source
    * @param bufferFileNo     the index of the current buffer file (1-based)
    * @param bufferFileSize   the size of buffer file
    * @tparam SRC the type of the source
    * @return a tuple with the final list of sources in the current file and
    *         the sources for the next file
    */
  private def correctSourcesInCurrentBufferFile[SRC](sourcesInFile: List[BufferedSource[SRC]],
                                                     optCurrentSource: => Option[BufferedSource[SRC]],
                                                     bufferFileNo: Int,
                                                     bufferFileSize: Long):
  (List[BufferedSource[SRC]], List[BufferedSource[SRC]]) =
    val bufferFileEndOffset = bufferFileNo * bufferFileSize
    val (contained, overflow) = sourcesInFile.span(_.endOffset < bufferFileEndOffset)
    if overflow.isEmpty then
      val actSources = optCurrentSource.fold(contained) { src =>
        contained :+ src
      }
      (actSources, Nil)
    else
      val actSources = contained :+ overflow.head.copy(endOffset = -1)
      (actSources, overflow)

  /**
    * A resolver function for sources stored in the buffer.
    *
    * @param src the source to be resolved
    * @return the resolved source
    */
  private def resolveSourceInBuffer[SRC](src: SourceInBuffer[SRC]): Future[AudioStreamPlayerStage.AudioStreamSource] =
    src.resolveSource()

  /**
    * Computes the size of a source for reading from a buffer file. If the
    * source spans multiple buffer files, its size is unknown initially. It
    * then has to be set later via a [[Promise]]. This function handles these
    * cases.
    *
    * @param bufferedSource the object describing the source
    * @tparam SRC the type of the source
    * @return the initial size for this source
    */
  private def sourceSize[SRC](bufferedSource: BufferedSource[SRC]): Long =
    if bufferedSource.endOffset < 0 then
      -1
    else
      bufferedSource.endOffset - bufferedSource.startOffset

  /**
    * Creates a new bridge actor instance with a unique name based on the given
    * configuration.
    *
    * @param system      the actor system
    * @param config      the configuration
    * @param unknownSize the size to use for sources for which the real size is
    *                    not yet known
    * @param nameSuffix  the suffix for the actor name
    * @tparam SRC the type of the stream source
    * @tparam SNK the type of the stream sink
    * @return a reference to the newly spawned actor instance
    */
  private def spawnBridgeActor[SRC, SNK](system: classic.ActorSystem,
                                         config: BufferedPlaylistSourceConfig[SRC, SNK],
                                         unknownSize: Long,
                                         nameSuffix: String): ActorRef[SourceSinkBridgeCommand] =
    system.spawn(sourceSinkBridgeActor(config.bufferFileSize, unknownSize), s"${config.sourceName}_$nameSuffix")

  /**
    * Provides an [[ExecutionContext]] in implicit scope from the given actor
    * system.
    *
    * @param system the actor system
    * @return the execution context
    */
  private given executionContext(using system: classic.ActorSystem): ExecutionContext = system.dispatcher

  /**
    * Provides a typed actor system from an untyped one in implicit scope.
    *
    * @param system the classic actor system
    * @return the typed actor system
    */
  private given typedActorSystem(using system: classic.ActorSystem): ActorSystem[_] = system.toTyped
end BufferedPlaylistSource
