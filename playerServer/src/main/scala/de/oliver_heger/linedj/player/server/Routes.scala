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

package de.oliver_heger.linedj.player.server

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.server.model.RadioModel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * An object defining the routes supported by the player server.
  */
object Routes extends RadioModel.RadioJsonSupport:
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
    * @param radioConfig     the [[PlayerServerConfig]]
    * @param radioPlayer     the [[RadioPlayer]]
    * @param shutdownPromise the promise to trigger shutdown
    * @param system          the current actor system
    * @return the API route
    */
  private def apiRoute(radioConfig: PlayerServerConfig,
                       radioPlayer: RadioPlayer,
                       shutdownPromise: Promise[Done])
                      (implicit system: ActorSystem): Route =
    pathPrefix("api") {
      concat(
        shutdownRoute(shutdownPromise),
        radioRoute(radioPlayer, radioConfig.sourceConfig)
      )
    }

  /**
    * Returns the route for handling API requests related to the radio player.
    *
    * @param radioPlayer       the [[RadioPlayer]]
    * @param radioSourceConfig the configuration for the radio sources
    * @param system            the current actor system
    * @return the route for the radio API
    */
  private def radioRoute(radioPlayer: RadioPlayer,
                         radioSourceConfig: RadioSourceConfig)
                        (implicit system: ActorSystem): Route = {
    implicit val ec: ExecutionContext = system.dispatcher

    val radioSources = radioSourcesMap(radioSourceConfig)

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
        pathPrefix("sources") {
          path("current") {
            get {
              val futSource = radioPlayer.currentPlaybackState.map { state =>
                state.currentSource flatMap { source =>
                  radioSourceConfig.namedSources.find(_._2.uri == source.uri)
                } flatMap { (name, _) => radioSources.values.find(_.name == name) }
              }
              onSuccess(futSource) {
                case Some(source) => complete(source)
                case None => complete(StatusCodes.NoContent)
              }
            }
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
    * Generates a map with [[RadioModel.RadioSource]] objects allowing direct
    * access to the sources based on their IDs.
    *
    * @param radioSourceConfig the configuration for radio sources
    * @return the map with the sources
    */
  private def radioSourcesMap(radioSourceConfig: RadioSourceConfig): Map[String, RadioModel.RadioSource] =
    radioSourceConfig.namedSources.map { (name, source) =>
      name -> RadioModel.RadioSource(calculateID(name, source.uri), name, radioSourceConfig.ranking(source))
    }.toMap

  /**
    * Generates an ID for a radio source based on its name and URI by applying
    * a hash algorithm.
    *
    * @param name the name of the radio source
    * @param uri  the URI of the source
    * @return the ID for this source
    */
  private def calculateID(name: String, uri: String): String =
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(name.getBytes(StandardCharsets.UTF_8))
    digest.update(uri.getBytes(StandardCharsets.UTF_8))
    Base64.getEncoder.encodeToString(digest.digest())
