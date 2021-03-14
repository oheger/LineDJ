/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.io.stream

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.stream.stage.{AsyncCallback, GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.util.Timeout
import de.oliver_heger.linedj.io.stream.ActorSource.{ActorCompletionResult, ActorDataResult,
ActorErrorResult, ResultFunc}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ActorSource {
  /**
    * A default timeout value for invoking the data actor. If no response is
    * received within this time span, processing stops with a failure.
    */
  val DefaultTimeout = Timeout(1.minute)

  /**
    * A trait to classify a response message received from the wrapped actor.
    * Responses from the wrapped actor are passed to a transformation
    * function that returns one of the sub types of this trait. This allows the
    * source to decide how a specific result is to be handled.
    *
    * @tparam A the element type of the source
    */
  sealed trait ActorResult[A]

  /**
    * Data class defining an actor result with a data item to be passed
    * downstream.
    *
    * @param data the data to be passed downstream
    * @tparam A the element type of the source
    */
  case class ActorDataResult[A](data: A) extends ActorResult[A]

  /**
    * An actor result indicating that no more data is available. The source
    * will then complete its stage.
    *
    * @tparam A the element type of the source
    */
  case class ActorCompletionResult[A]() extends ActorResult[A]

  /**
    * An actor result indicating a processing error. The source will fail with
    * the specified error message.
    *
    * @param exception the exception causing the failure
    * @tparam A the element type of the source
    */
  case class ActorErrorResult[A](exception: Throwable) extends ActorResult[A]

  /**
    * Type definition for a function that maps a response from the actor to an
    * ''ActorResult'' object. Such a function has to be provided when
    * constructing an ''ActorSource'', so that the responses received from the
    * actor can be interpreted correctly.
    *
    * @tparam A the element type of the source
    */
  type ResultFunc[A] = Any => ActorResult[A]

  /**
    * Creates a ''Source'' that wraps the specified actor. Use this method to
    * construct streams that are controlled by a data-providing actor.
    *
    * @param actor      the actor to be wrapped by this source
    * @param requestMsg the message to request new data from the actor
    * @param timeout    a timeout when waiting for the actor's response
    * @param f          a function to map a response to an ''ActorResult''
    * @param ec         the execution context to deal with futures
    * @tparam A the type of elements produced by this source
    * @return the ''Source'' wrapping the provided actor
    */
  def apply[A](actor: ActorRef, requestMsg: Any, timeout: Timeout = DefaultTimeout)
              (f: ResultFunc[A])
              (implicit ec: ExecutionContext): Source[A, NotUsed] = {
    val actorSource = new ActorSource[A](actor, requestMsg, timeout)(f)(ec)
    Source fromGraph actorSource
  }
}

/**
  * A stream source implementation that receives data from a specified actor.
  *
  * An instance can be created passing in an actor reference and some
  * information how to communicate with this actor:
  *  - A message to be sent to the actor in order to request a chunk of data.
  * The source sends this message to the actor whenever new data can be
  * consumed.
  *  - A function to map a response from the actor to an
  * [[de.oliver_heger.linedj.io.stream.ActorSource.ActorResult]] object. Based
  * on this object, the source can decide how to proceed: pass new data
  * downstream, finish processing, or fail with an exception.
  *
  * @param actor        the actor to be wrapped by this source
  * @param requestMsg   the message to request new data from the actor
  * @param actorTimeout a timeout when waiting for the actor's response
  * @param f            a function to map a response to an ''ActorResult''
  * @param ec           the execution context to deal with futures
  * @tparam A the type of elements produced by this source
  */
class ActorSource[A](actor: ActorRef, requestMsg: Any, actorTimeout: Timeout)
                    (f: ResultFunc[A])
                    (implicit ec: ExecutionContext)
  extends GraphStage[SourceShape[A]] {
  val out: Outlet[A] = Outlet("ActorSource.out")

  override def shape: SourceShape[A] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var data: Option[A] = None

      var callback: AsyncCallback[Try[Any]] = _

      override def preStart(): Unit = {
        super.preStart()
        callback = getAsyncCallback[Try[Any]](handleActorMessage)
        sendRequest()
      }

      /**
        * Sends a request to the actor wrapped by this source.
        */
      private def sendRequest(): Unit = {
        implicit val timeout = actorTimeout
        data = None
        val response = actor ? requestMsg
        response onComplete callback.invoke
      }

      /**
        * Handles a response from the wrapped actor. Depending on the
        * ''ActorResult'' returned by the result function, data is pushed
        * downstream, the stream completes, or a failure result is produced.
        *
        * @param m the response message received from the wrapped actor
        */
      private def handleActorMessage(m: Try[Any]): Unit = {
        m match {
          case Success(msg) =>
            f(msg) match {
              case ActorCompletionResult() =>
                complete(out)
              case ActorErrorResult(ex) =>
                failStage(ex)
              case ActorDataResult(d) =>
                if (isAvailable(out)) {
                  processElement(d)
                } else {
                  data = Some(d)
                }
            }

          case Failure(ex) =>
            failStage(ex)
        }
      }

      /**
        * Processes an element to be pushed downstream. Also sends a request
        * for the next element to the wrapped actor.
        *
        * @param d the element
        */
      private def processElement(d: A): Unit = {
        push(out, d)
        sendRequest()
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          data match {
            case Some(d) =>
              processElement(d)
            case _ =>
          }
        }
      })
    }
}
