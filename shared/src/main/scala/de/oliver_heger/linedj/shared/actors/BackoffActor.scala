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

package de.oliver_heger.linedj.shared.actors

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
  * A module providing an actor implementation that can be used to execute
  * tasks with an exponential backoff in configurable limits.
  *
  * Via this module, actors can be created that are configured with a task
  * function. Such a function returns a [[Future]] with a result code. Based on
  * the result code, the actor decides how to proceed. It can
  *  - invoke the function again with an increased delay according to the
  *    configured backoff parameters
  *  - reset its internal state and invoke the function again after the minimum
  *    configured delay
  *  - stop the periodic invocation of the function and terminate.
  *
  * A typical use case is to perform a task with a retry logic. Clients
  * interact with the actor via a handle that can be used to cancel the
  * activity at any time. Closing the handle also terminates the actor. It is
  * safe to call _close()_ on the handle multiple times; the implementation
  * ensures that the actor only receives a single message to stop itself (so,
  * no dead letter logs should be printed).
  */
object BackoffActor:
  /**
    * An enumeration defining the supported results of a task execution. The
    * values determine the different behaviors of an actor instance, when and
    * if it calls the task function again.
    */
  enum TaskResult:
    /**
      * On receiving this result, the actor applies its backoff configuration
      * to its current state and determines the next delay for invoking the
      * task function again.
      */
    case Backoff

    /**
      * On receiving this result, the actor resets its internal backoff state.
      * It then invokes the task function again with the minimum configured
      * delay.
      */
    case Reset

    /**
      * This result indicates that no more invocations of the task function are
      * desired. The actor instance can be stopped.
      */
    case Cancel
  end TaskResult

  /**
    * Type alias for the task function that is invoked periodically by an actor
    * instance. The function can perform arbitrary asynchronous activity, and
    * then has to indicate how to continue with the backoff processing.
    */
  type TaskFunc = () => Future[TaskResult]

  /**
    * A data class to hold the parameters to trigger tasks periodically with
    * an exponential backoff. An instance of this class must be provided when
    * creating a new actor instance. It controls which action to invoke with
    * which delays.
    *
    * @param taskFunc        the function representing the task to execute
    * @param minBackoff      the minimum backoff; this is the smallest delay
    *                        between two invocations of the task function; this
    *                        is also the first delay after resetting the actor
    *
    * @param maxBackoff      the maximum backoff; the delay between two 
    *                        invocations of the task function never gets bigger
    *                        than this value
    *
    * @param incrementFactor the factor to increase the delay
    * @param failureResult   the result to assume if the task function returns
    *                        a failed [[Future]]
    */
  final case class BackoffParameters(taskFunc: TaskFunc,
                                     minBackoff: FiniteDuration,
                                     maxBackoff: FiniteDuration,
                                     incrementFactor: Double = 2.0,
                                     failureResult: TaskResult = TaskResult.Cancel)

  /**
    * A trait to represent a handle to a backoff actor. Such a handle can be
    * used to cancel task execution from the outside.
    */
  trait BackoffHandle extends AutoCloseable:
    /**
      * Resets the delay between invocations of the task function to the
      * configured minimum backoff. The task function is invoked immediately
      * if it is not currently running (and the next scheduled execution is
      * canceled). If it is currently running, the actor waits for its
      * completion. In any case, the next invocation happens after the minimum
      * backoff delay. Note that this function only has an effect if the actor
      * has not stopped. So, using this function is only safe if the task
      * function never returns a _Cancel_ result.
      */
    def resetDelay(): Unit

  /**
    * A trait to create a new backoff actor instance.
    */
  trait Factory:
    /**
      * Returns a handle to a new backoff actor that performs periodic task
      * executions according to the provided parameters.
      *
      * @param params       the parameters describing the executions
      * @param name         the name of the actor
      * @param actorFactory the factory to create the actor instance
      * @return a handle to stop the actor instance
      */
    def apply(params: BackoffParameters, name: String)
             (using actorFactory: ActorFactory): BackoffHandle

  /**
    * A default [[Factory]] object that allows creating new actor instances.
    */
  final val newInstance: Factory = new Factory:
    override def apply(params: BackoffParameters, name: String)(using actorFactory: ActorFactory): BackoffHandle =
      given ExecutionContext = actorFactory.actorSystem.dispatcher

      val stopPromise = Promise[Unit]()
      val behavior = backoffBehavior(params, name, stopPromise)
      val backoffActor = actorFactory.createTypedActor(behavior, name)
      stopPromise.future foreach : _ =>
        backoffActor ! BackoffCommand.Stop

      backoffActor ! BackoffCommand.ExecuteTask
      new BackoffHandle:
        override def resetDelay(): Unit =
          backoffActor ! BackoffCommand.Reset

        override def close(): Unit =
          stopPromise.trySuccess(())

  /**
    * An (internal) enumeration defining the commands supported by the backoff 
    * actor.
    */
  private enum BackoffCommand:
    /**
      * Stops this actor instance. This command is triggered when the handle to
      * the actor gets closed.
      */
    case Stop

    /**
      * Triggers the (delayed) execution of the task function. Then, depending
      * on the result, another invocation with a new delayed might be 
      * scheduled.
      */
    case ExecuteTask

    /**
      * Notifies the actor about the outcome of a task execution. Based on the
      * result, the actor decides what to do next.
      *
      * @param result the result from the task function
      */
    case ProcessTaskResult(result: TaskResult)

    /**
      * Resets the delay to the configured minimum backoff. If the task
      * function is currently not active, it is invoked immediately.
      */
    case Reset

  /**
    * Creates the behavior for a backoff actor based on the given parameters.
    * The passed in [[Promise]] is used to sync the termination of the actor;
    * it prevents multiple stop commands which would cause dead letter logs.
    *
    * @param params      the [[BackoffParameters]]
    * @param name        the name for this actor instance
    * @param stopPromise the [[Promise]] to control termination
    * @return the behavior of the new actor
    */
  private def backoffBehavior(params: BackoffParameters,
                              name: String,
                              stopPromise: Promise[Unit]): Behavior[BackoffCommand] =
    Behaviors.setup: context =>
      def handleBackoffCommand(currentDelay: FiniteDuration,
                               taskInProgress: Boolean,
                               stopped: Boolean,
                               optSchedule: Option[Cancellable]): Behavior[BackoffCommand] =
        Behaviors.receiveMessage:
          case BackoffCommand.ExecuteTask =>
            context.pipeToSelf(params.taskFunc()): result =>
              BackoffCommand.ProcessTaskResult(result.getOrElse(params.failureResult))
            handleBackoffCommand(currentDelay, taskInProgress = true, stopped, None)

          case BackoffCommand.ProcessTaskResult(_) if stopped =>
            stop()

          case BackoffCommand.ProcessTaskResult(result) =>
            val optNextDelay = result match
              case TaskResult.Backoff =>
                Some(computeNextDelay(params, currentDelay))
              case TaskResult.Reset =>
                Some(params.minBackoff)
              case TaskResult.Cancel =>
                context.log.info("[{}]: Got Cancel result. Stopping periodic execution.", name)
                stopPromise.trySuccess(())
                None
            optNextDelay.map: delay =>
              context.log.debug("[{}]: Next execution after {}.", name, delay)
              val cancellable = context.scheduleOnce(delay, context.self, BackoffCommand.ExecuteTask)
              handleBackoffCommand(delay, taskInProgress = false, stopped, Some(cancellable))
            .getOrElse(handleBackoffCommand(currentDelay, taskInProgress = false, stopped, None))

          case BackoffCommand.Reset =>
            optSchedule.foreach(_.cancel())
            if !taskInProgress then
              context.self ! BackoffCommand.ExecuteTask
            handleBackoffCommand(0.seconds, taskInProgress, stopped, None)

          case BackoffCommand.Stop if taskInProgress =>
            context.log.info("[{}]: Received Stop command while task is active. Waiting for completion.", name)
            handleBackoffCommand(currentDelay, taskInProgress, stopped = true, optSchedule)

          case BackoffCommand.Stop =>
            stop()

      def stop(): Behavior[BackoffCommand] =
        context.log.info("[{}]: Stopping actor.", name)
        Behaviors.stopped

      handleBackoffCommand(0.seconds, taskInProgress = false, stopped = false, optSchedule = None)

  /**
    * Computes the next delay based on the current delay and the backoff 
    * parameters.
    *
    * @param params       the parameters defining the backoff
    * @param currentDelay the current delay
    * @return the next delay
    */
  private def computeNextDelay(params: BackoffParameters, currentDelay: FiniteDuration): FiniteDuration =
    val nextDelayMillis = math.round(currentDelay.toMillis * params.incrementFactor)
    math.min(math.max(nextDelayMillis, params.minBackoff.toMillis), params.maxBackoff.toMillis).millis
  