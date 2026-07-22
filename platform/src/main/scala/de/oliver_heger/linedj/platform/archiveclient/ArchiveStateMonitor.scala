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
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing an actor implementation that periodically calls a
  * function to check and update a specific state of the connected media
  * archive.
  *
  * The actor allows the registration of an arbitrary number of change
  * listeners. If at least one change listener is registered, it creates a
  * [[BackoffActor]] instance and configures a task function that delegates to
  * the check function. Depending on the outcome, the locally recorded archive
  * state gets updated, and change listeners are notified.
  *
  * The idea behind the backoff in requests to the server is that if there is
  * an update, there are typically many changes in a short period of time. For
  * instance, when new data from the local filesystem or a remote archive is
  * imported. Afterward, the archive can then be stable for a long time.
  */
object ArchiveStateMonitor:
  /**
    * A trait defining a listener for changes in the state of a media
    * archive.
    *
    * @tparam STATE the type of the monitored state
    */
  trait ArchiveChangeListener[STATE]:
    /**
      * Notifies this object that changes in the state of the archive have
      * been detected. A concrete implementation can now trigger corresponding
      * actions. The updated state is passed in.
      *
      * @param state the most recent state of the archive
      */
    def archiveStateChanged(state: STATE): Unit

  /**
    * An enumeration defining the (externally available) commands supported by
    * the archive monitor actor. This boils down to the management of listeners
    * that receive notifications when the state of the monitored archive has
    * changed.
    *
    * @tparam STATE the type of the monitored state
    */
  enum ArchiveListenerCommand[STATE]:
    /**
      * Command to add a change listener to be managed by this actor. This
      * listener is then invoked when a change in the content of the archive is
      * detected.
      *
      * @param listener the listener to add
      */
    case AddChangeListener(listener: ArchiveChangeListener[STATE])

    /**
      * Command to remove a change listener from the set of listeners managed
      * by this actor.
      *
      * @param listener the listener to remove
      */
    case RemoveChangeListener(listener: ArchiveChangeListener[STATE])

    /**
      * Command to notify this actor that there might be changes in the
      * monitored archive. This can be used by clients to give the actor a hint
      * that it might be useful to check the archive again with a shorter
      * interval.
      */
    case ChangesExpected()

    /**
      * Command to stop this actor instance.
      */
    case Stop()
  end ArchiveListenerCommand

  /**
    * An enumeration defining internal commands for the archive monitor actor
    * that are needed to check the status of the archive.
    *
    * @tparam DATA  the type of the managed data
    * @tparam STATE the type of the managed state
    */
  private enum ArchiveCheckCommand[DATA, STATE]:
    /**
      * Command that triggers a check whether there are changes in the content
      * of the archive. The result of the check is the result to be returned by
      * the periodically invoked task function.
      *
      * @param replyTo the actor to receive the result of the check
      */
    case TriggerCheck(replyTo: ActorRef[BackoffActor.TaskResult])

    /**
      * Command to process the result from the evaluate function when passed 
      * the response from the latest check request. The response determines 
      * whether registered listeners need to be notified and which result to return
      * from the task function.
      *
      * @param evalResult the result from the evaluate function
      * @param replyTo    the actor receive the result of the check
      */
    case HandleEvalResult(evalResult: Option[(DATA, STATE)], replyTo: ActorRef[BackoffActor.TaskResult])
  end ArchiveCheckCommand

  /**
    * Type alias for the combined type of commands that are processed by the
    * archive monitor actor.
    */
  private type ArchiveMonitorCommand[DATA, STATE] = ArchiveListenerCommand[STATE] | ArchiveCheckCommand[DATA, STATE]

  /**
    * Type alias for a function this actor uses to generate the HTTP requests
    * to send to the archive. The function expects the current data managed by
    * this actor (which may not yet be available) and can derive the requests
    * from there.
    *
    * @tparam DATA the type of the data to be managed
    */
  type RequestFunc[DATA] = Option[DATA] => List[HttpRequest]

  /**
    * Type alias for a function this actor calls asynchronously to extract the
    * updated data from the responses received from the archive. The responses
    * are passed in the same order as the requests generated by the request
    * function. In addition, it  gets passed the current data, too, if
    * available. If this function returns a defined [[Option]], the actor
    * stores the updated data and calls the registered listeners with the given
    * state.
    *
    * @tparam DATA  the updated data to store
    * @tparam STATE the state for notifying change listeners
    */
  type EvaluateFunc[DATA, STATE] = (List[HttpResponse], Option[DATA]) => Future[Option[(DATA, STATE)]]

  /**
    * A data class that collects all the parameters to configure an archive 
    * monitor actor.
    *
    * @param archiveSender  the actor to send requests to the archive
    * @param backoffConfig  the configuration for periodic checks
    * @param requestFunc    the function to produce the request to the archive
    * @param evaluateFunc   the function to evaluate the response
    * @param requestTimeout a timeout for sending requests
    * @param backoffFactory the factory to create a new backoff actor
    * @tparam DATA  the type of the data to be managed
    * @tparam STATE the type of the state for the listeners
    */
  final case class Params[DATA, STATE](archiveSender: ActorRef[HttpRequestSender.HttpCommand],
                                       backoffConfig: BackoffConfig,
                                       requestFunc: RequestFunc[DATA],
                                       evaluateFunc: EvaluateFunc[DATA, STATE],
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
      * @tparam DATA  the type of the data to be managed
      * @tparam STATE the type of the state for the listeners
      */
    def apply[DATA, STATE](params: Params[DATA, STATE], actorName: String)
                          (using actorFactory: ActorFactory): ActorRef[ArchiveListenerCommand[STATE]]

  /**
    * A default [[Factory]] instance to create new actor instances.
    */
  final val newInstance: Factory = new Factory:
    override def apply[DATA, STATE](params: Params[DATA, STATE], actorName: String)
                                   (using actorFactory: ActorFactory): ActorRef[ArchiveListenerCommand[STATE]] =
      val behavior = setUpMonitorActor(params, actorName)
      actorFactory.createTypedActor(behavior, actorName)


  /**
    * Returns the behavior of a new actor instance based on the provided 
    * parameters.
    *
    * @param params       the parameters for the actor
    * @param actorName    the actor name
    * @param actorFactory the factory to create new actors
    * @return the behavior for the new actor instance
    * @tparam DATA  the type of the data to be managed
    * @tparam STATE the type of the state for the listeners
    */
  private def setUpMonitorActor[DATA, STATE](params: Params[DATA, STATE], actorName: String)
                                            (using actorFactory: ActorFactory):
  Behavior[ArchiveListenerCommand[STATE]] =
    Behaviors.setup[ArchiveMonitorCommand[DATA, STATE]]: context =>
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
        * @param optBackoffHandle optional handle to the backoff actor
        * @param optData          the data managed by this actor
        * @return the updated behavior
        */
      def handleArchiveMonitorCommand(listeners: List[ArchiveChangeListener[STATE]],
                                      optBackoffHandle: Option[BackoffActor.BackoffHandle],
                                      optData: Option[(DATA, STATE)]):
      Behavior[ArchiveMonitorCommand[DATA, STATE]] =
        Behaviors.receiveMessage:
          case ArchiveListenerCommand.AddChangeListener(listener) =>
            optData.foreach(data => listener.archiveStateChanged(data._2))
            val backoffHandle = optBackoffHandle.getOrElse(startBackoffActor())
            handleArchiveMonitorCommand(listener :: listeners, Some(backoffHandle), optData)

          case ArchiveListenerCommand.RemoveChangeListener(listener) =>
            val nextListeners = listeners.filterNot(_ == listener)
            val nextHandle = if nextListeners.isEmpty then
              context.log.info("[{}]: Stopping Backoff actor.", actorName)
              optBackoffHandle.foreach(_.close())
              None
            else
              optBackoffHandle
            handleArchiveMonitorCommand(nextListeners, nextHandle, optData)

          case ArchiveListenerCommand.ChangesExpected() =>
            optBackoffHandle.foreach(_.resetDelay())
            Behaviors.same

          case ArchiveListenerCommand.Stop() =>
            context.log.info("[{}] Stopping ArchiveMonitor actor.", actorName)
            Behaviors.stopped

          case ArchiveCheckCommand.TriggerCheck(replyTo) =>
            context.log.debug("Checking for changes in the archive's content.")
            val requests = params.requestFunc(optData.map(_._1))
            val results = requests.map: request =>
              HttpRequestSender.sendRequestSuccess(
                params.archiveSender,
                request
              )
            val futEval = for
              responses <- Future.sequence(results)
              eval <- params.evaluateFunc(responses.map(_.response), optData.map(_._1))
            yield eval
            futEval.foreach: result => // In case of a failed future, this will cause a timeout.
              context.self ! ArchiveCheckCommand.HandleEvalResult(result, replyTo)
            Behaviors.same

          case ArchiveCheckCommand.HandleEvalResult(evalResult, replyTo) =>
            evalResult match
              case Some((_, state)) =>
                listeners.foreach(_.archiveStateChanged(state))
                replyTo ! BackoffActor.TaskResult.Reset
                handleArchiveMonitorCommand(listeners, optBackoffHandle, evalResult)
              case None =>
                replyTo ! BackoffActor.TaskResult.Backoff
                Behaviors.same

      /**
        * Returns the task function that is called periodically to check for
        * changes in the content of the archive. This function only asks the
        * actor instance to perform the check. This automatically handles
        * failed requests and timeouts.
        *
        * @param context the context of the monitor actor
        * @return the task function for the backoff actor
        */
      def checkArchive(context: ActorContext[ArchiveMonitorCommand[DATA, STATE]]): BackoffActor.TaskFunc =
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

      handleArchiveMonitorCommand(Nil, None, None)
    .narrow
