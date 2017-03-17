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

package de.oliver_heger.linedj.archivehttp

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern.{ask, pipe}
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.util.{ByteString, Timeout}
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import de.oliver_heger.linedj.shared.archive.media.MediumID

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
  * Test class for using Akka HTTP.
  */
object HttpTest {
  /** The path to files containing meta data. */
  val MetaDataPath = "/metadata/"

  /** Maximum length of output strings. */
  private val MaxStringLength = 256

  /**
    * Expected parameters:
    *  - Host name of the server
    *  - user name
    *  - password
    *
    * @param args command line arguments
    */
  def main(args: Array[String]): Unit = {
    if (args.length != 3) {
      println("Parameters: hostname, user name, password")
      System exit 1
    }

    val decider: Supervision.Decider = _ => Supervision.Resume
    val ArchiveUrl = s"https://${args.head}/"
    implicit val system = ActorSystem("HTTPSystem")
    implicit val execContext = system.dispatcher
    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    implicit val timeout = Timeout(1.second)

    val actorSettings = system.actorOf(Props(classOf[DemoProcessingActor], "Settings: "),
      "settingsActor")
    val actorMetaData = system.actorOf(Props(classOf[DemoProcessingActor], "MetaData: "),
      "metaDataActor")
    val userName = args(1)
    val password = args(2)
    val httpContext = Http().createClientHttpsContext(AkkaSSLConfig())
    val pooledClientFlow = Http()
      .cachedHostConnectionPoolHttps[ActorRef](args.head, connectionContext = httpContext)

    println("Starting download...")
    val futureStream = filesToDownload(ArchiveUrl)
      .mapConcat[(HttpRequest, ActorRef)](md => createRequestsForMedium(md, userName, password,
      actorSettings, actorMetaData))
      .via(pooledClientFlow)
      .mapAsyncUnordered(2)(t => t._2 ? t._1)
      .runForeach(bs => println("Result: " + shorten(bs.asInstanceOf[ByteString])))

    println("Result of downloads: " + Await.result(futureStream, 1.minute))
    println("Terminating...")
    val futureTerminate = Http().shutdownAllConnectionPools() andThen {
      case _ => system.terminate()
    }
    println("Terminated: " + Await.result(futureTerminate, 10.seconds))
  }

  /**
    * Reads the entity of the given response and returns it as a
    * ''ByteString''.
    *
    * @param response the response to be read
    * @param prefix   a prefix to add before the response's bytes
    * @return a future with the read response
    */
  def readResponse(response: HttpResponse, prefix: String)
                  (implicit materializer: ActorMaterializer): Future[ByteString] =
    response.entity.dataBytes.runFold(ByteString(prefix))(_ ++ _)

  /**
    * Returns an abbreviated string for the given byte string.
    *
    * @param bs the byte string
    * @return the shortened string
    */
  private def shorten(bs: ByteString): String = {
    val s = bs.utf8String
    if (s.length > MaxStringLength) s.substring(0, MaxStringLength) + "..."
    else s
  }

  /**
    * Creates a request to be executed for a given relative path.
    *
    * @param uri      the URI to the file to be loaded
    * @param user     the user name
    * @param password the password
    * @return the request
    */
  private def createRequest(uri: String, user: String, password: String): HttpRequest = {
    HttpRequest(uri = uri,
      headers = List(Authorization(BasicHttpCredentials(user, password))))
  }

  /**
    * Creates a request for the settings file of a medium.
    *
    * @param mediumDef the medium definition
    * @param user      the user name
    * @param password  the password
    * @return the request for the settings file
    */
  private def createSettingsRequest(mediumDef: HttpMediumDef, user: String,
                                    password: String): HttpRequest =
    createRequest(mediumDef.mediumID.mediumDescriptionPath.get, user, password)

  /**
    * Creates a request for the meta data file of a medium.
    *
    * @param mediumDef the medium definition
    * @param user      the user name
    * @param password  the password
    * @return the request for the meta data file
    */
  private def createMetaDataRequest(mediumDef: HttpMediumDef, user: String,
                                    password: String): HttpRequest =
    createRequest(mediumDef.mediumID.archiveComponentID + mediumDef.metaDataUri, user, password)

  /**
    * Creates the requests to process a medium definition.
    *
    * @param mediumDef     the medium definition
    * @param user          the user name
    * @param password      the password
    * @param settingsActor the actor processing settings
    * @param metaDataActor the actor processing meta data
    * @return an Iterable with the requests to be executed
    */
  private def createRequestsForMedium(mediumDef: HttpMediumDef, user: String,
                                      password: String, settingsActor: ActorRef,
                                      metaDataActor: ActorRef): collection.immutable.Iterable[
    (HttpRequest, ActorRef)] = {
    val requests = List((createSettingsRequest(mediumDef, user, password), settingsActor),
      (createMetaDataRequest(mediumDef, user, password), metaDataActor))
    println("Produced requests: " + requests)
    requests
  }

  /**
    * Creates the medium ID for a medium defined by its relative path.
    *
    * @param archiveUrl the URL of the media archive
    * @param path       the relative path
    * @return the medium ID
    */
  private def createMediumID(archiveUrl: String, path: String): MediumID =
    MediumID(archiveUrl + path, Some(archiveUrl + path + "/playlist.settings"),
      archiveUrl)

  /**
    * Returns a source with files to be downloaded from the media archive.
    *
    * @param url the URL of the archive server
    * @return the source with files to be downloaded
    */
  private def filesToDownload(url: String): Source[HttpMediumDef, NotUsed] = {
    val defs = List(
      HttpMediumDef(createMediumID(url, "music/alben5"), MetaDataPath + "d625c112.mdt"),
      HttpMediumDef(createMediumID(url, "music/Beethoven"), MetaDataPath + "c63e8851_wrong.mdt"),
      HttpMediumDef(createMediumID(url, "music/FemaleRock1"), MetaDataPath + "ed51c4b.mdt"),
      HttpMediumDef(createMediumID(url, "music/LacunaCoil"), MetaDataPath + "f349061d.mdt"),
      HttpMediumDef(createMediumID(url, "music/Orchestra"), MetaDataPath + "87614ace.mdt"))
    Source(defs)
  }
}

case class HttpMediumDef(mediumID: MediumID, metaDataUri: String)

/**
  * A demo actor class which simulates a processing operation.
  *
  * @param header the header to identify this actor
  */
class DemoProcessingActor(header: String) extends Actor {
  private implicit val materializer = ActorMaterializer()

  import context.dispatcher

  override def receive: Receive = {
    case Success(resp) =>
      val response = resp.asInstanceOf[HttpResponse]
      println("Response from archive: " + response.status)
      if (response.status.isSuccess()) {
        // let timeout in error case
        val currentSender = sender()
        val future = HttpTest.readResponse(response, header)
        future.pipeTo(currentSender)
      }
    case Failure(exception) =>
      sender() ! Status.Failure(exception)
  }
}
