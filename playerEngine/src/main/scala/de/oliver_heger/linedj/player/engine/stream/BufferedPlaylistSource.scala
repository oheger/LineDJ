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
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink, Source}
import org.apache.pekko.stream.stage.*
import org.apache.pekko.stream.*
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
    * A timeout value to be used when actually no timeout is required from the
    * business logic point of view, but syntactically one is needed.
    */
  private val InfiniteTimeout = 30.days

  /**
    * Constant for a special data chunks that indicates the end of the current
    * stream. This is used by the bridge sources and sinks to manage the
    * lifecycle of streams.
    */
  private val EndOfStreamChunk = DataChunk(ByteString.empty)

  /**
    * Constant for a message that is sent to the bridge actor to indicate the
    * end of the current stream.
    */
  private val EndOfStreamMessage = AddDataChunk(None, EndOfStreamChunk.data)

  /** Pattern for generating names for buffer files. */
  private val BufferFilePattern = "buffer%02d.dat"

  /**
    * A class defining the configuration of a [[BufferedPlaylistSource]].
    *
    * @param streamPlayerConfig the configuration for playing audio streams;
    *                           this is required in order to retrieve data
    *                           sources for stream elements
    * @param bufferFolder       the folder in which to create buffer files
    * @param bufferFileSize     the maximum size of each buffer file
    * @tparam SRC the type of the elements in the playlist
    * @tparam SNK the type expected by the sink of the stream
    */
  case class BufferedPlaylistSourceConfig[SRC, SNK](streamPlayerConfig:
                                                    AudioStreamPlayerStage.AudioStreamPlayerConfig[SRC, SNK],
                                                    bufferFolder: Path,
                                                    bufferFileSize: Int)

  def apply[SRC, SNK, MAT](config: BufferedPlaylistSourceConfig[SRC, SNK],
                           source: Source[SRC, MAT])
                          (using system: classic.ActorSystem): Source[SRC, MAT] = ???

  /**
    * A data class describing an audio source that has been written into a
    * buffer file. If a buffer file contains data from a source, an instance of
    * this class is added to the corresponding [[BufferFileWritten]] message.
    * The size of the source is only available after it has been fully written.
    * If only parts of the source ended up in the buffer file, it is -1. Note
    * that this is not the total size of the source, but only the part that is
    * contained in this buffer file.
    *
    * @param source the source
    * @param size   the size of the source in the current buffer file if known
    *               or -1 otherwise
    * @tparam SRC the type to represent sources
    */
  private[stream] case class BufferedSource[SRC](source: SRC,
                                                 size: Long)

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

        /** The source that is currently processed. */
        private var currentSource: Option[SRC] = _

        /** A counter for the buffer files that have been created. */
        private var bufferFileCount = 0

        /**
          * A counter to track the bytes written into the current buffer file.
          * This is mainly used to correctly calculate the size of sources that
          * span over multiple files.
          */
        private var bytesInCurrentBufferFile = 0L

        /**
          * Stores the remaining capacity in the current buffer file when a new
          * source is started. This value is updated every time a new data
          * source is started. It is also used to calculate the size in a
          * [[BufferedSource]] object.
          */
        private var remainingBufferFileCapacityForSource = 0L

        /**
          * The number of the buffer file that was active when a new source was
          * started. This is used to discover sources spanning multiple buffer
          * files.
          */
        private var sourceStartBufferFile = 0

        /** The typed actor system in implicit scope. */
        private given typedSystem: ActorSystem[_] = system.toTyped

        /** The execution context in implicit scope. */
        private given ec: ExecutionContext = system.dispatcher

        setHandler(in, new InHandler:
          override def onPush(): Unit = {
            val src = grab(in)
            currentSource = Some(src)
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
              case Nil =>
          }
        )

        override def preStart(): Unit =
          super.preStart()
          pull(in)

          if !Files.isDirectory(config.bufferFolder) then
            log.info("Buffer folder '{}' does not exist. Creating it now.", config.bufferFolder)
            Files.createDirectories(config.bufferFolder)

          bridgeActor = system.spawn(sourceSinkBridgeActor(), "bridgeActor")
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
            dataBuffer = dataBuffer.appended(data)

        /**
          * Handles the processing of the given source. This function resolves
          * the source and starts the stream that writes its data into the 
          * buffer.
          *
          * @param source the source to be processed
          */
        private def fillSourceIntoBuffer(source: SRC): Unit =
          remainingBufferFileCapacityForSource = config.bufferFileSize - bytesInCurrentBufferFile
          sourceStartBufferFile = bufferFileCount

          (for
            resolvedSource <- config.streamPlayerConfig.sourceResolverFunc(source)
            fillResult <- resolvedSource.source.runWith(Sink.fromGraph(new BridgeSink(bridgeActor, source)))
          yield fillResult).onComplete(onSourceCompleted.invoke)

        /**
          * A callback method that is invoked when one of the data sources from
          * upstream has been fully read. Then the next source needs to be
          * requested.
          *
          * @param triedResult the result from reading the source
          */
        private def handleSourceCompleted(triedResult: Try[BufferedSource[SRC]]): Unit =
          // The current source should always be defined when this function is called.
          currentSource.foreach { source =>
            log.info("Current source '{}' is complete: {}.", currentSource, triedResult)
            val bufferedSource = adjustToCurrentFile(triedResult.getOrElse(BufferedSource(source, 0)))
            bytesInCurrentBufferFile += bufferedSource.size
            sourcesInCurrentBufferFile = bufferedSource :: sourcesInCurrentBufferFile

            if isClosed(in) then
              log.info("End of playlist.")
              bridgeActor ! EndOfStreamMessage
            else
              pull(in)
          }
          currentSource = None

        /**
          * Calculates the size of the buffered source relative to the current
          * buffer file, so that it covers only the part which is actually
          * written in this file. This is expected downstream when sources are
          * again extracted from the buffer. An adjustment is only required if
          * the source is stored over multiple buffer files.
          *
          * @param bufSrc the [[BufferedSource]] from the writing stream
          * @return the adjusted [[BufferedSource]]
          */
        private def adjustToCurrentFile(bufSrc: BufferedSource[SRC]): BufferedSource[SRC] =
          if sourceStartBufferFile != bufferFileCount then
            val relativeSize = (bufSrc.size - remainingBufferFileCapacityForSource) % config.bufferFileSize
            bufSrc.copy(size = relativeSize)
          else
            bufSrc

        /**
          * Creates a new file in the buffer and starts a stream that populates
          * it from the sources obtained from upstream. This stream completes
          * when the maximum size of the buffer file was reached or the
          * playlist stream ends.
          */
        private def createAndFillBufferFile(): Unit =
          bufferFileCount += 1
          sourcesInCurrentBufferFile = Nil
          bytesInCurrentBufferFile = 0
          val bufferFile = config.bufferFolder.resolve(bufferFileName(bufferFileCount))
          log.info("Creating buffer file {}.", bufferFile)

          val bufferFileSource = Source.fromGraph(new BridgeSource(bridgeActor, config.bufferFileSize))
          val bufferFileSink = FileIO.toPath(bufferFile)
          bufferFileSource.toMat(bufferFileSink)(Keep.left).run().onComplete(bufferFileCompleteCallback.invoke)

        /**
          * A callback method that is invoked when a buffer file has been fully
          * written. Depending on the provided completion reason, the next
          * actions need to be taken.
          *
          * @param triedResult the result from the source for the file
          */
        private def handleBufferFileCompleted(triedResult: Try[BridgeSourceCompletionReason]): Unit =
          log.info("Current buffer file has been fully written: {}.", triedResult)
          triedResult.foreach { completionReason =>
            val allSourcesInFile = currentSource.fold(sourcesInCurrentBufferFile) { source =>
              // This source is stored over multiple buffer files.
              BufferedSource(source, -1) :: sourcesInCurrentBufferFile
            }
            pushData(BufferFileWritten(allSourcesInFile.reverse))

            completionReason match
              case BridgeSourceCompletionReason.LimitReached => createAndFillBufferFile()
              case BridgeSourceCompletionReason.PlaylistStreamEnd => completeStage()
          }
  end FillBufferFlowStage

  /**
    * An internal [[Sink]] implementation that passes data from upstream to a
    * bridge actor instance. This is used to combine the data of multiple
    * sources.
    *
    * @param bridgeActor the bridge actor instance
    * @param source      the source that is currently processed
    * @param system      the implicit actor system
    * @tparam SRC the type to represent sources
    */
  private class BridgeSink[SRC](bridgeActor: ActorRef[SourceSinkBridgeCommand],
                                source: SRC)
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
            setMaterializedValue()

          override def onUpstreamFailure(ex: Throwable): Unit =
            super.onUpstreamFailure(ex)
            log.error(ex, "Source '{}' failed after {} bytes.", source, bytesProcessed)
            setMaterializedValue()
        )

        override def preStart(): Unit =
          pull(in)

        /**
          * Sets the materialized value for this sink, which is a
          * [[BufferedSource]] containing the number of written bytes. This has
          * to be done when upstream finishes, no matter if successful or with 
          * an error. 
          */
        private def setMaterializedValue(): Unit =
          promiseMat.success(BufferedSource(source, bytesProcessed))

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
    * the buffer do not exceed their configured capacity. To distinguish 
    * between the cases that the end of the playlist was reached or the limit
    * of the source, the source materializes a flag with the reason for its
    * end.
    *
    * @param bridgeActor the bridge actor instance
    * @param limit       the maximum number of bytes to be issued
    * @param system      the actor system
    */
  private class BridgeSource(bridgeActor: ActorRef[SourceSinkBridgeCommand],
                             limit: Long)
                            (using system: ActorSystem[_])
    extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[BridgeSourceCompletionReason]]:
    private val out: Outlet[ByteString] = Outlet("BridgeSource")

    override def shape: SourceShape[ByteString] = SourceShape(out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
    (GraphStageLogic, Future[BridgeSourceCompletionReason]) =
      val promiseMat = Promise[BridgeSourceCompletionReason]()
      val logic = new GraphStageLogic(shape):
        /**
          * A callback to handle data chunks that have been retrieved
          * asynchronously from the bridge actor.
          */
        private val onDataCallback = getAsyncCallback[Try[DataChunk]](dataAvailable)

        /** The number of bytes that has been emitted so far. */
        private var bytesProcessed = 0L

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            given timeout: Timeout = InfiniteTimeout

            given ec: ExecutionContext = system.executionContext

            val maxSize = (limit - bytesProcessed).toInt
            bridgeActor.ask[DataChunk](ref => GetNextDataChunk(ref, maxSize)).onComplete(onDataCallback.invoke)
        )

        /**
          * Handles responses from the bridge actor for requests to the next
          * chunk of data. The chunk is passed downstream if it is not empty.
          * An empty chunk indicates the end of the stream. Failures are
          * handled by failing this stage.
          *
          * @param triedChunk a ''Try'' with the next chunk of data
          */
        private def dataAvailable(triedChunk: Try[DataChunk]): Unit =
          triedChunk match
            case Success(EndOfStreamChunk) =>
              completeStage()
              promiseMat.success(BridgeSourceCompletionReason.PlaylistStreamEnd)
            case Success(chunk) =>
              push(out, chunk.data)
              bytesProcessed += chunk.data.size
              if bytesProcessed >= limit then
                completeStage()
                promiseMat.success(BridgeSourceCompletionReason.LimitReached)
            case Failure(exception) => cancelStage(exception)

      (logic, promiseMat.future)
  end BridgeSource

  /**
    * A message sent by the bridge actor as a response to a
    * [[GetNextDataChunk]] command. It contains the requested data.
    *
    * @param data the data of this chunk
    */
  private case class DataChunk(data: ByteString)

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
  private case class GetNextDataChunk(replyTo: ActorRef[DataChunk],
                                      maxSize: Int) extends SourceSinkBridgeCommand

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
    handleBridgeCommand(Nil, None, None)

  /**
    * The actual command handler function of the bridge actor.
    *
    * @param chunks   the current buffer of chunks
    * @param producer the reference to the producer of the chunk
    * @param consumer the reference to the consumer of chunks
    * @return the updated behavior function
    */
  private def handleBridgeCommand(chunks: List[DataChunk],
                                  producer: Option[ActorRef[DataChunkProcessed]],
                                  consumer: Option[GetNextDataChunk]): Behavior[SourceSinkBridgeCommand] =
    Behaviors.receive {
      case (ctx, AddDataChunk(replyTo, data)) =>
        consumer.foreach(ctx.self ! _)
        handleBridgeCommand(chunks :+ DataChunk(data), replyTo, consumer)

      case (_, msg@GetNextDataChunk(replyTo, maxSize)) =>
        chunks match
          case h :: t if h.data.size <= maxSize =>
            replyTo ! h
            val nextProducer = if t.isEmpty then
              producer.foreach(_ ! DataChunkProcessed())
              None
            else producer
            handleBridgeCommand(t, nextProducer, None)
          case h :: t => // The current chunk needs to be split.
            val (consumed, remaining) = h.data.splitAt(maxSize)
            replyTo ! DataChunk(consumed)
            handleBridgeCommand(DataChunk(remaining) :: t, producer, None)
          case _ =>
            handleBridgeCommand(Nil, producer, Some(msg))

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
end BufferedPlaylistSource
