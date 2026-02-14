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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorSystem

object Server:
  /** The name of this server. */
  final val ServerName = "playerServer"

/**
  * A class for creating and running the Player Server.
  *
  * The class uses a [[ServiceFactory]] to set up all required services and to
  * start the HTTP server. It then waits until the server shuts down.
  *
  * The player server configuration is loaded from the standard locations
  * supported by Commons Configuration. The name of the configuration file can
  * be specified via the [[Controller.PropConfigFileName]] system property.
  *
  * @param serviceFactory the factory for creating services
  * @param system         the actor system
  */
class Server(serviceFactory: ServiceFactory)
            (using system: ActorSystem):
  /** The logger. */
  private val log = LogManager.getLogger(classOf[Server])

  /**
    * Starts the server and all active components. The function then waits
    * until the server shuts down (which is triggered by a request to the
    * shutdown endpoint).
    */
  def run(): Unit =
    log.info("Server.run()")

    val controller = new Controller(serviceFactory) with SystemPropertyAccess {}
    val runner = serviceFactory.createServerRunner()

    val handle = runner.launch(Server.ServerName, controller)
    handle.awaitShutdown()

    log.info("Server shut down.")
