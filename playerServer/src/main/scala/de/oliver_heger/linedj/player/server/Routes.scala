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

import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer

/**
  * An object defining the routes supported by the player server.
  */
object Routes: 
  /**
    * Returns the top-level route for the player server.
    *
    * @param config      the [[PlayerServerConfig]]
    * @param radioPlayer the [[RadioPlayer]]
    * @return the top-level route of the server
    */
  def route(config: PlayerServerConfig, radioPlayer: RadioPlayer): Route =
    uiRoute(config)

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
