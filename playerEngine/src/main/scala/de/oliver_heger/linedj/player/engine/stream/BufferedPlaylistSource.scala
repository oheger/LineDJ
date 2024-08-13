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

package de.oliver_heger.linedj.player.engine.stream

import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink, Source}
import org.apache.pekko.stream.stage.*
import org.apache.pekko.util.{ByteString, Timeout}

import java.nio.file.{Files, Path}
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
  private val InfiniteTimeout = 30.days

  /**
    * Constant for the default chunk size when reading from a buffer file.
    */
  private val ReadChunkSize = 16384

  /**
    * Constant for a special data chunks that indicates the end of the current
    * stream. This is used by the bridge sources and sinks to manage the
    * lifecycle of streams.
    */
  private val EndOfStreamChunk = DataChunkResponse.DataChunk(ByteString.empty)

  /**
    * Constant for a message that is sent to the bridge actor to indicate the
    * end of the current stream.
    */
  private val EndOfStreamMessage = AddDataChunk(None, ByteString.empty)

  /** Pattern for generating names for buffer files. */
  private val BufferFilePattern = "buffer%02d.dat"

  /**
    * A function for providing a [[Sink]] for writing to a file in the buffer.
    * Such a function can be passed to a buffered source in its configuration.
    * It is then invoked whenever a new file in the buffer needs to be created.
    */
  type BufferSinkFunc = Path => Sink[ByteString, Any]

  /**
    * Definition of a trait that is used for the sources exposed downstream by
    * the buffered source. It allows resolving the source from the buffer. For
    * this resolve operation, a lot of internal details about the way buffering
    * is implemented are required. The main purpose of this trait is to hide
    * this complexity and to prevent that internal data types need to be
    * exposed.
    */
  trait SourceInBuffer:
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
                          (using system: classic.ActorSystem): Source[SourceInBuffer, MAT] =
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
  AudioStreamPlayerStage.AudioStreamPlayerConfig[SourceInBuffer, SNK] =
    config.copy(sourceResolverFunc = resolveSourceInBuffer)
      .asInstanceOf[AudioStreamPlayerStage.AudioStreamPlayerConfig[SourceInBuffer, SNK]]

  /**
    * A default function for opening a file in the buffer. This is used by
    * [[BufferedPlaylistSourceConfig]] if no custom function is provided for
    * this purpose.
    *
    * @param path the path to the buffer file
    * @return a [[Sink]] for writing to this file
    */
  def defaultBufferSink(path: Path): Sink[ByteString, Any] = FileIO.toPath(path)

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
        private val bufferFileCompleteCallback =
          getAsyncCallback[Try[BridgeSourceCompletionReason]](handleBufferFileCompleted)

        /** The actor for source/sink multiplexing. */
        private var bridgeActor: ActorRef[SourceSinkBridgeCommand] = _

        /** A buffer for elements to be pushed downstream. */
        private var dataBuffer: List[BufferFileWritten[SRC]] = Nil

        /** Stores the sources added to the current buffer file. */
        private var sourcesInCurrentBufferFile = List.empty[BufferedSource[SRC]]

        /** Stores information about the source that is currently processed. */
        private var currentSource: Option[(SRC, AudioStreamPlayerStage.AudioStreamSource)] = _

        /** A counter for the buffer files that have been created. */
        private var bufferFileCount = 0

        /**
          * A counter to track the bytes that have been written into the buffer
          * (in total). This is used to determine the start and end offsets of
          * the single data sources.
          */
        private var bytesProcessed = 0L

        /**
          * A flag that allows keeping track whether a buffer file is currently
          * written. Write operations are paused if the buffer directory
          * already contains 2 buffer files.
          */
        private var bufferFileWriteInProgress = false

        /**
          * A flag that indicates when the playlist from upstream is finished.
          * This information needs to be stored, since this stage cannot be
          * completed directly when the last file was written, but only when
          * all data has been passed downstream.
          */
        private var playlistFinished = false

        setHandler(in, new InHandler:
          override def onPush(): Unit = {
            val src = grab(in)
            fillSourceIntoBuffer(src)
          }

          // Note: This must be overridden, since the base implementation immediately completes the stage.
          override def onUpstreamFinish(): Unit =
            log.info("Playlist source finished.")
        )

        setHandler(out, new OutHandler:
          override def onPull(): Unit = {
            dataBuffer match
              case ::(head, next) =>
                push(out, head)
                dataBuffer = next
                createAndFillBufferFile()
              case Nil =>
                if playlistFinished then completeStage()
          }
        )

        override def preStart(): Unit =
          super.preStart()
          pull(in)

          if !Files.isDirectory(config.bufferFolder) then
            log.info("Buffer folder '{}' does not exist. Creating it now.", config.bufferFolder)
            Files.createDirectories(config.bufferFolder)

          bridgeActor = system.spawn(sourceSinkBridgeActor(), s"${config.sourceName}_fillBridgeActor")
          createAndFillBufferFile()

        override def postStop(): Unit =
          bridgeActor ! StopBridgeActor
          super.postStop()

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
              resolvedSource.source.runWith(
                Sink.fromGraph(new BridgeSink(bridgeActor, srcData._1, resolvedSource.url, bytesProcessed))
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
          if isClosed(in) then
            log.info("End of playlist.")
            bridgeActor ! EndOfStreamMessage
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
          if !bufferFileWriteInProgress && dataBuffer.size < 1 then
            bufferFileWriteInProgress = true
            bufferFileCount += 1
            val bufferFile = config.bufferFolder.resolve(bufferFileName(bufferFileCount))
            log.info("Creating buffer file {}.", bufferFile)

            val bufferFileSource = Source.fromGraph(new BridgeSource(bridgeActor, config.bufferFileSize))
            val bufferFileSink = config.bufferSinkFunc(bufferFile)
            bufferFileSource.toMat(bufferFileSink)(Keep.left).run().onComplete(bufferFileCompleteCallback.invoke)

        /**
          * A callback method that is invoked when a buffer file has been fully
          * written. Depending on the provided completion reason, the next
          * actions need to be taken.
          *
          * @param triedResult the result from the source for the file
          */
        private def handleBufferFileCompleted(triedResult: Try[BridgeSourceCompletionReason]): Unit =
          bufferFileWriteInProgress = false
          log.info("Current buffer file has been fully written: {}.", triedResult)

          triedResult match
            case Success(completionReason) =>
              val (allSourcesInFile, nextFile) = correctSourcesInCurrentBufferFile(
                sourcesInCurrentBufferFile.reverse,
                currentSource.map(src => BufferedSource(src._1, src._2.url, bytesProcessed, -1)),
                bufferFileCount,
                config.bufferFileSize
              )
              pushData(BufferFileWritten(allSourcesInFile))
              sourcesInCurrentBufferFile = nextFile

              completionReason match
                case BridgeSourceCompletionReason.LimitReached => createAndFillBufferFile()
                case BridgeSourceCompletionReason.PlaylistStreamEnd => playlistFinished = true
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
    extends GraphStage[FlowShape[BufferFileWritten[SRC], SourceInBuffer]]:
    private val in: Inlet[BufferFileWritten[SRC]] = Inlet("ReadBufferFlowStage.in")
    private val out: Outlet[SourceInBuffer] = Outlet("ReadBufferFlowStage.out")

    override def shape: FlowShape[BufferFileWritten[SRC], SourceInBuffer] = new FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with StageLogging:
        /**
          * A callback function that gets invoked when an audio source has been
          * read from the buffer.
          */
        private val onSourceComplete = getAsyncCallback[Try[BridgeSourceCompletionReason]](handleSourceCompleted)

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

        /**
          * A counter for the number of sources that are currently in progress.
          * This is needed, since there can be overlap with a source that has
          * just been started and one that is about to complete. In order to
          * correctly determine when the stage can be completed, it has to be
          * checked that no source is active anymore.
          */
        private var inProgressCount = 0

        /**
          * A flag that stores whether the size of the last source in the 
          * current buffer file is unknown. This means that it has to be
          * updated once it becomes available.
          */
        private var lastSourceSizeUnknown = false

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            val fileWritten = grab(in)
            log.info("Received buffer file: {}.", fileWritten)
            val headSource = fileWritten.sources.head
            val newSources = if lastSourceSizeUnknown then fileWritten.sources.tail
            else fileWritten.sources
            bufferedSources = bufferedSources :++ newSources

            if headSource.endOffset >= 0 && lastSourceSizeUnknown then
              val size = headSource.endOffset - headSource.startOffset
              log.info("Updating size of source '{}' to {}.", headSource.source, size)
              bridgeActor ! UpdateSourceLimit(size)

            lastSourceSizeUnknown = fileWritten.sources.last.endOffset < 0
            readNextBufferFile()
            pushNextSource()

          // Note: This must be overridden, since the base implementation immediately completes the stage.
          override def onUpstreamFinish(): Unit =
            log.info("onUpstreamFinished - all buffer files have been written.")
        )

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            pushNextSource()
        )

        override def preStart(): Unit =
          super.preStart()
          bridgeActor = system.spawn(sourceSinkBridgeActor(), "readBufferFlowStage_bridgeActor")
          pull(in)

        override def postStop(): Unit =
          bridgeActor ! StopBridgeActor
          super.postStop()

        /**
          * Starts reading of the next buffer file. For this file, a source is
          * created together with a sink that passes the content to the bridge
          * actor. From there, the data can be divided again to the single data
          * sources.
          */
        private def readNextBufferFile(): Unit =
          bufferFileCount += 1
          val bufferFile = config.bufferFolder.resolve(bufferFileName(bufferFileCount))
          log.info("Start reading of buffer file '{}'.", bufferFile)

          val bufferFileSource = FileIO.fromPath(bufferFile)
          val bufferFileSink = Sink.fromGraph(new BridgeSink(bridgeActor, bufferFile, bufferFile.toString, 0))
          bufferFileSource.runWith(bufferFileSink).onComplete(onBufferFileComplete.invoke)

        /**
          * Pushes an element downstream that allows reading the next audio
          * source in the current buffer file if this is currently possible.
          */
        private def pushNextSource(): Unit =
          if isAvailable(out) && bufferedSources.nonEmpty && inProgressCount == 0 then
            val currentSource = bufferedSources.head
            bufferedSources = bufferedSources.tail
            inProgressCount += 1
            val size = sourceSize(currentSource)
            log.info("Start processing audio source '{}' with size {}..", currentSource.source, size)

            push(out, new SourceInBuffer:
              override def resolveSource(): Future[AudioStreamPlayerStage.AudioStreamSource] = Future {
                val promiseResult = Promise[BridgeSourceCompletionReason]()
                promiseResult.future.onComplete(onSourceComplete.invoke)

                val source = Source.fromGraph(new BridgeSource(bridgeActor,
                  size,
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
          if !isClosed(in) then
            pull(in)

        /**
          * A callback function that is invoked when an audio source completes.
          * Here it needs to be handled whether bytes have to be skipped or the
          * playlist is complete.
          *
          * @param result the result from the source
          */
        private def handleSourceCompleted(result: Try[BridgeSourceCompletionReason]): Unit =
          log.info("Current source completed with result {}.", result)
          inProgressCount -= 1

          if isClosed(in) && bufferedSources.isEmpty && inProgressCount == 0 then
            log.info("All sources have been processed. Completing stage.")
            completeStage()
          else
            pushNextSource()
  end ReadBufferFlowStage

  /**
    * An internal [[Sink]] implementation that passes data from upstream to a
    * bridge actor instance. This is used to combine the data of multiple
    * sources.
    *
    * @param bridgeActor the bridge actor instance
    * @param source      the source that is currently processed
    * @param url         the URL for the current source
    * @param startOffset the start offset for the current source
    * @param system      the implicit actor system
    * @tparam SRC the type to represent sources
    */
  private class BridgeSink[SRC](bridgeActor: ActorRef[SourceSinkBridgeCommand],
                                source: SRC,
                                url: String,
                                startOffset: Long)
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
            given timeout: Timeout = InfiniteTimeout

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
        )

        override def preStart(): Unit =
          pull(in)

        override def postStop(): Unit =
          promiseMat.success(BufferedSource(source, url, startOffset, startOffset + bytesProcessed))

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
  private enum BridgeSourceCompletionReason:
    case PlaylistStreamEnd
    case LimitReached

  /**
    * An internal [[Source]] implementation that reads its data from a bridge
    * actor instance. It is possible to limit the number of bytes issued by
    * this source. This is necessary for instance to make sure that files in
    * the buffer do not exceed their configured capacity. When reading sources 
    * from the buffer that span multiple buffer files, the size of the source 
    * is not known initially. In this case, the value -1 is passed, and the
    * limit is set later on via the bridge actor. To distinguish between the
    * cases that the end of the playlist was reached or the limit of the 
    * source, the source materializes a flag with the reason for its end.
    *
    * Since the materialized value of the source is only available if there is
    * full control over the whole stream (which is for instance not the case
    * when reading data from a buffer file), there is an alternative way to
    * obtain the result produced by the source: The [[Promise]] that is also
    * used to provide the materialized result can be passed when constructing
    * an instance. Then a client can react when it gets completed.
    *
    * @param bridgeActor  the bridge actor instance
    * @param initialLimit the initial number of bytes to be issued
    *                     (this may be changed later); can be less than 0
    *                     to indicate that no limit is enforced
    * @param promiseMat   the promise to use for generating the result
    * @param system       the actor system
    */
  private class BridgeSource(bridgeActor: ActorRef[SourceSinkBridgeCommand],
                             initialLimit: Long,
                             promiseMat: Promise[BridgeSourceCompletionReason] = Promise())
                            (using system: ActorSystem[_])
    extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[BridgeSourceCompletionReason]]:
    private val out: Outlet[ByteString] = Outlet("BridgeSource")

    override def shape: SourceShape[ByteString] = SourceShape(out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
    (GraphStageLogic, Future[BridgeSourceCompletionReason]) =
      val logic = new GraphStageLogic(shape):
        /**
          * A callback to handle data chunks that have been retrieved
          * asynchronously from the bridge actor.
          */
        private val onDataCallback = getAsyncCallback[Try[DataChunkResponse]](dataAvailable)

        /** The number of bytes that has been emitted so far. */
        private var bytesProcessed = 0L

        /** The maximum number of bytes to issue. */
        private var limit = initialLimit

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            requestNextChunk()

          override def onDownstreamFinish(cause: Throwable): Unit =
            promiseMat.failure(cause)
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
          given timeout: Timeout = InfiniteTimeout

          given ec: ExecutionContext = system.executionContext

          val maxSize = if limit < 0 then ReadChunkSize
          else math.min(limit - bytesProcessed, ReadChunkSize).toInt
          bridgeActor.ask[DataChunkResponse](ref => GetNextDataChunk(ref, maxSize)).onComplete(onDataCallback.invoke)

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
            case Success(EndOfStreamChunk) =>
              completeStageWithResult(BridgeSourceCompletionReason.PlaylistStreamEnd)
            case Success(DataChunkResponse.DataChunk(chunk)) =>
              push(out, chunk)
              bytesProcessed += chunk.size
              completeIfLimitReached()
            case Success(DataChunkResponse.LimitChanged(updatedLimit)) =>
              updateLimit(updatedLimit)
              if !completeIfLimitReached() then requestNextChunk()
            case Failure(exception) => cancelStage(exception)

        /**
          * Checks whether the current source limit has been reached. If so,
          * this stage is completed accordingly.
          *
          * @return a flag whether the limit has been reached
          */
        private def completeIfLimitReached(): Boolean =
          if limit >= 0 && bytesProcessed >= limit then
            completeStageWithResult(BridgeSourceCompletionReason.LimitReached)
            true
          else
            false

        /**
          * Sets this stage to completed and sets the materialized value to the
          * given result.
          *
          * @param result the result for this stage
          */
        private def completeStageWithResult(result: BridgeSourceCompletionReason): Unit =
          completeStage()
          promiseMat.success(result)

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
  private enum DataChunkResponse:
    /**
      * A response containing the requested data chunk.
      *
      * @param data the data
      */
    case DataChunk(data: ByteString)

    /**
      * A response that notifies the client about a change in the limit for the
      * current data source.
      *
      * @param updatedLimit the new limit for this source
      */
    case LimitChanged(updatedLimit: Long)

  /**
    * A message class sent by the bridge actor as a reply to an
    * [[AddDataChunk]] command. The message tells the receiver that now
    * capacity is available to receive another chunk of data.
    */
  private case class DataChunkProcessed()

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
    * @param replyTo the actor to send the chunk to
    * @param maxSize the maximum size of the chunk
    */
  private case class GetNextDataChunk(replyTo: ActorRef[DataChunkResponse],
                                      maxSize: Int) extends SourceSinkBridgeCommand

  /**
    * A command class processed by the bridge actor that notifies it about a
    * change in the limit for the currently processed data source. This change
    * needs to be forwarded to a waiting consumer of data.
    *
    * @param limit the new limit for this source
    */
  private case class UpdateSourceLimit(limit: Long) extends SourceSinkBridgeCommand

  /**
    * A command to indicate to the bridge actor that the limit for the current
    * source is not known. The actor then passes the limit to the source as 
    * soon as it becomes available.
    */
  private case object GetSourceLimit extends SourceSinkBridgeCommand

  /**
    * A command to notify the bridge actor to stop itself.
    */
  private case object StopBridgeActor extends SourceSinkBridgeCommand

  /**
    * Returns the behavior of an actor that bridges between multiple sources
    * and sinks. For the use case at hand, multiple sources may need to be
    * combined to be filled into a buffer file. Also, the content of sources
    * may need to be split to fit into multiple buffer files. This actor helps
    * achieving this. It can be used to implement virtual sources and sinks
    * that receive their data from multiple sources and transfer their data
    * into multiple sinks. The idea is that a specialized sink implementation
    * pushes its data to an actor instance, while a specialized source
    * implementation retrieves it from there. Then sinks and sources can be
    * replaced independently from each other.
    *
    * Per default, an actor instance buffers a single chunk of data that is set
    * by a producer sink and queried by a consumer source. In special cases,
    * however, multiple chunks can be present in the buffer, for instance at
    * the end of the stream or when a chunk needs to be split that does not fit
    * into a buffer file.
    *
    * @return the behavior of the bridge actor
    */
  private def sourceSinkBridgeActor(): Behavior[SourceSinkBridgeCommand] =
    handleBridgeCommand(Nil, None, None, None, false)

  /**
    * The actual command handler function of the bridge actor.
    *
    * @param chunks       the current buffer of chunks
    * @param producer     the reference to the producer of the chunk
    * @param consumer     the reference to the consumer of chunks
    * @param limitUpdate  a pending update of the limit for the currently
    *                     processed source
    * @param limitUnknown a flag that indicates whether for the current source
    *                     the limit is not known; then this source is sent a 
    *                     notification when there is an update
    * @return the updated behavior function
    */
  private def handleBridgeCommand(chunks: List[DataChunkResponse.DataChunk],
                                  producer: Option[ActorRef[DataChunkProcessed]],
                                  consumer: Option[GetNextDataChunk],
                                  limitUpdate: Option[Long],
                                  limitUnknown: Boolean): Behavior[SourceSinkBridgeCommand] =
    Behaviors.receive {
      case (ctx, AddDataChunk(replyTo, data)) =>
        consumer.foreach(ctx.self ! _)
        handleBridgeCommand(chunks :+ DataChunkResponse.DataChunk(data), replyTo, None, limitUpdate, limitUnknown)

      case (ctx, msg@GetNextDataChunk(replyTo, maxSize)) =>
        limitUpdate match
          case Some(limit) if limitUnknown =>
            ctx.log.info("Notifying client about a change in the limit of the current source to {}.", limit)
            replyTo ! DataChunkResponse.LimitChanged(limit)
            handleBridgeCommand(chunks, producer, None, None, false)
          case _ =>
            chunks match
              case h :: t if h.data.size <= maxSize =>
                replyTo ! h
                val nextProducer = if t.isEmpty then
                  producer.foreach(_ ! DataChunkProcessed())
                  None
                else producer
                handleBridgeCommand(t, nextProducer, None, limitUpdate, limitUnknown)
              case h :: t => // The current chunk needs to be split.
                val (consumed, remaining) = h.data.splitAt(maxSize)
                replyTo ! DataChunkResponse.DataChunk(consumed)
                handleBridgeCommand(
                  DataChunkResponse.DataChunk(remaining) :: t,
                  producer,
                  None,
                  limitUpdate,
                  limitUnknown
                )
              case _ =>
                handleBridgeCommand(Nil, producer, Some(msg), limitUpdate, limitUnknown)

      case (ctx, UpdateSourceLimit(limit)) =>
        ctx.log.info("UpdateSourceLimit({})", limit)
        consumer match
          case Some(client) if limitUnknown =>
            ctx.log.info("Notifying pending client about a change in the limit of the current source to {}.", limit)
            client.replyTo ! DataChunkResponse.LimitChanged(limit)
            handleBridgeCommand(chunks, producer, None, None, false)
          case _ =>
            handleBridgeCommand(chunks, producer, None, Some(limit), limitUnknown)

      case (ctx, GetSourceLimit) =>
        ctx.log.info("Limit for current source is unavailable. Stored value is {}.", limitUpdate)
        handleBridgeCommand(chunks, producer, consumer, limitUpdate, limitUnknown = true)

      case (ctx, StopBridgeActor) =>
        ctx.log.info("Bridge actor stopped.")
        Behaviors.stopped
    }

  /**
    * Generates the name of the buffer file with the given index.
    *
    * @param index the index
    * @return the name of the buffer file with this index
    */
  private def bufferFileName(index: Int): String = String.format(BufferFilePattern, index)

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
  private def resolveSourceInBuffer(src: SourceInBuffer): Future[AudioStreamPlayerStage.AudioStreamSource] =
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
