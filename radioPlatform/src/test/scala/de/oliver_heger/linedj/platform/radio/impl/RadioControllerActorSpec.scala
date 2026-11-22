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

import com.github.cloudfiles.core.http.factory.{HttpRequestSenderFactory, HttpRequestSenderFactoryImpl}
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, UnregisterService}
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.{verify, when, timeout as mockTimeout}
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout as PatienceTimeout}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Promise
import scala.concurrent.duration.*

object RadioControllerActorSpec:
  /** The parameters for the discovery operation used by the tests. */
  private val TestDiscoveryParams = ServerDiscovery.DiscoveryParams(
    multicastAddress = "239.255.255.250",
    port = 1900,
    requestCode = "radioPlayer"
  )

  /** The name expected for the discovery operation. */
  private val DiscoveryName = "radioPlayerControllerDiscovery"

  /** The URI of the player server reported by discovery. */
  private val ServerUri = "https://radio.example.com:8080"

  /** The timeout for waiting on asynchronous conditions. */
  private val WaitTimeout = PatienceTimeout(Span(3, Seconds))

  /** The interval for polling asynchronous conditions. */
  private val WaitInterval = Interval(Span(10, Millis))

  /** The timeout in milliseconds for Mockito verifications. */
  private val VerifyTimeout = 3000L
end RadioControllerActorSpec

/**
  * Test class for [[RadioControllerActor]].
  */
