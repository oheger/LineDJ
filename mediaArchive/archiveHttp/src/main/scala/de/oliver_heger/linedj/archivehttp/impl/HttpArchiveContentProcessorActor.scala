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

package de.oliver_heger.linedj.archivehttp.impl

import akka.Done
import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream._
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.impl.HttpArchiveContentProcessorActor.RemoveKillSwitch
import de.oliver_heger.linedj.shared.archive.media.MediumID

import scala.concurrent.Future
import scala.util.Try

object HttpArchiveContentProcessorActor {

  /**
    * An internal message that tells the actor to remove the kill switch
    * registration with the given ID. This message is sent to ''self'' when
    * a stream has been completely processed.
    *
    * @param killSwitchID the ID of the ''KillSwitch''
    */
  private case class RemoveKillSwitch(killSwitchID: Int)

}

/**
  * An actor class that processes the content of an HTTP archive.
  *
  * This actor class processes the content document of an HTTP archive. For
  * each referenced medium it downloads the settings and the meta data files.
  * The responses of these download requests are sent to special processor
  * actors which extract the relevant information and pass it back to this
  * actor. All processing results are then sent to the manager actor which is
  * responsible for the HTTP archive currently processed.
  *
  * The download and processing of files from the archive is done in a single
  * stream. This actor configures the stream and materializes it. When the
  * stream has been fully processed the manager actor is notified.
  */
class HttpArchiveContentProcessorActor extends Actor with ActorLogging
  with CancelableStreamSupport {
  /** The materializer for streams. */
  private implicit val materializer = createMaterializer()

  import context.dispatcher

  override def receive: Receive = {
    case req: ProcessHttpArchiveRequest =>
      val (killSwitch, futStream) = materializeStream(req)
      val killSwitchID = registerKillSwitch(killSwitch)
      futStream andThen {
        case _ =>
          req.archiveActor ! HttpArchiveProcessingComplete(req.seqNo)
          self ! RemoveKillSwitch(killSwitchID)
      }

    case CancelProcessing =>
      cancelCurrentStreams()

    case RemoveKillSwitch(killSwitchID) =>
      unregisterKillSwitch(killSwitchID)
  }

  /**
    * Creates the stream for processing the specified archive request.
    * This stream loads all settings and meta data files of the media contained
    * in this HTTP archive. The method also returns a ''KillSwitch'' to cancel
    * stream processing on an external request.
    *
    * @param req the request to process the archive
    * @return a ''Future'' when the stream is done and a ''KillSwitch''
    */
  private [impl] def materializeStream(req: ProcessHttpArchiveRequest):
  (KillSwitch, Future[Done]) = {
    val sink = Sink.foreach(req.archiveActor.!)
    req.mediaSource
      .viaMat(KillSwitches.single)(Keep.right)
      .filter(md => md.metaDataPath != null && md.mediumDescriptionPath != null)
      .mapConcat(createRequestsForMedium(req, _))
      .via(req.clientFlow)
      .mapAsyncUnordered(req.archiveConfig.processorCount) { t =>
        processHttpResponse(req, t)
      }
    .toMat(sink)(Keep.both)
    .run()
  }

  /**
    * Creates a HTTP request for the specified path.
    *
    * @param path the path for the request
    * @param credentials the credentials for the request
    * @return the new request
    */
  private def createRequest(path: String, credentials: UserCredentials): HttpRequest =
    HttpRequest(uri = Uri(path),
      headers = List(Authorization(BasicHttpCredentials(credentials.userName,
        credentials.password))))

  /**
    * Returns a list with the requests to execute for a specific medium.
    *
    * @param req the request to process the archive
    * @param md  the description of the medium affected
    * @return a list with the requests to execute for this medium
    */
  private def createRequestsForMedium(req: ProcessHttpArchiveRequest, md: HttpMediumDesc):
  List[(HttpRequest, RequestData)] =
    List((createRequest(md.mediumDescriptionPath, req.archiveConfig.credentials),
      RequestData(md, req.settingsProcessorActor)),
      (createRequest(md.metaDataPath, req.archiveConfig.credentials),
        RequestData(md, req.metaDataProcessorActor)))

  /**
    * Processes a response received from the HTTP archive. The response now has
    * to be send to the correct processing actor.
    *
    * @param req the request to process the archive
    * @param t   the tuple with data received from the HTTP flow
    * @return a future for the message expected from the processor actor
    */
  private def processHttpResponse(req: ProcessHttpArchiveRequest,
                                  t: (Try[HttpResponse], RequestData)): Future[Any] = {
    val mediumID: MediumID = createMediumID(req, t._2.mediumDesc)
    val msg = ProcessResponse(mediumID, t._1, req.archiveConfig, req.seqNo)
    t._2.processorActor.ask(msg)(req.archiveConfig.processorTimeout)
  }

  /**
    * Creates a medium ID from the given medium description.
    *
    * @param req the processing request
    * @param md  the medium description
    * @return the ''MediumID''
    */
  private def createMediumID(req: ProcessHttpArchiveRequest, md: HttpMediumDesc)
  : MediumID = {
    val pos = md.mediumDescriptionPath.lastIndexOf('/')
    val mediumURI = md.mediumDescriptionPath.substring(0, pos)
    val mediumID = MediumID(mediumURI, Some(md.mediumDescriptionPath),
      req.archiveConfig.archiveURI.toString())
    mediumID
  }

  /**
    * Creates the object for materialization of streams.
    *
    * @return the ''ActorMaterializer''
    */
  private def createMaterializer(): ActorMaterializer = {
    val decider: Supervision.Decider = {
      e =>
        log.error(e, "Exception during stream processing!")
        Supervision.Resume
    }
    ActorMaterializer(ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider))
  }
}
