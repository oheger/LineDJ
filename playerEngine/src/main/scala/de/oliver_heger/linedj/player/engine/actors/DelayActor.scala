/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, typed}

import scala.concurrent.duration._

object DelayActor:
  /** A delay value that means ''no delay''. */
  val NoDelay: FiniteDuration = 0.seconds

  case object Propagate:
    /**
      * Returns a new instance of ''Propagate'' that contains only a single
      * message to be forwarded to a target actor after a delay.
      *
      * @param msg    the message to be forwarded
      * @param target the target actor
      * @param delay  the delay
      * @return the new ''Propagate'' instance
      */
    def apply(msg: Any, target: ActorRef, delay: FiniteDuration): Propagate =
      new Propagate(List((msg, target)), delay)

  /**
    * A message processed by [[DelayActor]] that causes the specified
    * message(s) to be sent to the target(s) after the given delay.
    *
    * @param sendData contains the messages and their target actors
    * @param delay    the delay
    */
  case class Propagate(sendData: Iterable[(Any, ActorRef)], delay: FiniteDuration):
    /**
      * Returns the actor reference considered the target of this propagation.
      * This is, by coincidence, the first target in the list of messages. Note
      * that empty lists are not handled.
      *
      * @return the target actor of this propagation
      */
    def target: ActorRef = sendData.head._2

  /**
    * A data class used internally to store information about pending delayed
    * invocations. Messages of this type are sent to this actor by the
    * scheduler when a delayed invocation is due. The class contains the
    * information required to pass the desired message to the target actor plus
    * additional information to prevent that outdated invocations are
    * processed.
    *
    * @param propagate the original ''Propagate'' message
    * @param seqNo     a sequence number; this is used to detect outdated messages
    */
  private[engine] case class DelayedInvocation(propagate: Propagate, seqNo: Int)

  /**
    * An internal data class for keeping track on pending scheduled
    * invocations. Instances of this class are stored by the actor so that it
    * can react accordingly when it is notified by the scheduler. The main
    * purpose of this class is to detect stale notifications from the scheduler
    * actor; this is achieved using a sequence number.
    *
    * @param seqNo       the current sequence number
    */
  private case class DelayData(seqNo: Int) extends AnyVal

  /**
    * Creates a ''Props'' object for creating an actor instance.
    * @param schedulerActor the actor for doing scheduled invocations
    * @return creation ''Props'' for a new actor instance
    */
  def apply(schedulerActor: typed.ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand]): Props =
    Props(classOf[DelayActor], schedulerActor)

/**
  * An actor class which can delay messages to a target actor.
  *
  * This actor is used by the player engine to send a message with a certain
  * delay to a target actor. This is useful for instance on startup time: When
  * the player starts, it may be the case that not all required MP3 context
  * factory objects have already been registered; therefore, it makes sense to
  * give them some time to start up. Also, for error handling it may be a
  * strategy to wait some time before retrying an operation (e.g. switch to a
  * specific radio source which may be temporarily not available).
  *
  * This actor basically processes messages that define a target actor, a
  * message to be sent to the target, and a delay. If the delay is less than or
  * equal to 0, the message is sent immediately. Otherwise, a one-time
  * scheduler task is created to send the message after the specified delay.
  * The latter is done using [[ScheduledInvocationActor]].
  *
  * Note that for the use cases needed for the audio player engine it is not
  * necessary to preserve the original sender of the message; so the message is
  * just sent from this actor.
  *
  * Delayed messages for a specific target actor are removed when another
  * message for this target actor comes in. So only a single message can be
  * pending for one actor.
  *
  * Sometimes, multiple messages have to be sent together after a delay. This
  * is achieved by specifying multiple pairs of message and target actor in a
  * single message to be processed by this actor. In this case, the first
  * target actor is considered the relevant one.
  */
class DelayActor(schedulerActor: typed.ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand])
  extends Actor with ActorLogging:
  import DelayActor._

  /** A map for the pending scheduled invocations. */
  private val pendingSchedules = collection.mutable.Map.empty[ActorRef, DelayData]

  /** A counter for generating sequence numbers. */
  private var sequenceCounter = 0

  override def receive: Receive =
    case p: Propagate =>
      pendingSchedules.remove(p.target)
      if p.delay > NoDelay then
        pendingSchedules += p.target -> scheduleInvocation(p)
      else
        propagate(p)

    case DelayedInvocation(prop, seqNo) =>
      log.debug("Received delayed invocation.")
      pendingSchedules.remove(prop.target) match
        case Some(DelayData(seq)) if seqNo == seq =>
          log.debug("Propagating: {}.", prop)
          propagate(prop)
        case _ => // outdated or unexpected

    case CloseRequest =>
      pendingSchedules.clear()
      sender() ! CloseAck(self)

  /**
    * Prepares the scheduler to handle a propagation after the specified
    * delay.
    *
    * @param p the ''Propagate'' object
    * @return the internal data for this delayed invocation
    */
  private def scheduleInvocation(p: Propagate): DelayData =
    log.debug("Scheduling invocation for {}.", p)
    val currentCount = sequenceCounter
    sequenceCounter += 1
    val scheduleMessage = DelayedInvocation(p, currentCount)
    val invocation = ScheduledInvocationActor.ClassicActorInvocation(self, scheduleMessage)
    schedulerActor ! ScheduledInvocationActor.ActorInvocationCommand(p.delay, invocation)

    DelayData(currentCount)

  /**
    * Handles propagation. Sends the messages to their target actors.
    *
    * @param propagate the ''Propagate'' object
    */
  private def propagate(propagate: Propagate): Unit =
    propagate.sendData foreach { t =>
      t._2 ! t._1
    }
