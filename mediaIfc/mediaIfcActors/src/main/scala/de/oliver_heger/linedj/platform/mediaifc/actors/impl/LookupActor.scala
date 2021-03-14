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

package de.oliver_heger.linedj.platform.mediaifc.actors.impl

import akka.actor._
import de.oliver_heger.linedj.platform.mediaifc.actors.impl.LookupActor.{RemoteActorAvailable, RemoteActorUnavailable}

import scala.concurrent.duration._

object LookupActor {

  /**
   * A message sent by ''RemoteLookupActor'' when the actor to be monitored
   * could be retrieved. Communication with this actor is then possible.
   *
   * @param path the path to the actor
   * @param actor the actor reference pointing to the remote actor
   */
  case class RemoteActorAvailable(path: String, actor: ActorRef)

  /**
   * A message sent by ''RemoteLookupActor'' when the monitored actor becomes
   * no longer available. This could mean that the actor was stopped or that
   * the connection to the remote system was broken.
   * @param path the path to the monitored actor
   */
  case class RemoteActorUnavailable(path: String)

}

/**
 * An actor which monitors an actor reference in a remote actor system.
 *
 * This actor is initialized with the path to an actor it should monitor. It
 * then tests in intervals controlled by a [[DelaySequence]] whether this actor
 * is available. If this is the case, a configurable watcher actor is notified.
 *
 * After the monitored actor has become available, it is further observed. When
 * it dies (for whatever reason, including network failure or server crash) the
 * watcher actor is again notified. The lookup actor then starts anew with
 * trying to connect to the remote actor.
 *
 * @param path the path to the actor to be monitored
 * @param watcher the watcher actor to notify when state changes occur
 * @param delaySequence the delay sequence
 */
class LookupActor(path: String, watcher: ActorRef, delaySequence: DelaySequence) extends
Actor with ActorLogging {
  /** The current state of the delay sequence. */
  private var currentDelay = delaySequence

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    sendIdentifyRequest()
  }

  override def receive: Receive = lookingUp

  /**
   * Receive function used until the remote actor is detected.
   * @return the receive function
   */
  private def lookingUp: Receive = {
    case ActorIdentity(`path`, Some(actor)) =>
      context.watch(actor)
      watcher ! RemoteActorAvailable(path, actor)
      context become available(actor)

    case ActorIdentity(`path`, None) =>
      log.info("Remote actor not available: {}", path)

    case ReceiveTimeout =>
      sendIdentifyRequest()
      log.info("Timeout. Try again later.")
  }

  /**
   * Receive function used as long as the remote actor is available.
   * @param actor the remote actor
   * @return the receive function
   */
  private def available(actor: ActorRef): Receive = {
    case Terminated(`actor`) =>
      log.debug("Remote actor died.")
      watcher ! RemoteActorUnavailable(path)
      currentDelay = delaySequence
      sendIdentifyRequest()
      context become lookingUp

    case ReceiveTimeout =>
    //ignore
  }

  /**
   * Performs a lookup operation. This is done by sending an identify request
   * to the path to be monitored.
   */
  private def sendIdentifyRequest(): Unit = {
    context.actorSelection(path) ! Identify(path)
    import context.dispatcher
    context.system.scheduler.scheduleOnce(nextDelay, self, ReceiveTimeout)
  }

  /**
   * Returns the next delay as a finite duration.
   * @return the next delay value
   */
  private def nextDelay = {
    val (d, nxt) = currentDelay.nextDelay
    currentDelay = nxt
    d.seconds
  }
}
