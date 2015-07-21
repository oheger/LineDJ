/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj

import akka.actor._
import scala.concurrent.duration._

/**
 * Actor testing remote lookup.
 */
class LookupActor(path: String) extends Actor with ActorLogging {
  private var remoteActor: ActorRef = _

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    sendIdentifyRequest()
  }

  override def receive: Receive = {
    case ActorIdentity(`path`, Some(actor)) =>
      context.watch(actor)
      log.info("Got reference to actor.")
      remoteActor = actor

    case ActorIdentity(`path`, None) =>
      log.info("Remote actor not available: {}", path)

    case ReceiveTimeout =>
      if(remoteActor == null) {
        sendIdentifyRequest()
        log.info("Timeout. Try again later.")
      }

    case term: Terminated if term.actor == remoteActor =>
      log.debug("Remote actor died.")
      sendIdentifyRequest()
      remoteActor = null
  }

  private def sendIdentifyRequest(): Unit = {
    context.actorSelection(path) ! Identify(path)
    import context.dispatcher
    context.system.scheduler.scheduleOnce(5.seconds, self, ReceiveTimeout)
  }
}
