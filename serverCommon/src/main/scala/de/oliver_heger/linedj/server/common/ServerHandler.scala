/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.server.common

import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import org.apache.pekko.actor as classic

import scala.concurrent.ExecutionContext

object ServerHandler:
  /**
    * A data class providing access to a number of services that can be used by
    * a concrete handler implementation, e.g. to create actors.
    *
    * @param system               the classic actor system
    * @param managingActorFactory an actor factory with management capabilities
    */
  case class ServerServices(system: classic.ActorSystem,
                            managingActorFactory: ManagingActorFactory)

  /** Provides the classic actor system in implicit scope. */
  given classicActorSystem(using services: ServerServices): classic.ActorSystem = services.system

  /** Provides the execution context in implicit scope. */
  given executionContext(using services: ServerServices): ExecutionContext = services.system.dispatcher
end ServerHandler

/**
  * A trait defining a common protocol for starting up and gracefully shutting
  * down a custom HTTP server implementation.
  *
  * The idea is that an object implementing this trait can be passed to a
  * server runner which invokes the methods defined here to lead the server
  * implementation through its different life-cycle phases. The handler can
  * define its own data type for a context object that is passed to all
  * methods. That way, it is possible to exchange data between te different
  * method calls.
  *
  * The callback methods deal with the initialization of the context, setting
  * up the routes, shutdown handling, and other relevant life-cycle events.
  */
trait ServerHandler
