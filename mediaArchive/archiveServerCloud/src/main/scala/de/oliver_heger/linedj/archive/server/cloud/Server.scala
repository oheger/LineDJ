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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.server.common.ServerRunner
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor as classics

object Server:
  /** The name of this server. */
  final val ServerName = "archiveServerCloud"

  private val log = LogManager.getLogger(classOf[Server])

  /**
    * The main function of the cloud archive server. Creates the central actor 
    * system and starts the server instance.
    *
    * @param args command line arguments
    */
  def main(args: Array[String]): Unit =
    given classics.ActorSystem = classics.ActorSystem(ServerName)

    val runner = new ServerRunner

    val server = new Server(runner)
    server.run()
end Server

/**
  * The main class for the cloud archive server. The class launches a server
  * implementation that loads the content of an arbitrary number of cloud 
  * archives defined in the configuration. It provides access to the single
  * media, artists, albums, and songs contained in these archives via a REST 
  * API.
  *
  * @param runner the object for launching the server
  */
class Server(runner: ServerRunner):

  import Server.*

  /**
    * Starts the archive server.
    */
  def run(): Unit =
    log.info("Server.run()")
    val controller = new Controller() with SystemPropertyAccess {}
    val handle = runner.launch(ServerName, controller)

    handle.awaitShutdown()
    log.info("Server shut down.")
