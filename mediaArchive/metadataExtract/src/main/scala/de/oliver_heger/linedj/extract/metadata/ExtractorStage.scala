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

package de.oliver_heger.linedj.extract.metadata

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingResult, MetadataProcessingSuccess}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.stage.*
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
  * A stage implementation that handles the metadata extraction for a stream of
  * media files.
  *
  * For each file path passing received from upstream, the stage obtains an
  * extractor function from an [[ExtractorFunctionProvider]]. If such a 
  * function is available, the stage invokes it and waits for the resulting
  * future to complete or the configured timeout is reached. Failures and
  * timeouts cause processing error results. If no extractor function is 
  * available for the file, the result contains only empty metadata.
  *
  * @param extractorFunctionProvider the provider for extractor functions
  * @param extractTimeout            the timeout for processing a single file
  * @param mediumID                  the ID for the current medium
  * @param uriMappingFunc            the function to create the file URI
  * @param system                    the current actor system
  */
private class ExtractorStage(extractorFunctionProvider: ExtractorFunctionProvider,
                             extractTimeout: FiniteDuration,
                             mediumID: MediumID,
                             uriMappingFunc: Path => MediaFileUri)
                            (using system: ActorSystem)
  extends GraphStage[FlowShape[FileData, MetadataProcessingResult]]:
  val in: Inlet[FileData] = Inlet[FileData]("ExtractorStage.in")
  val out: Outlet[MetadataProcessingResult] = Outlet[MetadataProcessingResult]("ExtractorStage.out")

  override val shape: FlowShape[FileData, MetadataProcessingResult] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogicWithLogging(shape):
      /** The callback for incoming metadata results. */
      private val onExtractorResult = getAsyncCallback[(Path, Try[MediaMetadata])](handleExtractorResult)

      /** Stores the path of the audio file that is currently processed. */
      private var currentPath: Path = _

      /** Stores a flag whether the upstream has been finished. */
      private var upstreamFinished = false

      /** The execution context for asynchronous operations. */
      given ExecutionContext = system.dispatcher

      setHandler(in, new InHandler:
        override def onUpstreamFinish(): Unit =
          upstreamFinished = true
          if currentPath == null then
            completeStage()

        override def onPush(): Unit =
          val fileData = grab(in)
          extractorFuncFor(fileData.path) match
            case Some(extractorFunc) =>
              log.info("Extracting metadata for file '{}'.", fileData.path)
              currentPath = fileData.path
              extractorFunc(fileData.path, system).onComplete(onExtractorResult.invoke(fileData.path, _))
              scheduleOnce(fileData.path, extractTimeout)
            case None =>
              push(out, resultForUnsupportedFile(fileData.path))
      )

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          if upstreamFinished then
            completeStage()
          else
            pull(in)
      )

      override protected def onTimer(timerKey: Any): Unit =
        val timeoutPath = timerKey.asInstanceOf[Path]
        log.warning("Extractor function timed out for file '{}'.", timeoutPath)

        val timeoutException = new TimeoutException(s"Metadata extraction timed out for '$timeoutPath'.")
        handleExtractorResult(timeoutPath, Failure(timeoutException))

      /**
        * Handles a result produced asynchronously from an extractor function.
        *
        * @param result the tuple with the path of the processed file and the
        *               metadata returned by the extractor function
        */
      private def handleExtractorResult(result: (Path, Try[MediaMetadata])): Unit =
        val path = result._1
        if path == currentPath then
          val processingResult = createProcessingResult(path, result._2)
          push(out, processingResult)
          currentPath = null
          cancelTimer(path)

      /**
        * Tries to obtain an extractor function for the given audio file path.
        * This function determines the file extension and asks the provider for
        * an extractor function.
        *
        * @param path the path in question
        * @return an [[Option]] with the extractor function
        */
      private def extractorFuncFor(path: Path): Option[ExtractorFunctionProvider.ExtractorFunc] =
        val fileName = path.getFileName.toString
        val posExt = fileName.lastIndexOf('.')
        val extension = if posExt < 0 then ""
        else fileName.substring(posExt + 1)
        extractorFunctionProvider.extractorFuncFor(extension)

      /**
        * Generates a processing result for a file for which no extractor
        * function is available.
        *
        * @param path the path of the affected file
        * @return the result for this file
        */
      private def resultForUnsupportedFile(path: Path): MetadataProcessingResult =
        MetadataProcessingSuccess(mediumID, uriMappingFunc(path), MediaMetadata.UndefinedMediaData)

      /**
        * Generates a [[MetadataProcessingResult]] for the given parameters.
        * This can be a success or error result.
        *
        * @param path        the path of the processed file
        * @param triedResult the [[Try]] with extracted metadata
        * @return the processing result for this file
        */
      private def createProcessingResult(path: Path, triedResult: Try[MediaMetadata]): MetadataProcessingResult =
        val fileUri = uriMappingFunc(path)
        triedResult match
          case Success(metadata) =>
            MetadataProcessingSuccess(mediumID, fileUri, metadata)
          case Failure(exception) =>
            MetadataProcessingError(mediumID, fileUri, exception)
