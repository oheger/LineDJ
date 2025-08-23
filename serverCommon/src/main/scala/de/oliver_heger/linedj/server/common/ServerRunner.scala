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

import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import org.apache.logging.log4j.LogManager
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.{Done, actor as classic}

import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object ServerRunner:
  /** The hard timeout when terminating the actor system. */
  private val TerminationTimeout = 5.seconds

  /** The logger. */
  private val log = LogManager.getLogger(classOf[ServerRunner])

  /**
    * A trait that can be used to interact with a running server and to stop
    * it. An implementation is returned by the [[ServerRunner.launch]]
    * function.
    */
  trait ServerHandle:
    /**
      * Returns a [[Future]] that completes when the server has shut down,
      * i.e. after the [[ServerController.afterShutdown]] callback has been
      * executed. This can be used for instance to wait until all shutdown
      * actions have been performed.
      *
      * @return a [[Future]] to report the server's shutdown
      */
    def shutdownFuture: Future[Done]

    /**
      * Allows triggering the server's shutdown by an external party. After
      * calling this function, the server stops listening for incoming 
      * requests, frees resources, and invokes the shutdown callback. Calling
      * this function again, has no effect.
      */
    def shutdown(): Unit
  end ServerHandle

  /**
    * An internally used data class to record information while the server is
    * starting up.
    *
    * @param binding the [[ServerBinding]] of the server
    * @param context the server's context
    * @tparam CONTEXT the type of the server's context
    */
  private[common] case class ServerStartupData[CONTEXT](binding: ServerBinding,
                                                        context: CONTEXT)

  /**
    * Enables the server to shut down gracefully when the given shutdown future
    * completes. This function installs hooks that stop all managed actors and
    * terminate the actor system when both the startup future and the shutdown
    * future are completed. It returns a [[Future]] with the server's context
    * that completes when the actor system has been terminated. This [[Future]]
    * is in failed state when the server could not be started.
    *
    * @param startupFuture  the [[Future]] controlling server startup
    * @param shutdownFuture the [[Future]] to trigger the shutdown
    * @param services       the object with services
    * @tparam CONTEXT the type of the server's context
    * @return a [[Future]] that completes when shutdown is done with the 
    *         server's context
    */
  private[common] def enableGracefulShutdown[CONTEXT](startupFuture: Future[ServerStartupData[CONTEXT]],
                                                      shutdownFuture: Future[Done])
                                                     (using services: ServerController.ServerServices): Future[CONTEXT] =
    val shutdownStartupFuture = startupFuture.map: startup =>
      val shutdownBinding = startup.binding.addToCoordinatedShutdown(TerminationTimeout)
      startup.copy(binding = shutdownBinding)
    val futCanShutdown = for
      startup <- shutdownStartupFuture
      _ <- shutdownFuture
    yield startup.context

    val promiseTerminated = Promise[CONTEXT]()
    futCanShutdown.onComplete: triedContext =>
      log.info("Stopping actors and terminating actor system.")
      services.managingActorFactory.stopActors()
      services.system.terminate() onComplete : _ =>
        triedContext match
          case Failure(exception) =>
            log.error("Error when setting up server.", exception)
            promiseTerminated.failure(exception)
          case Success(value) =>
            promiseTerminated.success(value)

    promiseTerminated.future

  /**
    * Starts the Pekko HTTP server for a managed server based on the given
    * parameters. Returns a [[Future]] with information about the startup.
    *
    * @param bindingParameters the parameters how to bind the server
    * @param context           the context of the server
    * @param route             the route specification for the server
    * @param services          the object with services
    * @tparam CONTEXT the type of the server's context
    * @return a [[Future]] with startup information
    */
  private def startHttpServer[CONTEXT](bindingParameters: ServerController.BindingParameters,
                                       context: CONTEXT,
                                       route: Route)
                                      (using services: ServerController.ServerServices):
  Future[ServerStartupData[CONTEXT]] =
    log.info(
      "Starting HTTP server and binding it to '{}:{}'.",
      bindingParameters.bindInterface,
      bindingParameters.bindPort
    )
    Http().newServerAt(bindingParameters.bindInterface, bindingParameters.bindPort)
      .bind(route)
      .map(binding => ServerStartupData(binding, context))
end ServerRunner

/**
  * A class to manage the whole life-cycle of an HTTP server.
  *
  * An instance can be used to start and manage a server defined by a 
  * [[ServerController]] object whose life-cycle is connected to an actor
  * system. The server is bound according to the parameters provided by its
  * handler. Once started, the server can do whatever it wants. It can trigger
  * its own termination. Alternatively, the shutdown of the server can be 
  * initiated externally using the [[ServerRunner.ServerHandle]] returned by
  * this runner.
  *
  * This class offers support for starting a [[ServerLocator]] for the server.
  * If the [[ServerController]] returns corresponding parameters, such a
  * locator is created and configured, so that the URL of the server can be
  * queried via multicast UDP requests.
  *
  * @param locatorFactory the factory for creating a [[ServerLocator]]
  * @param system         the [[classic.ActorSystem]] to operate the servers
  */
class ServerRunner(locatorFactory: ServerLocator.LocatorFactory = ServerLocator.newLocator)
                  (using system: classic.ActorSystem):

  import ServerRunner.*

  /**
    * Starts a server that is managed by the given controller. This is a 
    * non-blocking operation. It returns a [[ServerHandle]] for further
    * interaction with the server immediately, while the server starts serving
    * requests in background.
    *
    * @param controller the controller for the server
    * @return a [[ServerHandle]] for this server
    */
  def launch(controller: ServerController): ServerHandle =
    log.info("Launching server controlled by {}.", controller.getClass.getSimpleName)

    val factory = ManagingActorFactory.newDefaultManagingActorFactory
    val shutdownPromise = Promise[Done]()
    given ServerController.ServerServices(system, factory)

    val startFuture = (for
      context <- controller.createContext
      serverParams <- controller.serverParameters(context)
      route = controller.route(context, shutdownPromise)
      startup <- startHttpServer(serverParams.bindingParameters, context, route)
      _ <- startLocator(serverParams.optLocatorParams, factory)
    yield startup) andThen :
      case Success(startupData) =>
        log.info("HTTP server is listening on port {}.", startupData.binding.localAddress.getPort)
      case Failure(exception) =>
        log.error("Failed to start HTTP server.", exception)

    val donePromise = Promise[Done]()
    val gracefulShutdownFuture = enableGracefulShutdown(startFuture, shutdownPromise.future) map : context =>
      log.info("HTTP server has shut down.")
      controller.afterShutdown(context)
      Done

    new ServerHandle:
      override val shutdownFuture: Future[Done] = gracefulShutdownFuture

      override def shutdown(): Unit =
        if shutdownPromise.trySuccess(Done) then
          log.info("HTTP server was shut down using its handle.")

  /**
    * Starts a server locator if corresponding parameters are available.
    *
    * @param optParams    the optional parameters for the locator
    * @param actorFactory the actor factory
    * @return a [[Future]] for the started locator
    */
  private def startLocator(optParams: Option[ServerLocator.LocatorParams],
                           actorFactory: ManagingActorFactory): Future[Done] =
    optParams.foreach: params =>
      log.info("Starting locator for server.")
      locatorFactory("serverLocator", params)(using actorFactory)

    Future.successful(Done)
