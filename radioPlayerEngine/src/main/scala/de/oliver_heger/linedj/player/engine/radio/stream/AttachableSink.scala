/*
 * Copyright 2015-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.stream

import org.apache.pekko.{NotUsed, actor as classic}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, Inlet, Outlet, SinkShape, SourceShape}
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
  * A module that provides a special [[Sink]] implementation which allows
  * adding consumers to a stream dynamically.
  *
  * The sink materializes an actor to control its behavior and can be used to
  * attach a consumer to the stream. Initially, the sink is in unattached mode,
  * which means that all the data received is just ignored. By sending a
  * specific message to the control actor, a consumer can be attached. As a
  * response to the message, the actor sends a [[Source]] that can be used to
  * start another stream. This stream then receives the data that arrives at
  * the original sink. It is also possible to detach the consumer, switching
  * back to unattached mode.
  *
  * The use case for this sink implementation is mainly to support checks for
  * metadata exclusions. When the checking logic detects that an exclusion is
  * no longer active, the current audio stream should remain open, so that it
  * can be reused for audio playback.
  */
object AttachableSink:
  /** A default timeout for attaching consumers to a sink. */
  final val DefaultAttachTimeout = Timeout(3.seconds)

  /** The timeout for interactions with the control actor. */
  private given ControlActorTimeout: Timeout = Timeout(30.days)

  /**
    * The base trait for commands to be handled by the actor which acts as
    * controller for an attachable sink.
    *
    * @tparam T the type of data to be processed by the sink
    */
  sealed trait AttachableSinkControlCommand[+T]

  /**
    * A command for the control actor that allows attaching a consumer to the
    * [[Sink]]. In response, the actor sends a [[ConsumerAttached]] message
    * with a [[Source]] that can be used to consume the data stream. Note that
    * only a single consumer can be attached at a given time.
    *
    * @param replyTo the actor to send the reply to
    * @tparam T the type of data to be processed by the sink
    */
  case class AttachConsumer[T](replyTo: ActorRef[ConsumerAttached[T]]) extends AttachableSinkControlCommand[T]

  /**
    * A command for the control actor that allows to switch the [[Sink]] back
    * to unattached mode. After processing this command, the sink will ignore
    * again all the data is receives.
    */
  case class DetachConsumer() extends AttachableSinkControlCommand[Nothing]

  /**
    * An internal command for the control actor to pass a stream message from
    * the attachable sink. If a consumer is attached, the actor forwards the
    * message to it; otherwise, it ignores the message. It replies with a
    * [[MessageProcessed]] message when it is ready to receive the next
    * message.
    *
    * @param message the message to be processed
    * @param replyTo the actor to send the reply to
    * @tparam T the type of data to be processed by the sink
    */
  private case class PutMessage[T](message: StreamMessage[T],
                                   replyTo: ActorRef[MessageProcessed]) extends AttachableSinkControlCommand[T]

  /**
    * An internal command for the control actor to query the next message for
    * an attached source. The actor then sends the next message it receives
    * from the sink.
    *
    * @param replyTo the actor to send the reply to
    * @tparam T the type of data to be processed by the sink
    */
  private case class GetMessage[T](replyTo: ActorRef[StreamMessage[T]]) extends AttachableSinkControlCommand[T]

  /**
    * A response message sent by the control actor for a request to attach a
    * new consumer. The [[Source]] in this response can be used to run a new
    * stream that receives the data passed to the original sink.
    *
    * @param source the source to consume the stream data
    * @tparam T the type of the data produced by the source
    */
  case class ConsumerAttached[T](source: Source[T, NotUsed])

  /**
    * An internal response message sent by the control actor after a stream
    * message has been delivered to the consumer.
    */
  private case class MessageProcessed()

  /**
    * A data type defining the different messages that can be passed from a
    * sink to an attached source via the control actor. Via these messages,
    * stream data and control signals are forwarded to consumers.
    *
    * @tparam T the type of data processed by the stream
    */
  private enum StreamMessage[+T]:
    /**
      * A message type to represent an element with data that is passed through
      * the stream.
      */
    case Data(element: T)

    /**
      * A message type indicating that the original stream has been completed.
      */
    case Complete

    /**
      * A message type indicating that the original stream has failed with the
      * contained exception.
      */
    case Failure(ex: Throwable)
  end StreamMessage

  /**
    * Creates a [[Sink]] which allows attaching a consumer dynamically. The
    * sink materializes an actor instance that can be used to attach or detach
    * consumers to it.
    *
    * @param name     a name for the sink; this should be unique to allow that
    *                 multiple instances can coexist in parallel
    * @param buffered a flag whether the last value received by the sink in
    *                 unattached mode should be buffered; if '''true''', such a
    *                 value is passed directly to a consumer when it is newly
    *                 attached; otherwise, a new consumer receives a value only
    *                 after new data is received by the sink
    * @param system   an implicit actor system
    * @tparam T the type of data to be processed by the sink
    * @return the [[Sink]] supporting dynamic attach operations
    */
  def apply[T](name: String, buffered: Boolean = false)
              (using system: classic.ActorSystem): Sink[T, ActorRef[AttachableSinkControlCommand[T]]] =
    val controlActor = system.spawn(createControlActor(name, buffered), name)
    Sink.fromGraph(new SinkImpl[T](controlActor))

  /**
    * A helper function for attaching a consumer to a sink that is controlled
    * by the given actor. The function returns a [[Future]] with a [[Source]]
    * from which a new stream can be created to receive the data passed to the
    * original sink.
    *
    * @param controlActor the control actor for the attachable sink
    * @param timeout      a timeout for interactions with the actor
    * @param system       an implicit actor system
    * @tparam T the type of the data processed by the sink
    * @return a [[Future]] with a [[Source]] to consume the data
    */
  def attachConsumer[T](controlActor: ActorRef[AttachableSinkControlCommand[T]],
                        timeout: Timeout = DefaultAttachTimeout)
                       (using system: classic.ActorSystem): Future[Source[T, NotUsed]] =
    controlActor.ask[ConsumerAttached[T]] { ref =>
      AttachConsumer(ref)
    }.map(_.source)

  /**
    * A helper function for detaching the consumer from the sink controlled by
    * the given actor. This is a fire-and-forget operation for which no
    * confirmation is returned. Detaching happens asynchronously. Note that the
    * stream using the attached source has to be stopped manually.
    *
    * @param controlActor the control actor for the attachable sink
    * @tparam T the type of the data processed by the sink
    */
  def detachConsumer[T](controlActor: ActorRef[AttachableSinkControlCommand[T]]): Unit =
    controlActor ! DetachConsumer()

  /**
    * An internal implementation of a [[Sink]] that passes the data it receives
    * to the given control actor. This actor then decides how to handle this
    * data.
    *
    * @param controlActor the control actor for this sink
    * @param scheduler    the scheduler for executing futures
    * @param ec           the execution context
    * @tparam T the type of data to be processed by this sink
    */
  private class SinkImpl[T](controlActor: ActorRef[AttachableSinkControlCommand[T]])
                           (using scheduler: Scheduler, ec: ExecutionContext)
    extends GraphStageWithMaterializedValue[SinkShape[T], ActorRef[AttachableSinkControlCommand[T]]]:
    private val in: Inlet[T] = Inlet("attachableSinkImpl")

    override def shape: SinkShape[T] = SinkShape(in)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
    (GraphStageLogic, ActorRef[AttachableSinkControlCommand[T]]) =
      val logic = new GraphStageLogic(shape):
        /** A callback to handle the response from the control actor. */
        private val onProcessedCallback = getAsyncCallback[Try[MessageProcessed]](messageProcessed)

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            val elem = grab(in)
            sendMessage(StreamMessage.Data(elem))

          override def onUpstreamFinish(): Unit =
            super.onUpstreamFinish()
            sendMessage(StreamMessage.Complete)

          override def onUpstreamFailure(ex: Throwable): Unit =
            super.onUpstreamFailure(ex)
            sendMessage(StreamMessage.Failure(ex))
        )

        override def preStart(): Unit =
          pull(in)

        /**
          * Sends the given message to the control actor and prepares the
          * processing of the response.
          *
          * @param message the message to be sent
          */
        private def sendMessage(message: StreamMessage[T]): Unit =
          controlActor.ask[MessageProcessed] { ref =>
            PutMessage(message, ref)
          }.onComplete(onProcessedCallback.invoke)

        /**
          * A callback function that is invoked when the control actor sends
          * the response that the last stream element has been processed. Then
          * the next element can be requested.
          *
          * @param response the response from the actor
          */
        private def messageProcessed(response: Try[MessageProcessed]): Unit =
          response match
            case Success(_) =>
              pull(in)
            case Failure(ex) =>
              failStage(ex)

      (logic, controlActor)
  end SinkImpl

  /**
    * An internal implementation of a [[Source]] that can be used by dynamic
    * consumers to obtain the data passed to the original sink. It queries the
    * control actor for the elements to pass downstream.
    *
    * @param controlActor the control actor
    * @param scheduler    the scheduler for executing futures
    * @param ec           the execution context
    * @tparam T the type of the data to be processed
    */
  private class SourceImpl[T](controlActor: ActorRef[AttachableSinkControlCommand[T]])
                             (using scheduler: Scheduler, ec: ExecutionContext)
    extends GraphStage[SourceShape[T]]:
    private val out = Outlet[T]("attachedSource")

    override def shape: SourceShape[T] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape):
      /** A callback to handle responses from the control actor. */
      private val onMessageCallback = getAsyncCallback[Try[StreamMessage[T]]](messageReceived)

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          controlActor.ask[StreamMessage[T]](ref => GetMessage(ref)).onComplete(onMessageCallback.invoke)
      )

      /**
        * A callback function that is invoked when a reply from the control
        * actor with the next message to process arrives.
        *
        * @param triedMessage the message from the control actor
        */
      private def messageReceived(triedMessage: Try[StreamMessage[T]]): Unit =
        triedMessage match
          case Failure(exception) =>
            failStage(exception)
          case Success(StreamMessage.Data(element)) =>
            push(out, element)
          case Success(StreamMessage.Complete) =>
            completeStage()
          case Success(StreamMessage.Failure(ex)) =>
            failStage(ex)

  /**
    * An internal data class to hold the state of the control actor for the
    * attachable sink.
    *
    * @param elements   elements to be passed from the sink to the source; per
    *                   default, there is only a single element; only in
    *                   corner case, such as the end of the stream, there can
    *                   be two elements
    * @param producer   a producer waiting for the element to be consumed
    * @param consumer   a consumer waiting for an element to become available
    * @param isAttached a flag whether a consumer is attached to the sink
    * @param sinkName   the name of the attachable sink
    * @param buffered   flag whether buffering is enabled
    * @tparam T the type of the data to be processed
    */
  private case class ControlActorState[T](elements: List[StreamMessage[T]],
                                          producer: Option[ActorRef[MessageProcessed]],
                                          consumer: Option[ActorRef[StreamMessage[T]]],
                                          isAttached: Boolean,
                                          sinkName: String,
                                          buffered: Boolean):
    /**
      * Sends a [[MessageProcessed]] notification to a producer if one is
      * pending.
      */
    def notifyPendingProducer(): Unit =
      producer.foreach(_ ! MessageProcessed())

  /**
    * The handler function of the actor that controls an attachable sink. The
    * actor keeps track whether there is a consumer attached to the sink. If
    * this is the case, it acts as a bridge between the sink and the [[Source]]
    * used by the consumer.
    *
    * @param name     the name of the sink to be controlled
    * @param buffered flag whether the sink is buffered
    * @tparam T the type of data to be processed
    * @return the [[Behavior]] for the new actor instance
    */
  private def createControlActor[T](name: String, buffered: Boolean): Behavior[AttachableSinkControlCommand[T]] =
    val state = ControlActorState[T](
      elements = Nil,
      producer = None,
      consumer = None,
      isAttached = false,
      sinkName = name,
      buffered = buffered
    )
    handleControlCommand(state)

  /**
    * The message handler function for the control actor.
    *
    * @param state the current state of the actor
    * @tparam T the type of data to be processed
    * @return the [[Behavior]] for the actor instance
    */
  private def handleControlCommand[T](state: ControlActorState[T]): Behavior[AttachableSinkControlCommand[T]] =
    Behaviors.receive {
      case (ctx, pm: PutMessage[T] @unchecked) if state.isAttached =>
        state.consumer match
          case Some(consumerRef) =>
            consumerRef ! pm.message
            nextStateForMessage(ctx, state, pm.message) {
              pm.replyTo ! MessageProcessed()
              handleControlCommand(state.copy(consumer = None))
            }
          case None =>
            handleControlCommand(state.copy(elements = state.elements :+ pm.message, producer = Some(pm.replyTo)))

      case (ctx, pm: PutMessage[T] @unchecked) =>
        nextStateForMessage(ctx, state, pm.message) {
          pm.replyTo ! MessageProcessed()
          if state.buffered then
            handleControlCommand(state.copy(elements = List(pm.message)))
          else
            Behaviors.same
        }

      case (ctx, gm: GetMessage[T] @unchecked) =>
        state.elements match
          case message :: t =>
            gm.replyTo ! message
            nextStateForMessage(ctx, state, message) {
              state.notifyPendingProducer()
              handleControlCommand(state.copy(elements = t, producer = None))
            }
          case _ =>
            handleControlCommand(state.copy(consumer = Some(gm.replyTo)))

      case (ctx, ac: AttachConsumer[T] @unchecked) =>
        ctx.log.info("Attaching consumer to sink '{}'.", state.sinkName)

        given Scheduler = ctx.system.scheduler

        given ExecutionContext = ctx.system.executionContext

        val consumerSource = Source.fromGraph(new SourceImpl(ctx.self))
        ac.replyTo ! ConsumerAttached(consumerSource)
        handleControlCommand(state.copy(isAttached = true))

      case (ctx, DetachConsumer()) =>
        ctx.log.info("Detaching consumer from sink '{}'.", state.sinkName)
        if state.isAttached then
          state.notifyPendingProducer()
          handleControlCommand(state.copy(isAttached = false, producer = None))
        else
          ctx.log.warn("Ignoring DetachConsumer command for sink '{}', since no consumer is attached.",
            state.sinkName)
          Behaviors.same
    }

  /**
    * Returns a follow-up state based on the given message. If this happens to
    * be a message indicating the end of the stream, this actor gets stopped.
    * Otherwise, the given block is executed to compute the next state.
    *
    * @param ctx     the actor context
    * @param state   the current state
    * @param message the message in question
    * @param block   the bock to compute the next state
    * @tparam T the type of data to be processed
    * @return the next state for this actor
    */
  private def nextStateForMessage[T](ctx: ActorContext[AttachableSinkControlCommand[T]],
                                     state: ControlActorState[T],
                                     message: StreamMessage[T])
                                    (block: => Behavior[AttachableSinkControlCommand[T]]):
  Behavior[AttachableSinkControlCommand[T]] =
    if isStreamEnd(message) then
      ctx.log.info(
        "Stopping control actor '{}' after message '{}', attached state = {}.",
        state.sinkName,
        message,
        state.isAttached
      )
      Behaviors.stopped
    else
      block

  /**
    * Checks if the given message indicates an end of the original stream. This
    * is used to figure out whether the control actor needs to be stopped.
    *
    * @param message the message
    * @tparam U the type of the data
    * @return a flag whether this message indicates the stream end
    */
  private def isStreamEnd[U](message: StreamMessage[U]): Boolean =
    message match
      case StreamMessage.Data(_) => false
      case StreamMessage.Complete => true
      case StreamMessage.Failure(ex) => true

  /**
    * Provides an [[ExecutionContext]] in implicit scope from the given actor
    * system.
    *
    * @param system the actor system
    * @return the execution context
    */
  private given executionContext(using system: classic.ActorSystem): ExecutionContext = system.dispatcher

  /**
    * Provides a typed actor system from an untyped one in implicit scope.
    *
    * @param system the classic actor system
    * @return the typed actor system
    */
  private given typedActorSystem(using system: classic.ActorSystem): ActorSystem[_] = system.toTyped
