/*
 * Copyright 2015-2019 The Developers Team.
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

import akka.actor.{Actor, ActorRef}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.impl.io.HttpRequestActor.SendRequest

/**
  * An extension actor that adds support for basic auth to an HTTP request
  * actor.
  *
  * This actor implementation intercepts incoming requests and adds an
  * ''Authorization'' header for a given set of credentials.
  *
  * @param credentials the credentials to be added to requests
  * @param httpActor   the underlying HTTP request actor
  */
class HttpBasicAuthRequestActor(credentials: UserCredentials, override val httpActor: ActorRef) extends Actor
  with HttpExtensionActor {
  /** The authorization header that is added to requests. */
  private val authHeader = Authorization(BasicHttpCredentials(credentials.userName, credentials.password.secret))

  override def receive: Receive = {
    case req: SendRequest =>
      modifyAndForward(req) { request =>
        request.copy(headers = authHeader :: request.headers.toList)
      }
  }
}
