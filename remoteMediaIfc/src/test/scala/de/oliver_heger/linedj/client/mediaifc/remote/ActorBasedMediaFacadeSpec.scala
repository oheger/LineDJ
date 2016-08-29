/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.client.mediaifc.remote

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.AskTimeoutException
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.client.mediaifc.MediaActors
import de.oliver_heger.linedj.media.MediumID
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

object ActorBasedMediaFacadeSpec {
  /** Constant for test message. */
  private val Message = new Object

  /** Constant for the test message wrapped in a remote message. */
  private val RemoteMessage = RelayActor.MediaMessage(MediaActors.MediaManager, Message)
}

/**
 * Test class for ''ActorBasedMediaFacade''.
 */
class ActorBasedMediaFacadeSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ActorBasedMediaFacadeSpec._

  def this() = this(ActorSystem("ActorBasedMediaFacadeSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates the test object, initialized with the test actor serving as relay
    * actor.
    *
    * @param optRelayActor an optional relay actor for the facade
    * @return the test message bus
    */
  private def createFacade(optRelayActor: Option[ActorRef] = None): ActorBasedMediaFacade = {
    val bus = mock[MessageBus]
    new ActorBasedMediaFacade(optRelayActor getOrElse testActor, system, bus)
  }

  "An ActorBasedMediaFacade" should "send messages to the rely actor" in {
    val facade = createFacade()

    facade.send(MediaActors.MediaManager, Message)
    expectMsg(RemoteMessage)
    verifyZeroInteractions(facade.bus)
  }

  it should "simplify sending an activation message to the associated actor" in {
    val facade = createFacade()

    facade activate true
    expectMsg(RelayActor.Activate(true))
  }

  it should "support setting the initial configuration" in {
    val address = "remote.host"
    val port = 1234
    val config = new PropertiesConfiguration
    config.addProperty("media.host", address)
    config.addProperty("media.port", port)
    val facade = createFacade()

    facade initConfiguration config
    expectMsg(ManagementActor.RemoteConfiguration(address, port))
  }

  it should "read default values from the configuration" in {
    val facade = createFacade()

    facade initConfiguration new PropertiesConfiguration
    expectMsg(ManagementActor.RemoteConfiguration("127.0.0.1", 2552))
  }

  it should "allow querying the current server state" in {
    val facade = createFacade()
    facade.requestMediaState()

    expectMsg(RelayActor.QueryServerState)
  }

  it should "process a request for an actor" in {
    val remoteActor = TestProbe()
    val relay = system.actorOf(Props(classOf[DummyRelayActor], remoteActor.ref))
    val facade = createFacade(Some(relay))
    implicit val timeout = Timeout(3.seconds)

    val future = facade.requestActor(MediaActors.MediaManager)
    Await.result(future, 3.seconds) should be(Some(remoteActor.ref))
  }

  it should "take the timeout for an actor request into account" in {
    val relayActor = system.actorOf(Props(classOf[DummyRelayActor], TestProbe().ref))
    val facade = createFacade(Some(relayActor))
    implicit val timeout = Timeout(100.millis)

    intercept[AskTimeoutException] {
      val future = facade.requestActor(MediaActors.MetaDataManager)
      Await.result(future, 3.seconds)
    }
  }

  it should "support removing a meta data listener" in {
    val mediumId = MediumID("someURI", None)
    val facade = createFacade()

    facade removeMetaDataListener mediumId
    expectMsg(RelayActor.RemoveListener(mediumId))
  }
}

/**
  * An actor class used for testing a request to a remote actor.
  *
  * @param remoteActorRef the actor reference to be returned
  */
class DummyRelayActor(remoteActorRef: ActorRef) extends Actor {
  override def receive: Receive = {
    case RelayActor.MediaActorRequest(MediaActors.MediaManager) =>
      sender() ! RelayActor.MediaActorResponse(MediaActors.MediaManager, Some(remoteActorRef))
  }
}
