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

package de.oliver_heger.linedj.archive.protocol.webdav

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.pattern.AskTimeoutException
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
  * Test class for ''WebDavProtocol''. This class also tests some functionality
  * from the base trait for protocols.
  */
class WebDavProtocolSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("WebDavProtocolSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import system.dispatcher

  "WebDavProtocol" should "return the correct name" in {
    val protocol = new WebDavProtocol

    protocol.name should be("webdav")
  }

  it should "return the correct multi host flag" in {
    val protocol = new WebDavProtocol

    protocol.requiresMultiHostSupport shouldBe false
  }

  it should "generate a default archive URI" in {
    val SourceUri = "https://test.archive.org/my/media"
    val protocol = new WebDavProtocol

    val archiveUri = protocol generateArchiveUri SourceUri
    archiveUri.get.toString() should be(SourceUri)
  }

  it should "return a correct resolver controller" in {
    val ResolveUri = Uri("https://my-archive.com/cool/media/file.mp5")
    val BasePath = "/relative/archive/base/path"
    val protocol = new WebDavProtocol

    val controller = protocol.resolveController(ResolveUri, BasePath)
    controller match {
      case c: WebDavResolverController =>
        c.uriToResolve should be(ResolveUri)
        c.basePath should be(BasePath)
      case c =>
        fail("Unexpected controller: " + c)
    }
  }

  it should "execute a download request" in {
    val SongUri = Uri("/my/nice/song.mp3")
    val ExpRequest = HttpRequest(uri = SongUri)
    val ExpResponse = HttpResponse(status = StatusCodes.Accepted)
    implicit val timeout: Timeout = Timeout(3.seconds)
    val requestActor = system.actorOf(RequestActorTestImpl())
    RequestActorTestImpl.expectRequest(requestActor, ExpRequest, ExpResponse)
    val protocol = new WebDavProtocol

    val response = futureResult(protocol.downloadMediaFile(requestActor, SongUri))
    response.response should be(ExpResponse)
  }

  it should "correctly apply the timeout to download requests" in {
    implicit val timeout: Timeout = Timeout(200.millis)
    val probeRequestActor = TestProbe()
    val protocol = new WebDavProtocol

    expectFailedFuture[AskTimeoutException](protocol.downloadMediaFile(probeRequestActor.ref, "/test.file"))
  }
}
