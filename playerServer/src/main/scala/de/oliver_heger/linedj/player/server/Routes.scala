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
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer

import scala.concurrent.{Future, Promise}

/**
  * An object defining the routes supported by the player server.
  */
object Routes:
  /**
    * Returns the top-level route for the player server.
    *
    * @param config          the [[PlayerServerConfig]]
    * @param radioPlayer     the [[RadioPlayer]]
    * @param shutdownPromise a promise to trigger when the shutdown command is
    *                        invoked
    * @return the top-level route of the server
    */
  def route(config: PlayerServerConfig, radioPlayer: RadioPlayer, shutdownPromise: Promise[Done]): Route =
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
    * @return the API route
    */
  private def apiRoute(radioConfig: PlayerServerConfig,
                       radioPlayer: RadioPlayer,
                       shutdownPromise: Promise[Done]): Route =
    pathPrefix("api") {
      concat(
        shutdownRoute(shutdownPromise),
        radioRoute(radioPlayer)
      )
    }

  /**
    * Returns the route for handling API requests related to the radio player.
    *
    * @param radioPlayer the [[RadioPlayer]]
    * @return the route for the radio API
    */
  private def radioRoute(radioPlayer: RadioPlayer): Route =
    pathPrefix("radio") {
      pathPrefix("playback") {
        concat(
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
