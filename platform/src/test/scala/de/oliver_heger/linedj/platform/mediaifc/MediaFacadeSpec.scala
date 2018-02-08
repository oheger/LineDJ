/*
 * Copyright 2015-2018 The Developers Team.
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

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.ActorRef
import akka.util.Timeout
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.GetMetaData
import org.apache.commons.configuration.Configuration
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Failure

/**
  * Test class for ''MediaFacade''.
  */
class MediaFacadeSpec extends FlatSpec with Matchers with MockitoSugar {
  "A MediaFacade" should "register a meta data listener" in {
    val facade = new MediaFacadeImpl
    val MediumId = MediumID("A medium", None)

    val regID = facade queryMetaDataAndRegisterListener MediumId
    facade.sentMessages should be(List((MediaActors.MetaDataManager,
      GetMetaData(MediumId, registerAsListener = true, regID))))
  }

  it should "generate a sequence of registration IDs" in {
    val count = 16
    val facade = new MediaFacadeImpl

    val regIDs = (1 to count).map(
      i => facade queryMetaDataAndRegisterListener MediumID("m" + i, None)).toSet
    regIDs should have size count
    regIDs should not contain MediaFacade.InvalidListenerRegistrationID
  }

  it should "increment the registration IDs in a thread-safe manner" in {
    val ThreadCount = 16
    val facade = new MediaFacadeImpl
    val latch = new CountDownLatch(1)
    val threads = (1 to ThreadCount) map(_ => new RegisterMediumListenerThread(facade, latch))
    threads foreach (_.start())
    latch.countDown()
    threads foreach(_.join(5000))

    val ids = threads.foldLeft(Set.empty[Int])((s, t) => s ++ t.ids)
    ids should have size ThreadCount*32
  }

  /**
    * Expects that the given future result is a failure.
    *
    * @param facade    the media facade
    * @param futActors the future
    */
  private def expectFailedFuture(facade: MediaFacadeImpl, futActors: Future[MediaFacade
  .MediaFacadeActors]): Unit = {
    Await.ready(futActors, facade.expTimeout.duration).value match {
      case Some(Failure(_)) => // expected
      case _ => fail("Unexpected result!")
    }
  }

  /**
    * Helper method for querying the facade actors.
    *
    * @param facade the facade object to be used
    */
  private def checkSuccessfulFacadeActorsRequest(facade: MediaFacadeImpl): Unit = {
    val actMediaManager = mock[ActorRef]
    val actMetaManager = mock[ActorRef]
    facade.responseMediaManager = Future.successful(Some(actMediaManager))
    facade.responseMetaDataManager = Future.successful(Some(actMetaManager))
    implicit val timeout = facade.expTimeout

    val futActors = facade.requestFacadeActors()
    val actors = Await.result(futActors, facade.expTimeout.duration)
    actors.mediaManager should be(actMediaManager)
    actors.metaDataManager should be(actMetaManager)
  }

  it should "allow querying an object with interface actors" in {
    checkSuccessfulFacadeActorsRequest(new MediaFacadeImpl)
  }

  it should "allow querying interface actors with a different timeout" in {
    checkSuccessfulFacadeActorsRequest(new MediaFacadeImpl(Timeout(1.second)))
  }

  it should "take the timeout into account when querying interface actors" in {

  }

  it should "fail to return interface actors if an actor cannot be obtained" in {
    val facade = new MediaFacadeImpl
    val actMediaManager = mock[ActorRef]
    facade.responseMediaManager = Future.successful(Some(actMediaManager))
    facade.responseMetaDataManager = Future.failed(new Exception("test exception"))
    implicit val timeout = facade.expTimeout

    val futActors = facade.requestFacadeActors()
    expectFailedFuture(facade, futActors)
  }

  it should "fail to return interface actors if one actor is undefined" in {
    val facade = new MediaFacadeImpl
    val actMetaManager = mock[ActorRef]
    facade.responseMetaDataManager = Future.successful(Some(actMetaManager))
    facade.responseMediaManager = Future.successful(None)
    implicit val timeout = facade.expTimeout

    val futActors = facade.requestFacadeActors()
    expectFailedFuture(facade, futActors)
  }
}

/**
  * Test implementation of the trait.
  *
  * @param expTimeout the timeout for actor requests
  */
class MediaFacadeImpl(val expTimeout: Timeout = Timeout(5.seconds)) extends MediaFacade {
  override val bus: MessageBus = null

  /** Records messages passed to the send() method (in reverse order). */
  var sentMessages = List.empty[(MediaActor, Any)]

  /** Response to be returned for the media manager by requestActor(). */
  var responseMediaManager: Future[Option[ActorRef]] = _

  /** Response to be returned for the meta data manager by requestActor(). */
  var responseMetaDataManager: Future[Option[ActorRef]] = _

  override def activate(enabled: Boolean): Unit = ???

  override def send(target: MediaActor, msg: Any): Unit = {
    sentMessages = (target, msg) :: sentMessages
  }

  override def initConfiguration(config: Configuration): Unit = ???

  override def requestMediaState(): Unit = ???

  /**
    * @inheritdoc Returns the response defined for the specified actor.
    */
  override def requestActor(target: MediaActor)(implicit timeout: Timeout):
  Future[Option[ActorRef]] = {
    if (timeout != expTimeout) {
      throw new AssertionError("Unexpected timeout: " + timeout)
    }
    target match {
      case MediaActors.MediaManager => responseMediaManager
      case MediaActors.MetaDataManager => responseMetaDataManager
    }
  }

  override def removeMetaDataListener(mediumID: MediumID): Unit = ???

  override def registerMetaDataStateListener(componentID: ComponentID): Unit = ???

  override def unregisterMetaDataStateListener(componentID: ComponentID): Unit = ???
}

/**
  * A test thread class for generating listener registration IDs in parallel.
  */
private class RegisterMediumListenerThread(facade: MediaFacade, latch: CountDownLatch)
  extends Thread {
  /** A set with the IDs that have been obtained from the facade. */
  var ids = Set.empty[Int]

  override def run(): Unit = {
    if (latch.await(10, TimeUnit.SECONDS)) {
      ids = (1 to 32).map(
        i => facade queryMetaDataAndRegisterListener MediumID("test" + i, None)).toSet
    }
  }
}
