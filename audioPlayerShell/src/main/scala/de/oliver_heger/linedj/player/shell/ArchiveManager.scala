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

package de.oliver_heger.linedj.player.shell

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory.given
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.util.Timeout

import java.awt.Desktop
import java.net.URI
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ArchiveManager:
  /**
    * An enumeration defining the commands supported by the archive manager
    * actor.
    */
  private enum ArchiveManagerCommand:
    /**
      * The discovery command. This command starts a discovery operation based
      * on the provided parameters.
      *
      * @param params the parameters for the discovery operation
      */
    case Discovery(params: ServerDiscovery.DiscoveryParams)

    /**
      * The command to send a request to the managed archive.
      *
      * @param request the HTTP request to send to the archive
      * @param replyTo the actor to send the response to
      */
    case SendRequest(request: HttpRequest,
                     replyTo: ActorRef[ArchiveResponse])

    /**
      * The command to handle the result from a discovery operation. This
      * typically makes an archive server available whose base URL is now
      * known.
      *
      * @param result the result of the discovery operation
      */
    case DiscoveryResult(result: String)

    /**
      * The command to stop the actor. This is called on shutdown of the shell
      * application. It releases all resources in use.
      */
    case Stop
  end ArchiveManagerCommand

  /**
    * A data class to represent the response on a command to send a request to
    * the managed archive.
    *
    * @param futResult the [[Future]] with the response from the archive
    */
  private case class ArchiveResponse(futResult: Future[HttpRequestSender.SuccessResult])

  /**
    * Creates a new [[ArchiveManager]] instance.
    *
    * @param system      the actor system
    * @param httpTimeout the timeout for HTTP requests
    * @return the new manager object
    */
  def apply(using system: classic.ActorSystem, httpTimeout: Timeout): ArchiveManager =
    val archiveManagerActor = system.spawn(handleArchiveCommand(None, None), "archiveManager")

    given ActorSystem[_] = system.toTyped

    new ArchiveManager:
      override def discover(params: ServerDiscovery.DiscoveryParams): Unit =
        archiveManagerActor ! ArchiveManagerCommand.Discovery(params)

      override def sendArchiveRequest(request: HttpRequest): Future[HttpRequestSender.SuccessResult] =
        archiveManagerActor.ask[ArchiveResponse](ref => ArchiveManagerCommand.SendRequest(request, ref))
          .flatMap(response => response.futResult)

      override def close(): Unit =
        archiveManagerActor ! ArchiveManagerCommand.Stop

  /**
    * The command handler function for the archive manager actor.
    *
    * @param optSender    an [[Option]] with the current HTTP sender actor
    * @param optDiscovery an [[Option]] with the current discovery handle
    * @param timeout      the timeout for HTTP requests
    * @return the behavior of the actor
    */
  private def handleArchiveCommand(optSender: Option[ActorRef[HttpRequestSender.HttpCommand]],
                                   optDiscovery: Option[ServerDiscovery.DiscoveryHandle])
                                  (using timeout: Timeout):
  Behavior[ArchiveManagerCommand] =
    Behaviors.receive:
      case (ctx, ArchiveManagerCommand.Discovery(params)) =>
        optDiscovery.foreach(_.close())
        ctx.log.info("Starting discovery operation for '{}'.", params.requestCode)

        given classic.ActorSystem = ctx.system.toClassic

        val nextHandle = ServerDiscovery.discover(params)
        nextHandle.futResult.filter(_.nonEmpty) // An empty result is returned by a canceled discovery.
          .foreach(result => ctx.self ! ArchiveManagerCommand.DiscoveryResult(result))
        handleArchiveCommand(optSender, Some(nextHandle))

      case (ctx, ArchiveManagerCommand.DiscoveryResult(result)) if result.endsWith(".html") =>
        // In this case, a Web application was discovered; so, open it in the Browser if possible.
        ctx.log.info("Received HTML URL as discovery result: '{}'.", result)
        if Desktop.isDesktopSupported then
          given ExecutionContext = ctx.executionContext

          ctx.log.info("Opening URL in browser.")
          Future:
            Desktop.getDesktop.browse(URI.create(result))
          .onComplete:
            case Success(_) => Output.output(Output.SyncOutput(List("Browser opened.")))
            case Failure(exception) =>
              val output = Output.SyncOutput(
                lines = List(
                  "Could not open browser.",
                  exception.getMessage
                ),
                style = Output.StyleError
              )
        else
          ctx.log.warn("Ignoring discovery result, since no Desktop support is available.")
        Behaviors.same

      case (ctx, ArchiveManagerCommand.DiscoveryResult(result)) =>
        ctx.log.info("Received discovery result: '{}'", result)
        optSender.foreach(_ ! HttpRequestSender.Stop)
        val nextSender = ctx.spawn(HttpRequestSender(result), "archiveActor")
        handleArchiveCommand(Some(nextSender), optDiscovery)

      case (ctx, ArchiveManagerCommand.SendRequest(request, replyTo)) =>
        val futResponse = optSender match
          case Some(sender) =>
            given ActorSystem[_] = ctx.system

            HttpRequestSender.sendRequestSuccess(sender, request)
          case None =>
            Future.failed(new IllegalStateException("No archive is currently available."))
        replyTo ! ArchiveResponse(futResponse)
        Behaviors.same

      case (_, ArchiveManagerCommand.Stop) =>
        optDiscovery.foreach(_.close())
        optSender.foreach(_ ! HttpRequestSender.Stop)
        Behaviors.stopped
end ArchiveManager

/**
  * A trait managing access to a media access.
  *
  * An implementation can interact with an archive server which is located via 
  * the server discovery mechanism. An instance can be instructed to start such
  * a discovery operation. Once this is successful, it constructs a request
  * sender actor to send requests to this archive.
  */
trait ArchiveManager extends AutoCloseable:
  /**
    * Triggers a discovery operation for an archive server using the provided
    * parameters. When this is successful, the result is interpreted as the
    * base URL of the archive server. It is then possible to send requests to
    * this archive.
    *
    * If multiple discovery operations are started, earlier operations are
    * canceled. Only one archive server is managed.
    *
    * @param params the parameters for the discovery operation
    */
  def discover(params: ServerDiscovery.DiscoveryParams): Unit

  /**
    * Sends the given request to the managed archive server. Is no such server
    * is available (because no discovery operation has been started yet, or an
    * operation is still in progress), result is a failed [[Future]].
    *
    * @param request the request to send to the managed archive
    * @return a [[Future]] with the response from the archive
    */
  def sendArchiveRequest(request: HttpRequest): Future[HttpRequestSender.SuccessResult]