class RadioControllerActorSpec extends ScalaTestWithActorTestKit, AnyFlatSpecLike, Matchers, MockitoSugar, Eventually:

  import RadioControllerActorSpec.*

  "A RadioControllerActor" should "create the service and player actor after a successful discovery" in :
    val helper = new ActorTestHelper

    helper.completeDiscovery()

    helper.expectServiceCreated()
      .expectPlayerCreated()

  it should "register the radio controller service after a successful discovery" in :
    val helper = new ActorTestHelper

    helper.completeDiscovery()
      .verifyRadioServiceDependencyRegistration()

  it should "not register the radio controller service before the discovery completes" in :
    val helper = new ActorTestHelper

    helper.verifyNoRadioServiceDependencyRegistration()

  it should "release its resources when it is stopped" in :
    val helper = new ActorTestHelper

    helper.completeDiscovery()
      .expectPlayerCreated()
    helper.stopController()
      .verifyRadioServiceDependencyUnregistration()
      .verifyPlayerStopped()
      .verifyServiceClosed()
      .verifyDiscoveryClosed()

  it should "close the discovery handle when stopped before discovery completes" in :
    val helper = new ActorTestHelper

    helper.stopController()
      .verifyDiscoveryClosed()
      .verifyNoRadioServiceDependencyRegistration()

  /**
    * A test helper class managing the actor under test and its dependencies.
    */
  private class ActorTestHelper:
    /** The message bus passed to the actor under test. */
    private val messageBus = new MessageBusTestImpl()

    /** The parameters passed to the discovery factory. */
    private val discoveryParams = TestDiscoveryParams

    /** A promise for the result of the discovery operation. */
    private val discoveryPromise = Promise[String]()

    /** A mock for the handle returned by the discovery factory. */
    private val discoveryHandle = createDiscoveryHandle()

    /** A flag whether the service factory has been invoked. */
    private val serviceCreated = new AtomicBoolean

    /** A mock for the player service returned by the service factory. */
    private val playerService = mock[PlayerServiceImpl]

    /** A flag whether the player actor factory has been invoked. */
    private val playerCreated = new AtomicBoolean

    /** A probe representing the player actor created by the actor under test. */
    private val playerProbe = testKit.createTestProbe[RadioPlayerActor.Command]()

    /** The classic actor system used by the test. */
    private val classicSystem: classic.ActorSystem = testKit.system.classicSystem

    /** The actor factory used to create the controller actor. */
    private val controllerActorFactory = createActorFactory()

    /** The timeout for service interactions. */
    private val controllerTimeout: Timeout = Timeout(3.seconds)

    given ActorFactory = controllerActorFactory

    given Timeout = controllerTimeout

    /** A stub factory for a discovery operation. */
    private val discoveryFactory = createDiscoveryFactory()

    /** A stub factory to create the player service. */
    private val serviceFactory = createServiceFactory()

    /** A stub factory to create the player actor. */
    private val playerFactory = createPlayerFactory()

    /** The actor under test. */
    private val controllerActor = createController()

    /**
      * Completes the discovery operation with the given server URI.
      *
      * @param uri the URI to report
      * @return this test helper
      */
    def completeDiscovery(uri: String = ServerUri): ActorTestHelper =
      discoveryPromise.success(uri)
      this

    /**
      * Waits until the player service has been created.
      *
      * @return this test helper
      */
    def expectServiceCreated(): ActorTestHelper =
      eventually(WaitTimeout, WaitInterval):
        serviceCreated.get() shouldBe true
      this

    /**
      * Waits until the player actor has been created.
      *
      * @return this test helper
      */
    def expectPlayerCreated(): ActorTestHelper =
      eventually(WaitTimeout, WaitInterval):
        playerCreated.get() shouldBe true
      this

    /**
      * Sends a Stop command to the actor under test.
      *
      * @return this test helper
      */
    def stopController(): ActorTestHelper =
      controllerActor ! RadioControllerActor.Command.Stop
      this

    /**
      * Verifies that the player actor received a Stop command.
      *
      * @return this test helper
      */
    def verifyPlayerStopped(): ActorTestHelper =
      playerProbe.expectMessage(RadioPlayerActor.Command.Stop)
      this

    /**
      * Verifies that the player service has been closed.
      *
      * @return this test helper
      */
    def verifyServiceClosed(): ActorTestHelper =
      verify(playerService, mockTimeout(VerifyTimeout)).close()
      this

    /**
      * Verifies that the discovery handle has been closed.
      *
      * @return this test helper
      */
    def verifyDiscoveryClosed(): ActorTestHelper =
      verify(discoveryHandle, mockTimeout(VerifyTimeout)).close()
      this

    /**
      * Verifies that the service dependency for the radio controller has been
      * published on the message bus.
      *
      * @return this test helper
      */
    def verifyRadioServiceDependencyRegistration(): ActorTestHelper =
      val registration = messageBus.expectMessageType[RegisterService]
      registration.service should be(RadioControllerActor.RadioPlayerControllerDependency)
      this

    /**
      * Verifies that the service dependency for the radio controller has been
      * unregistered again.
      *
      * @return this test helper
      */
    def verifyRadioServiceDependencyUnregistration(): ActorTestHelper =
      val unRegistration = messageBus.findMessageType[UnregisterService]
      unRegistration.service should be(RadioControllerActor.RadioPlayerControllerDependency)
      this

    /**
      * Verifies that no service dependency registration message was published
      * on the message bus.
      *
      * @return this test helper
      */
    def verifyNoRadioServiceDependencyRegistration(): ActorTestHelper =
      messageBus.expectNoMessage(200.millis)
      this

    /**
      * Creates an [[ActorFactory]] that can be used to create the actor under
      * test. It uses the test kit to spawn the new actor instance.
      *
      * @return the actor factory
      */
    private def createActorFactory(): ActorFactory =
      val baseFactory = ActorFactory.defaultActorFactory(using classicSystem)
      new ActorFactory:
        export baseFactory.{createTypedActor => _, *}

        override def createTypedActor[T](behavior: Behavior[T],
                                         name: String,
                                         props: Props,
                                         optStopCommand: Option[T]): ActorRef[T] =
          testKit.spawn(behavior)

    /**
      * Creates a mock for the discovery handle to be returned by the stub
      * discovery factory. The discovery future of the handle can be controlled
      * by methods of this test helper class.
      *
      * @return the mock for the discovery handle
      */
    private def createDiscoveryHandle(): ServerDiscovery.DiscoveryHandle =
      val handle = mock[ServerDiscovery.DiscoveryHandle]
      when(handle.futResult).thenReturn(discoveryPromise.future)
      handle

    /**
      * Creates a stub for the discovery factory. The stub checks the parameters
      * and returns a mock for the discovery handle.
      *
      * @return the discovery factory
      */
    private def createDiscoveryFactory(): ServerDiscovery.Factory =
      new ServerDiscovery.Factory:
        override def apply(params: ServerDiscovery.DiscoveryParams,
                           discoveryName: String)
                          (using actorFactory: ActorFactory): ServerDiscovery.DiscoveryHandle =
          params should be(discoveryParams)
          discoveryName should be(DiscoveryName)
          actorFactory should be(controllerActorFactory)
          discoveryHandle

    /**
      * Creates a stub for the player service factory. The stub checks the
      * server URI and returns a mock for the service.
      *
      * @return the service factory
      */
    private def createServiceFactory(): PlayerServiceImpl.Factory =
      new PlayerServiceImpl.Factory:
        override def apply(uri: String,
                           senderFactory: HttpRequestSenderFactory)
                          (using system: classic.ActorSystem, timeout: Timeout): PlayerServiceImpl =
          uri should be(ServerUri)
          senderFactory should be(HttpRequestSenderFactoryImpl)
          system should be(classicSystem)
          timeout should be(controllerTimeout)
          serviceCreated.set(true)
          playerService

    /**
      * Creates a stub for the player actor factory. The stub checks the
      * parameters and returns a behavior backed by a test probe.
      *
      * @return the player actor factory
      */
    private def createPlayerFactory(): RadioPlayerActor.Factory =
      (serverUri: String,
       playerService: PlayerService,
       messageBus: MessageBus,
       messageActorFactory: RadioMessageActor.Factory) =>
        serverUri should be(ServerUri)
        playerService should be(ActorTestHelper.this.playerService)
        messageBus should be(ActorTestHelper.this.messageBus)
        messageActorFactory should be(RadioMessageActor.newInstance)
        playerCreated.set(true)
        Behaviors.monitor(playerProbe.ref, Behaviors.ignore[RadioPlayerActor.Command])

    /**
      * Creates the actor under test.
      *
      * @return the controller actor
      */
    private def createController(): ActorRef[RadioControllerActor.Command] =
      RadioControllerActor.newInstance(discoveryParams, messageBus, discoveryFactory, serviceFactory, playerFactory)
