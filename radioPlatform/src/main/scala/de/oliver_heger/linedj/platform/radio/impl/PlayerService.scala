/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.radio.impl

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.player.server.model.RadioModel
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}

import scala.concurrent.Future

/**
  * Definition of constants related to the [[PlayerService]].
  */
object PlayerService:
  /** The base path of the REST API of the radio player server. */
  final val ApiPrefix = "/api/radio"

  /** The path to query the available radio sources. */
  final val SourcesPath = s"$ApiPrefix/sources"

  /** The path to query or manipulate the current radio source. */
  final val CurrentSourcePath = s"$ApiPrefix/sources/current"

  /** The base path for playback commands. */
  final val PlaybackPath = s"$ApiPrefix/playback"

  /** The path to start playback. */
  final val PlaybackStartPath = s"$PlaybackPath/start"

  /** The path to stop playback. */
  final val PlaybackStopPath = s"$PlaybackPath/stop"

  /** The path of the WebSocket endpoint that publishes radio events. */
  final val EventsPath = s"$ApiPrefix/events"
end PlayerService

/**
  * A service that provides high-level access to the REST API of the radio
  * player server.
  *
  * This trait shields clients from the low-level details of the HTTP API. It
  * offers methods for the operations required by the radio platform and
  * handles the conversion of JSON data to the corresponding model objects.
  */
trait PlayerService extends RadioModel.RadioJsonSupport:
  import PlayerService.*

  /**
    * Returns a reference to the actor that sends the HTTP requests to the
    * radio player server.
    *
    * @return the request sender actor
    */
  private[radio] def requestSender: ActorRef[HttpRequestSender.HttpCommand]

  /**
    * Sends the given request to the radio player server and returns a future
    * with the response.
    *
    * @param request the request to be sent
    * @return a future with the response
    */
  def sendRequest(request: HttpRequest): Future[HttpResponse]

  /**
    * Sends a request to the given URI and converts the JSON response to an
    * object of the desired type.
    *
    * @param uri the URI to request
    * @return a future with the converted response
    */
  def queryData[A](uri: String)(using unmarshaller: Unmarshaller[HttpResponse, A]): Future[A] =
    given ActorSystem = actorSystem
    for
      response <- sendRequest(HttpRequest(uri = uri))
      result <- Unmarshal(response).to[A]
    yield result

  /**
    * Queries the list of radio sources available on the server.
    *
    * @return a future with the available radio sources
    */
  def radioSources: Future[RadioModel.RadioSources] =
    queryData[RadioModel.RadioSources](SourcesPath)

  /**
    * Selects the radio source with the given ID.
    *
    * @param sourceID the ID of the radio source
    * @return a future that is completed when the source has been selected
    */
  def switchToSource(sourceID: String): Future[Unit] =
    sendCommand(s"$CurrentSourcePath/$sourceID")

  /**
    * Starts playback.
    *
    * @return a future that is completed when playback has been started
    */
  def startPlayback(): Future[Unit] =
    sendCommand(PlaybackStartPath)

  /**
    * Stops playback.
    *
    * @return a future that is completed when playback has been stopped
    */
  def stopPlayback(): Future[Unit] =
    sendCommand(PlaybackStopPath)

  /**
    * Returns the actor system used by this service.
    *
    * @return the actor system
    */
  protected def actorSystem: ActorSystem

  /**
    * Sends a POST request to the given URI.
    *
    * @param uri the URI to request
    * @return a future that is completed when the request has been processed
    */
  private def sendCommand(uri: String): Future[Unit] =
    given ActorSystem = actorSystem
    sendRequest(HttpRequest(method = HttpMethods.POST, uri = uri)).map(_ => ())
