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
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import org.apache.pekko.stream.{Attributes, Inlet, SinkShape}

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
    * An internal command for the control actor to signal that the stream is
    * done, and the actor should terminate itself.
    */
  private case class Stop() extends AttachableSinkControlCommand[Nothing]

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
    val controlActor = system.spawn(createControlActor(), name)
    Sink.fromGraph(new SinkImpl[T](controlActor))

  /**
    * An internal implementation of a [[Sink]] that passes the data it receives
    * to the given control actor. This actor then decides how to handle this
    * data.
    *
    * @param controlActor the control actor for this sink
    * @tparam T the type of data to be processed by this sink
    */
  private class SinkImpl[T](controlActor: ActorRef[AttachableSinkControlCommand[T]])
    extends GraphStageWithMaterializedValue[SinkShape[T], ActorRef[AttachableSinkControlCommand[T]]]:
    private val in: Inlet[T] = Inlet("attachableSinkImpl")

    override def shape: SinkShape[T] = SinkShape(in)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
    (GraphStageLogic, ActorRef[AttachableSinkControlCommand[T]]) =
      val logic = new GraphStageLogic(shape):
        setHandler(in, new InHandler:
          override def onPush(): Unit =
            grab(in)
            pull(in)
        )

        override def preStart(): Unit =
          pull(in)

        override def postStop(): Unit =
          controlActor ! Stop()
          super.postStop()

      (logic, controlActor)

  /**
    * The handler function of the actor that controls an attachable sink. The
    * actor keeps track whether there is a consumer attached to the sink. If
    * this is the case, it acts as a bridge between the sink and the [[Source]]
    * used by the consumer.
    *
    * @tparam T the type of data to be processed
    * @return the [[Behavior]] for the new actor instance
    */
  private def createControlActor[T](): Behavior[AttachableSinkControlCommand[T]] =
    Behaviors.receivePartial {
      case (ctx, Stop()) =>
        ctx.log.info("Received stop command.")
        Behaviors.stopped
    }
