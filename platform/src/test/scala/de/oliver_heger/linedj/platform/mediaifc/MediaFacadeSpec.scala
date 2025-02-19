/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.GetMetadata
import org.apache.commons.configuration.Configuration
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Failure

/**
  * Test class for ''MediaFacade''.
  */
class MediaFacadeSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A MediaFacade" should "register a metadata listener" in:
    val facade = new MediaFacadeImpl
    val MediumId = MediumID("A medium", None)

    val regID = facade queryMetadataAndRegisterListener MediumId
    facade.sentMessages should be(List((MediaActors.MetadataManager,
      GetMetadata(MediumId, registerAsListener = true, regID))))

  it should "generate a sequence of registration IDs" in:
    val count = 16
    val facade = new MediaFacadeImpl

    val regIDs = (1 to count).map(
      i => facade queryMetadataAndRegisterListener MediumID("m" + i, None)).toSet
    regIDs should have size count
    regIDs should not contain MediaFacade.InvalidListenerRegistrationID

  it should "increment the registration IDs in a thread-safe manner" in:
    val ThreadCount = 16
    val facade = new MediaFacadeImpl
    val latch = new CountDownLatch(1)
    val threads = (1 to ThreadCount) map(_ => new RegisterMediumListenerThread(facade, latch))
    threads foreach (_.start())
    latch.countDown()
    threads foreach(_.join(5000))

    val ids = threads.foldLeft(Set.empty[Int])((s, t) => s ++ t.ids)
    ids should have size ThreadCount*32

  /**
    * Expects that the given future result is a failure.
    *
    * @param facade    the media facade
    * @param futActors the future
    */
  private def expectFailedFuture(facade: MediaFacadeImpl, futActors: Future[MediaFacade
  .MediaFacadeActors]): Unit =
    Await.ready(futActors, facade.expTimeout.duration).value match
      case Some(Failure(_)) => // expected
      case _ => fail("Unexpected result!")

  /**
    * Helper method for querying the facade actors.
    *
    * @param facade the facade object to be used
    */
  private def checkSuccessfulFacadeActorsRequest(facade: MediaFacadeImpl): Unit =
    val actMediaManager = mock[ActorRef]
    val actMetaManager = mock[ActorRef]
    facade.responseMediaManager = Future.successful(Some(actMediaManager))
    facade.responseMetadataManager = Future.successful(Some(actMetaManager))
    implicit val timeout: Timeout = facade.expTimeout

    val futActors = facade.requestFacadeActors()
    val actors = Await.result(futActors, facade.expTimeout.duration)
    actors.mediaManager should be(actMediaManager)
    actors.metadataManager should be(actMetaManager)

  it should "allow querying an object with interface actors" in:
    checkSuccessfulFacadeActorsRequest(new MediaFacadeImpl)

  it should "allow querying interface actors with a different timeout" in:
    checkSuccessfulFacadeActorsRequest(new MediaFacadeImpl(Timeout(1.second)))

  it should "take the timeout into account when querying interface actors" in {

  }

  it should "fail to return interface actors if an actor cannot be obtained" in:
    val facade = new MediaFacadeImpl
    val actMediaManager = mock[ActorRef]
    facade.responseMediaManager = Future.successful(Some(actMediaManager))
    facade.responseMetadataManager = Future.failed(new Exception("test exception"))
    implicit val timeout: Timeout = facade.expTimeout

    val futActors = facade.requestFacadeActors()
    expectFailedFuture(facade, futActors)

  it should "fail to return interface actors if one actor is undefined" in:
    val facade = new MediaFacadeImpl
    val actMetaManager = mock[ActorRef]
    facade.responseMetadataManager = Future.successful(Some(actMetaManager))
    facade.responseMediaManager = Future.successful(None)
    implicit val timeout: Timeout = facade.expTimeout

    val futActors = facade.requestFacadeActors()
    expectFailedFuture(facade, futActors)

/**
  * Test implementation of the trait.
  *
  * @param expTimeout the timeout for actor requests
  */
class MediaFacadeImpl(val expTimeout: Timeout = Timeout(5.seconds)) extends MediaFacade:
  override val bus: MessageBus = null

  /** Records messages passed to the send() method (in reverse order). */
  var sentMessages = List.empty[(MediaActor, Any)]

  /** Response to be returned for the media manager by requestActor(). */
  var responseMediaManager: Future[Option[ActorRef]] = _

  /** Response to be returned for the metadata manager by requestActor(). */
  var responseMetadataManager: Future[Option[ActorRef]] = _

  override def activate(enabled: Boolean): Unit = ???

  override def send(target: MediaActor, msg: Any): Unit =
    sentMessages = (target, msg) :: sentMessages

  override def initConfiguration(config: Configuration): Unit = ???

  override def requestMediaState(): Unit = ???

  /**
    * @inheritdoc Returns the response defined for the specified actor.
    */
  override def requestActor(target: MediaActor)(implicit timeout: Timeout):
  Future[Option[ActorRef]] =
    if timeout != expTimeout then
      throw new AssertionError("Unexpected timeout: " + timeout)
    target match
      case MediaActors.MediaManager => responseMediaManager
      case MediaActors.MetadataManager => responseMetadataManager

  override def removeMetadataListener(mediumID: MediumID): Unit = ???

  override def registerMetadataStateListener(componentID: ComponentID): Unit = ???

  override def unregisterMetadataStateListener(componentID: ComponentID): Unit = ???

/**
  * A test thread class for generating listener registration IDs in parallel.
  */
private class RegisterMediumListenerThread(facade: MediaFacade, latch: CountDownLatch)
  extends Thread:
  /** A set with the IDs that have been obtained from the facade. */
  var ids = Set.empty[Int]

  override def run(): Unit =
    if latch.await(10, TimeUnit.SECONDS) then
      ids = (1 to 32).map(
        i => facade queryMetadataAndRegisterListener MediumID("test" + i, None)).toSet
