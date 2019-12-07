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

package de.oliver_heger.linedj.archivehttp.http

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpMessage, HttpResponse, ResponseEntity}
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.ResponseData
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Test class for ''HttpRequests''.
  */
class HttpRequestsSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll
  with Matchers with MockitoSugar with AsyncTestHelper {
  def this() = this(ActorSystem("HttpRequestsSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  "HttpRequests" should "support discarding the bytes of an entity" in {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    import system.dispatcher
    val entity = mock[ResponseEntity]
    val discardedEntity = new HttpMessage.DiscardedEntity(Future.successful(Done))
    when(entity.discardBytes()(mat)).thenReturn(discardedEntity)
    val response = HttpResponse(entity = entity)
    val responseData = ResponseData(response, 42)

    val result = futureResult(HttpRequests.discardEntityBytes(Future.successful(responseData)))
    result should be(responseData)
    verify(entity).discardBytes()(mat)
  }
}
