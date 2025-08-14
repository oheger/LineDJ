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

import de.oliver_heger.linedj.server.common.ServerHandler.{BindingParameters, DefaultBindingParameters, ServerServices}
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.{Done, actor as classic}

import scala.concurrent.{ExecutionContext, Future, Promise}

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

  /**
    * A data class defining parameters required for starting the server. An
    * instance of this class needs to be provided by the handler, so that the
    * server can bind to the correct IP address and port.
    *
    * @param bindInterface the network interface to bind to
    * @param bindPort      the bind port
    */
  case class BindingParameters(bindInterface: String,
                               bindPort: Int)

  /**
    * An instance of [[BindingParameters]] defining default properties. This
    * instance is used by default, unless a concrete handler overrides the
    * corresponding callback.
    */
  final val DefaultBindingParameters = BindingParameters(
    bindInterface = "0.0.0.0",
    bindPort = 8080
  )
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
trait ServerHandler:
  /**
    * A data type to be defined by a concrete handler implementation which can
    * hold context data. An instance of this class is created initially by the
    * handler and then passed to all later method calls.
    */
  type Context

  /**
    * Callback function that is invoked at the beginning of the life-cycle of
    * the server. The purpose of this function is to allow the server
    * implementation to create its [[Context]]. If this is done successfully,
    * the server can start, and the resulting context object is passed to all
    * life-cycle callbacks invoked later.
    *
    * @param services basic services to be consumed by this handler
    * @return a [[Future]] with the [[Context]] of this server
    */
  def createContext(using services: ServerServices): Future[Context]

  /**
    * Returns the [[BindingParameters]] to be used when starting up the server.
    * This callback is invoked after the creation of the context to obtain the
    * required parameters for initializing the listener for HTTP requests. This
    * base implementation returns default parameters.
    *
    * @param context  the [[Context]] of this server
    * @param services basic services to be consumed by this handler
    * @return a [[Future]] with the [[BindingParameters]]
    */
  def bindingParameters(context: Context)(using services: ServerServices): Future[BindingParameters] =
    Future.successful(DefaultBindingParameters)

  /**
    * Returns the [[Route]] for processing HTTP requests. This callback is
    * invoked after the creation of the context to initialize serving of HTTP
    * requests. The passed in [[Promise]] is a means to trigger the shutdown of
    * the server programmatically, e.g. from an endpoint of the route; when it
    * is completed, the runner initiates the shutdown of the server.
    *
    * @param context         the [[Context]] of this server
    * @param shutdownPromise a [[Promise]] to trigger the shutdown
    * @param services        basic services to be consumed by this handler
    * @return the [[Route]] for serving HTTP requests
    */
  def route(context: Context, shutdownPromise: Promise[Done])(using services: ServerServices): Route

  /**
    * A callback to notify this handler that the server has been shut down. At
    * that point in time, the actor system has been terminated; thus, services
    * are no longer available. A concrete handler can do some cleanup actions.
    * This base implementation does nothing.
    *
    * @param context the [[Context]] of this server
    */
  def afterShutdown(context: Context): Unit = {}

