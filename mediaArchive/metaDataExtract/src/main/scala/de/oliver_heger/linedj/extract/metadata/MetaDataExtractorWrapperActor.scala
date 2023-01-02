/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport, PathUtils}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.{MetaDataProcessingResult, MetaDataProcessingSuccess, ProcessMetaDataFile}
import de.oliver_heger.linedj.utils.ChildActorFactory

object MetaDataExtractorWrapperActor {
  /**
    * Creates a processing result for a media file which is not supported.
    * This result contains only a minimum set of information.
    *
    * @param p the process request
    * @return the minimum result
    */
  private def createUnsupportedResult(p: ProcessMetaDataFile): MetaDataProcessingSuccess = {
    val data = MediaMetaData(size = p.fileData.size)
    val result = p.resultTemplate.copy(metaData = data)
    result
  }

  /**
    * A function for handling a request to process a media file. The function
    * expects the request and returns an actor and a message that has to be
    * sent to this actor. There is an additional boolean flag which indicates
    * whether the actor should expect a result message.
    */
  private type ProcessFunc = ProcessMetaDataFile => (ActorRef, Any, Boolean)

  private class MetaDataExtractorWrapperActorImpl(ef: ExtractorActorFactory)
    extends MetaDataExtractorWrapperActor(ef) with ChildActorFactory with CloseSupport

  /**
    * Returns a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param extractorFactory the factory for creating extractor actors
    * @return ''Props'' to create a new instance
    */
  def apply(extractorFactory: ExtractorActorFactory): Props =
    Props(classOf[MetaDataExtractorWrapperActorImpl], extractorFactory)
}

/**
  * An actor that wraps actors for actually extracting meta data from media
  * files.
  *
  * Instances of this actor are used during processing of media files. For each
  * file encountered this actor is called. The task of this actor is to
  * determine the correct extractor actor for the media file (there may be
  * different extractors for specific file types). This extractor is then
  * invoked to process the file, and the response is sent to the original
  * caller. For unsupported file types, dummy meta data is constructed.
  *
  * The actor uses an [[ExtractorActorFactory]] to create extractor actors on
  * demand. It also implements cancellation handling. Note that the actor
  * cannot be reused after it has been canceled.
  *
  * @param extractorFactory the factory for extractor actors
  */
class MetaDataExtractorWrapperActor(extractorFactory: ExtractorActorFactory) extends Actor
  with ActorLogging {
  this: ChildActorFactory with CloseSupport =>

  import MetaDataExtractorWrapperActor._

  /** A cache for process functions. */
  private var cache = ProcessFuncCache(Map.empty, Map.empty, List.empty)

  /**
    * A map storing information about the requests currently in progress. This
    * is needed to find the correct receivers for result messages.
    */
  private var requests = Map.empty[MediaFileUri, ActorRef]

  override def receive: Receive = receiveProcessing

  /**
    * Receive function for normal processing mode.
    */
  private def receiveProcessing: Receive = {
    case p: ProcessMetaDataFile =>
      log.info("Meta data processing request for {}.", p.fileData.path)
      val ext = PathUtils extractExtension p.fileData.path.toString
      cache = ensureExtensionCanBeHandled(ext, cache)
      val (a, m, f) = cache.functions(ext)(p)
      a ! m
      if (f) {
        requests += p.resultTemplate.uri -> sender()
      }

    case result: MetaDataProcessingResult =>
      log.info("Received processing result for {}.", result.uri)
      requests.get(result.uri) foreach (_ ! result)
      requests -= result.uri

    case CloseRequest =>
      onCloseRequest(subject = self, target = sender(), factory = this,
        deps = cache.extractors)
      context become receiveClosed
  }

  /**
    * Receive function after the actor has been closed. In this state no more
    * requests and results are accepted.
    */
  private def receiveClosed: Receive = {
    case CloseComplete =>
      onCloseComplete()
  }

  /**
    * Returns a cache object that can handle the given file extension. If
    * necessary, the cache is updated with a process function.
    *
    * @param ext the file extension
    * @param c   the current cache
    * @return the updated cache
    */
  private def ensureExtensionCanBeHandled(ext: String, c: ProcessFuncCache): ProcessFuncCache = {
    if (c canHandle ext) c
    else {
      val optProps = extractorFactory.extractorProps(ext, self)
      optProps match {
        case Some(props) =>
          c.updateForExtractor(ext, props, createChildActor(props))
        case None =>
          c updateForUnsupportedExtension ext
      }
    }
  }

  /**
    * A process function for unsupported file extensions. This function sends
    * dummy meta data directly to the sender.
    *
    * @param req the process request
    * @return a tuple with process information
    */
  private def unsupportedProcessFunc(req: ProcessMetaDataFile): (ActorRef, Any, Boolean) =
    (sender(), createUnsupportedResult(req), false)

  /**
    * A process function that delegates a request to process a media file to
    * an extractor actor.
    *
    * @param actor the extractor actor
    * @param req   the process request
    * @return a tuple with process information
    */
  private def extractorProcessFunc(actor: ActorRef)(req: ProcessMetaDataFile):
  (ActorRef, Any, Boolean) =
    (actor, req, true)

  /**
    * An internally used class for caching information about processor
    * functions.
    *
    * @param functions        a map that assigns file extensions to process
    *                         functions
    * @param extractorMapping a map with the extractor actors that have already
    *                         been created
    * @param extractors       a list with all extractor actors
    */
  private case class ProcessFuncCache(functions: Map[String, ProcessFunc],
                                      extractorMapping: Map[Props, ProcessFunc],
                                      extractors: List[ActorRef]) {
    /**
      * Checks whether the specified file extension is already known to this
      * cache.
      *
      * @param ext the file extension
      * @return a flag whether this extension can be handled
      */
    def canHandle(ext: String): Boolean = functions contains ext

    /**
      * Returns an updated instance that can handle the specified file
      * extension by delegating to an extractor actor. This method also
      * checks whether an extractor for the specified Props already exists.
      *
      * @param ext       the file extension
      * @param props     the creation ''Props'' for the extractor
      * @param extractor the extractor actor (lazily evaluated)
      * @return the updated instance
      */
    def updateForExtractor(ext: String, props: Props, extractor: => ActorRef):
    ProcessFuncCache = {
      lazy val extRef = extractor
      extractorMapping get props match {
        case Some(f) =>
          copy(functions = functions + (ext -> f))
        case None =>
          val func: ProcessFunc = extractorProcessFunc(extRef)
          ProcessFuncCache(functions = functions + (ext -> func),
            extractorMapping = extractorMapping + (props -> func),
            extractors = extRef :: extractors)
      }
    }

    /**
      * Returns an updated instance with the specified unsupported file
      * extension.
      *
      * @param ext the file extension
      * @return the updated instance
      */
    def updateForUnsupportedExtension(ext: String): ProcessFuncCache =
      copy(functions = functions + (ext -> unsupportedProcessFunc))
  }

}
