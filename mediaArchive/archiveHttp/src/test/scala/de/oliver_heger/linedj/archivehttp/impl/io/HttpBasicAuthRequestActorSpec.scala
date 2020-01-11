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

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.headers.{Accept, Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, MediaRanges, StatusCodes}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.crypt.Secret
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object HttpBasicAuthRequestActorSpec {
  /** An object with test user credentials. */
  private val Credentials = UserCredentials("scott", Secret("tiger"))
}

/**
  * Test class for ''HttpBasicAuthRequestActor''.
  */
class HttpBasicAuthRequestActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("HttpBasicAuthRequestActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import HttpBasicAuthRequestActorSpec._

  "A HttpBasicAuthRequestActor" should "add a correct Authorization header to a request" in {
    val request = HttpRequest(uri = "http://request.test.org/foo",
      headers = List(Accept(MediaRanges.`text/*`)))
    val expRequest = request.copy(headers = Authorization(BasicHttpCredentials(Credentials.userName,
      Credentials.password.secret)) :: request.headers.toList)
    val response = HttpResponse(status = StatusCodes.Accepted)
    val Data = new Object
    val requestActor = system.actorOf(RequestActorTestImpl())
    RequestActorTestImpl.expectRequest(requestActor, expRequest, response)
    val authActor = system.actorOf(Props(classOf[HttpBasicAuthRequestActor], Credentials, requestActor))

    authActor ! HttpRequests.SendRequest(request, Data)
    expectMsg(HttpRequests.ResponseData(response, Data))
  }

  it should "stop the underlying request actor when it is stopped" in {
    val requestActor = system.actorOf(RequestActorTestImpl())
    val authActor = system.actorOf(Props(classOf[HttpBasicAuthRequestActor], Credentials, requestActor))

    system stop authActor
    watch(requestActor)
    expectTerminated(requestActor)
  }
}
