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

package de.oliver_heger.linedj.platform.archiveclient

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import org.apache.pekko.http.scaladsl.model.headers.{ETag, `If-None-Match`}
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext

/**
  * A module providing an actor implementation that checks periodically for
  * changes in the content of the connected media archive.
  *
  * The actor allows the registration of an arbitrary number of change
  * listeners. If at least one change listener is registered, it creates a
  * [[BackoffActor]] instance and configures a task function that sends a HEAD
  * request to the archive server checking for updates in the list of media.
  * If such a change is detected, it notifies the registered change listeners.
  *
  * The idea behind the backoff in requests to the server is that if there is
  * an update, there are typically many changes in a short period of time. For
  * instance, when new data from the local filesystem or a remote archive is
  * imported. Afterward, the archive can then be stable for a long time.
  */
object ArchiveMonitor:
  /**
    * A trait defining a listener for changes in the content of a media
    * archive.
    */
  trait ArchiveChangeListener:
    /**
      * Notifies this object that changes in the content of the archive have
      * been detected. A concrete implementation can now trigger corresponding
      * actions; for instance, load the newest media data again.
      */
    def archiveContentChanged(): Unit

  /**
    * An enumeration defining the (externally available) commands supported by
    * the archive monitor actor. This boils down to the management of listeners
    * that receive notifications when the content of the monitored archive has
    * changed.
    */
  enum ArchiveListenerCommand:
    /**
      * Command to add a change listener to be managed by this actor. This
      * listener is then invoked when a change in the content of the archive is
      * detected.
      *
      * @param listener the listener to add
      */
    case AddChangeListener(listener: ArchiveChangeListener)

    /**
      * Command to remove a change listener from the set of listeners managed
      * by this actor.
      *
      * @param listener the listener to remove
      */
    case RemoveChangeListener(listener: ArchiveChangeListener)

    /**
      * Command to stop this actor instance.
      */
    case Stop
  end ArchiveListenerCommand

  /**
    * An enumeration defining internal commands for the archive monitor actor
    * that are needed to check the status of the archive.
    */
  private enum ArchiveCheckCommand:
    /**
      * Command that triggers a check whether there are changes in the content
      * of the archive. The result of the check is the result to be returned by
      * the periodically invoked task function.
      *
      * @param replyTo the actor to receive the result of the check
      */
    case TriggerCheck(replyTo: ActorRef[BackoffActor.TaskResult])

    /**
      * Command to process the response from the asynchronous request for the
      * content status of the archive. The response determines whether
      * registered listeners need to be notified and which result to return
      * from the task function.
      *
      * @param response the response from the archive server
      * @param replyTo  the actor receive the result of the check
      */
    case HandleCheckResponse(response: HttpResponse, replyTo: ActorRef[BackoffActor.TaskResult])
  end ArchiveCheckCommand

  /**
    * Type alias for the combined type of commands that are processed by the
    * archive monitor actor.
    */
  private type ArchiveMonitorCommand = ArchiveListenerCommand | ArchiveCheckCommand

  /**
    * A data class that collects all the parameters to configure an archive 
    * monitor actor.
    *
    * @param archiveSender  the actor to send requests to the archive
    * @param backoffConfig  the configuration for periodic checks
    * @param requestTimeout a timeout for sending requests
    * @param backoffFactory the factory to create a new backoff actor
    */
  final case class ArchiveMonitorParams(archiveSender: ActorRef[HttpRequestSender.HttpCommand],
                                        backoffConfig: BackoffConfig,
                                        requestTimeout: Timeout,
                                        backoffFactory: BackoffActor.Factory = BackoffActor.newInstance)

  /**
    * A factory trait for creating new instances of the archive monitor actor.
    */
  trait Factory:
    /**
      * Creates a new instance of the archive monitor actor based on the 
      * provided parameters.
      *
      * @param params       the parameters for the new instance
      * @param actorName    the name of the actor
      * @param actorFactory the [[ActorFactory]]
      * @return a reference to the new actor instance
      */
    def apply(params: ArchiveMonitorParams, actorName: String)
             (using actorFactory: ActorFactory): ActorRef[ArchiveListenerCommand]

  /**
    * A default [[Factory]] instance to create new actor instances.
    */
  final val newInstance: Factory = new Factory:
    override def apply(params: ArchiveMonitorParams, actorName: String)
                      (using actorFactory: ActorFactory): ActorRef[ArchiveListenerCommand] =
      val behavior = setUpMonitorActor(params, actorName)
      actorFactory.createTypedActor(behavior, actorName)

  /**
    * Constant for the base request to be sent to the archive server to check
    * whether the content is up-to-date. Depending on the current state, some
    * additional headers need to be added.
    */
  private val BaseCheckRequest = HttpRequest(
    uri = "/api/archive/media",
    method = HttpMethods.HEAD
  )

  private def setUpMonitorActor(params: ArchiveMonitorParams, actorName: String)
                               (using actorFactory: ActorFactory): Behavior[ArchiveListenerCommand] =
    Behaviors.setup[ArchiveMonitorCommand]: context =>
      given ActorSystem[Nothing] = actorFactory.actorSystem.toTyped

      given ExecutionContext = context.executionContext

      given Timeout = params.requestTimeout

      lazy val backoffParams = BackoffActor.BackoffParameters(
        minBackoff = params.backoffConfig.minBackoff,
        maxBackoff = params.backoffConfig.maxBackoff,
        incrementFactor = params.backoffConfig.factor,
        failureResult = BackoffActor.TaskResult.Backoff,
        taskFunc = checkArchive(context)
      )

      /**
        * The command handler function for the archive monitor actor.
        *
        * @param listeners        the currently registered change listeners
        * @param checkRequest     the request to check for changes
        * @param optBackoffHandle optional handle to the backoff actor
        * @return the updated behavior
        */
      def handleArchiveMonitorCommand(listeners: List[ArchiveChangeListener],
                                      checkRequest: HttpRequest,
                                      optBackoffHandle: Option[BackoffActor.BackoffHandle]):
      Behavior[ArchiveMonitorCommand] =
        Behaviors.receiveMessage:
          case ArchiveListenerCommand.AddChangeListener(listener) =>
            val backoffHandle = optBackoffHandle.getOrElse(startBackoffActor())
            handleArchiveMonitorCommand(listener :: listeners, checkRequest, Some(backoffHandle))

          case ArchiveListenerCommand.RemoveChangeListener(listener) =>
            val nextListeners = listeners.filterNot(_ == listener)
            val nextHandle = if nextListeners.isEmpty then
              context.log.info("[{}]: Stopping Backoff actor.", actorName)
              optBackoffHandle.foreach(_.close())
              None
            else
              optBackoffHandle
            handleArchiveMonitorCommand(nextListeners, checkRequest, nextHandle)

          case ArchiveListenerCommand.Stop =>
            context.log.info("[{}] Stopping ArchiveMonitor actor.", actorName)
            Behaviors.stopped

          case ArchiveCheckCommand.TriggerCheck(replyTo) =>
            context.log.debug("Checking for changes in the archive's content.")
            HttpRequestSender.sendRequestSuccess(
              params.archiveSender,
              checkRequest,
              HttpRequestSender.DiscardEntityMode.Always
            ).foreach: result => // In case of a failed future, this will cause a timeout.
              context.self ! ArchiveCheckCommand.HandleCheckResponse(result.response, replyTo)
            Behaviors.same

          case ArchiveCheckCommand.HandleCheckResponse(response, replyTo) =>
            val taskResult = if response.status == StatusCodes.OK then
              listeners.foreach(_.archiveContentChanged())
              BackoffActor.TaskResult.Reset
            else
              BackoffActor.TaskResult.Backoff
            replyTo ! taskResult

            val nextRequest = response.header[ETag].fold(BaseCheckRequest): tag =>
              BaseCheckRequest.withHeaders(Seq(`If-None-Match`(tag.etag)))
            handleArchiveMonitorCommand(listeners, nextRequest, optBackoffHandle)

      /**
        * Returns the task function that is called periodically to check for
        * changes in the content of the archive. This function only asks the
        * actor instance to perform the check. This automatically handles
        * failed requests and timeouts.
        *
        * @param context the context of the monitor actor
        * @return the task function for the backoff actor
        */
      def checkArchive(context: ActorContext[ArchiveMonitorCommand]): BackoffActor.TaskFunc =
        given Scheduler = context.system.scheduler

        () =>
          context.self.ask[BackoffActor.TaskResult](ref => ArchiveCheckCommand.TriggerCheck(ref))

      /**
        * Calls the factory for the backoff actor to create a new instance and
        * obtain a handle for it.
        *
        * @return the handle for the backoff actor
        */
      def startBackoffActor(): BackoffActor.BackoffHandle =
        context.log.info("[{}]: Creating Backoff actor instance.", actorName)
        params.backoffFactory(backoffParams, actorName + ".backoff")

      handleArchiveMonitorCommand(Nil, BaseCheckRequest, None)
    .narrow
