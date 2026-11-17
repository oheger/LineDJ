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

package de.oliver_heger.linedj.platform.radio.impl

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, HttpRequestSenderFactoryImpl, Spawner}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

private object PlayerServiceImpl:
  /** The name that is used for the request sender actor. */
  final val SenderName = "radioPlayerServerClient"

  /**
    * A factory trait for creating new instances of [[PlayerServiceImpl]].
    */
  trait Factory:
    /**
      * Creates a new [[PlayerServiceImpl]] instance to interact with the radio
      * player server at the given URI.
      *
      * @param uri           the URI of the radio player server
      * @param senderFactory the factory to create the sender actor
      * @param system        the actor system
      * @param timeout       a timeout for sending requests
      * @return the newly created instance
      */
    def apply(uri: String,
              senderFactory: HttpRequestSenderFactory = HttpRequestSenderFactoryImpl)
             (using system: classic.ActorSystem, timeout: Timeout): PlayerServiceImpl

  /**
    * A default [[Factory]] instance for creating a new service instance.
    */
  final val newInstance: Factory = new Factory:
    override def apply(uri: String,
                       senderFactory: HttpRequestSenderFactory)
                      (using system: classic.ActorSystem, timeout: Timeout): PlayerServiceImpl =
      val senderConfig = HttpRequestSenderConfig(
        actorName = Some(SenderName)
      )
      val spawner: Spawner = system
      val sender = senderFactory.createRequestSender(spawner, uri, senderConfig)
      new PlayerServiceImpl(sender, system)
end PlayerServiceImpl

/**
  * A default implementation of the [[PlayerService]] trait. This implementation
  * uses an [[HttpRequestSender]] actor to communicate with the radio player
  * server. This actor is created by the factory. It is released by the
  * ''close()'' function which should be called when the service is no longer
  * needed.
  *
  * @param requestSender the actor for sending requests
  * @param actorSystem   the actor system
  * @param timeout       a timeout for sending requests
  */
private class PlayerServiceImpl(override val requestSender: ActorRef[HttpRequestSender.HttpCommand],
                                override val actorSystem: classic.ActorSystem)
                               (using timeout: Timeout) extends PlayerService, AutoCloseable:
  /** The implicit typed actor system. */
  private given ActorSystem[Nothing] = actorSystem.toTyped

  /** The implicit execution context. */
  private given ExecutionContext = actorSystem.dispatcher

  override def sendRequest(request: HttpRequest): Future[HttpResponse] =
    HttpRequestSender.sendRequestSuccess(requestSender, request).map(_.response)

  override def close(): Unit =
    requestSender ! HttpRequestSender.Stop
