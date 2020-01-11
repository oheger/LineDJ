/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.io

import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.http.scaladsl.model.HttpRequest
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.SendRequest

/**
  * A trait that provides functionality for actors that extend the mechanism to
  * send HTTP requests.
  *
  * This trait can be extended by actors that wrap an [[HttpRequestActor]] and
  * modify requests before they are actually executed by the request actor.
  * This is used for instance to implement different ways of authentication.
  * The idea is that the original request is received by the extension actor,
  * modified, and then passed to the wrapped ''HttpRequestActor''. The response
  * is then passed to the caller.
  *
  * As the extension actor transparently replaces the wrapped request actor, it
  * has to make sure that it is stopped together with the extension actor. This
  * trait offers a corresponding implementation in its ''postStop()'' method.
  */
trait HttpExtensionActor {
  this: Actor =>

  /** Reference to the HTTP actor for executing requests. */
  protected val httpActor: ActorRef

  /**
    * @inheritdoc This implementation stops the underlying HTTP actor.
    */
  override def postStop(): Unit = {
    httpActor ! PoisonPill
  }

  /**
    * Sends a modified request to the wrapped HTTP actor. A copy of the
    * original request message is created with an ''HttpRequest'' obtained by
    * invoking the given function.
    *
    * @param orgRequest the original request
    * @param caller     the caller
    * @param f          the function to modify the HTTP request
    */
  protected def modifyAndForward(orgRequest: SendRequest, caller: ActorRef = sender())
                                (f: HttpRequest => HttpRequest): Unit = {
    val modifiedRequest = f(orgRequest.request)
    httpActor.tell(orgRequest.copy(request = modifiedRequest), caller)
  }
}
