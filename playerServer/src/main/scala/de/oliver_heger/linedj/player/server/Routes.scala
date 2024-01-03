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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{Directives, Route}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.{ExecutionContext, Promise}

/**
  * An object defining the routes supported by the player server.
  */
object Routes extends RadioModel.RadioJsonSupport:
  /**
    * A data class storing the different representations for a radio source.
    *
    * @param engineSource the source used by the player engine
    * @param modelSource  the source as defined in the model
    */
  private case class SourceData(engineSource: RadioSource,
                                modelSource: RadioModel.RadioSource)

  /**
    * Returns the top-level route for the player server.
    *
    * @param config          the [[PlayerServerConfig]]
    * @param radioPlayer     the [[RadioPlayer]]
    * @param shutdownPromise a promise to trigger when the shutdown command is
    *                        invoked
    * @param system          the current actor system
    * @return the top-level route of the server
    */
  def route(config: PlayerServerConfig, radioPlayer: RadioPlayer, shutdownPromise: Promise[Done])
           (implicit system: ActorSystem): Route =
    concat(
      apiRoute(config, radioPlayer, shutdownPromise),
      uiRoute(config)
    )

  /**
    * Returns the route for handling requests against the API of the player 
    * server.
    *
    * @param serverConfig    the [[PlayerServerConfig]]
    * @param radioPlayer     the [[RadioPlayer]]
    * @param shutdownPromise the promise to trigger shutdown
    * @param system          the current actor system
    * @return the API route
    */
  private def apiRoute(serverConfig: PlayerServerConfig,
                       radioPlayer: RadioPlayer,
                       shutdownPromise: Promise[Done])
                      (implicit system: ActorSystem): Route =
    pathPrefix("api") {
      concat(
        shutdownRoute(shutdownPromise),
        radioRoute(radioPlayer, serverConfig)
      )
    }

  /**
    * Returns the route for handling API requests related to the radio player.
    *
    * @param radioPlayer  the [[RadioPlayer]]
    * @param serverConfig the [[PlayerServerConfig]]
    * @param system       the current actor system
    * @return the route for the radio API
    */
  private def radioRoute(radioPlayer: RadioPlayer,
                         serverConfig: PlayerServerConfig)
                        (implicit system: ActorSystem): Route = {
    implicit val ec: ExecutionContext = system.dispatcher

    val (modelSourcesByName, sourcesByID, sourceToID) = radioSourceMappings(serverConfig.sourceConfig)

    pathPrefix("radio") {
      concat(
        pathPrefix("playback") {
          concat(
            get {
              val futState = radioPlayer.currentPlaybackState.map { state =>
                RadioModel.PlaybackStatus(state.playbackActive)
              }
              onSuccess(futState) { state =>
                complete(state)
              }
            },
            path("start") {
              post {
                radioPlayer.startPlayback()
                complete(StatusCodes.OK)
              }
            },
            path("stop") {
              post {
                radioPlayer.stopPlayback()
                complete(StatusCodes.OK)
              }
            }
          )
        },
        path("sources") {
          get {
            complete(RadioModel.RadioSources(modelSourcesByName.values.toList))
          }
        },
        pathPrefix("sources") {
          concat(
            path("current" / Remaining) { id =>
              post {
                sourcesByID.get(id) match
                  case Some(source) =>
                    radioPlayer.switchToRadioSource(source.engineSource)
                    serverConfig.optCurrentConfig foreach { currentConfig =>
                      currentConfig.setProperty(PlayerServerConfig.PropCurrentSource,
                        source.modelSource.name)
                    }
                    complete(StatusCodes.OK)
                  case None =>
                    complete(StatusCodes.NotFound)
              }
            },
            path("current") {
              get {
                parameter("full".withDefault(false)) { full =>
                  val futState = radioPlayer.currentPlaybackState

                  if full then
                    val futSourceStatus = futState map { state =>
                      RadioModel.RadioSourceStatus(
                        currentSourceId = state.selectedSource flatMap sourceToID.get,
                        replacementSourceId = if state.currentSource == state.selectedSource then None
                        else state.currentSource flatMap sourceToID.get,
                        state.titleInfo.map(_.title)
                      )
                    }
                    onSuccess(futSourceStatus) {
                      complete(_)
                    }
                  else
                    val futSource = radioPlayer.currentPlaybackState.map { state =>
                      state.selectedSource flatMap { source =>
                        serverConfig.sourceConfig.namedSources.find(_._2.uri == source.uri)
                      } flatMap { (name, _) => modelSourcesByName.get(name) }
                    }
                    onSuccess(futSource) {
                      case Some(source) => complete(source)
                      case None => complete(StatusCodes.NoContent)
                    }
                }
              }
            }
          )
        },
        path("events") {
          val futFlow = MessageActor.newMessageFlow(radioPlayer, sourceToID)
          onSuccess(futFlow) { flow =>
            handleWebSocketMessages(flow)
          }
        }
      )
    }
  }

  /**
    * Returns the route for the web UI of the player server. This route exposes
    * the content of a configurable directory for GET operations. The URL path
    * of this route is also defined in the configuration.
    *
    * @param config the configuration
    * @return the route for the web UI of the player
    */
  private def uiRoute(config: PlayerServerConfig): Route =
    pathPrefix(config.uiPathPrefix) {
      getFromDirectory(config.uiContentFolder.toAbsolutePath.toString)
    }

  /**
    * Returns a route that shuts down the HTTP server by completing a promise.
    *
    * @param shutdownPromise the promise that triggers shutdown
    * @return the shutdown route
    */
  private def shutdownRoute(shutdownPromise: Promise[Done]): Route =
    path("shutdown") {
      post {
        shutdownPromise.success(Done)
        complete(StatusCodes.Accepted)
      }
    }

  /**
    * Generates [[RadioModel.RadioSource]] objects from the given radio sources
    * configuration and maps allowing fast access to these and the original
    * radio sources from the configuration based on calculated IDs.
    *
    * @param radioSourceConfig the configuration for radio sources
    * @return a tuple with maps for accessing the radio sources
    */
  private def radioSourceMappings(radioSourceConfig: RadioSourceConfig):
  (Map[String, RadioModel.RadioSource], Map[String, SourceData], Map[RadioSource, String]) =
    val favorites = radioSourceConfig.favorites.zipWithIndex.map { (t, index) =>
      t._2 -> (t._1, index)
    }.toMap

    val mappings = radioSourceConfig.namedSources.map { (name, source) =>
      val id = calculateID(name, source.uri)
      val favoriteData = favorites.get(source)
      val modelSource = RadioModel.RadioSource(
        id,
        name,
        radioSourceConfig.ranking(source),
        favoriteData.map(_._2) getOrElse -1,
        favoriteData.map(_._1) getOrElse name
      )
      val modelSourceMapping = name -> modelSource
      val engineSourceMapping = id -> SourceData(source, modelSource)
      (modelSourceMapping, engineSourceMapping)
    }.unzip

    val sourceIdMap = mappings._2.map(t => (t._2.engineSource, t._1))
    (mappings._1.toMap, mappings._2.toMap, sourceIdMap.toMap)

  /**
    * Generates an ID for a radio source based on its name and URI by applying
    * a hash algorithm.
    *
    * @param name the name of the radio source
    * @param uri  the URI of the source
    * @return the ID for this source
    */
  private[server] def calculateID(name: String, uri: String): String =
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(name.getBytes(StandardCharsets.UTF_8))
    digest.update(uri.getBytes(StandardCharsets.UTF_8))
    Base64.getEncoder.encodeToString(digest.digest())
