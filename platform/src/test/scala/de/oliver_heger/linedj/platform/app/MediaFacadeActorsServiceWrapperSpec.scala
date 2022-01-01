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

package de.oliver_heger.linedj.platform.app

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.ArgumentMatchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.osgi.framework.{BundleContext, ServiceRegistration}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}

/**
  * Test class for ''MediaFacadeActorsServiceWrapper''.
  */
class MediaFacadeActorsServiceWrapperSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("MediaFacadeActorsServiceWrapper"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MediaFacadeActorsServiceWrapper" should "register itself for archive availability" in {
    val helper = new ServiceWrapperTestHelper

    helper.activate().verifyArcListenerRegistration()
  }

  it should "remove all listener registrations when it is deactivated" in {
    val helper = new ServiceWrapperTestHelper

    helper.activate().deactivate()
      .verifyArcListenerDeRegistration()
      .verifyMessageBusDeRegistration()
  }

  it should "register the facade actors when the archive is available" in {
    val helper = new ServiceWrapperTestHelper

    helper.activate()
      .prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .verifyNoServiceRegistration()
      .processUIFuture()
      .verifyServiceRegistration()
  }

  it should "support a timeout in the configuration" in {
    val timeOutSecs = 5
    val helper = new ServiceWrapperTestHelper

    helper.activate()
      .initActorsRetrievalTimeout(timeOutSecs)
      .prepareArchiveActorsRequest(timeout = Timeout(timeOutSecs.seconds))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyServiceRegistration()
  }

  it should "remove the service registration on deactivation" in {
    val helper = new ServiceWrapperTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyServiceRegistration()

    helper.deactivate().verifyRegistrationRemoved()
  }

  it should "not register the service again on a 2nd archive available message" in {
    val helper = new ServiceWrapperTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()

    helper.sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectNoMessageOnBus()
  }

  it should "remove the service when the union archive becomes unavailable" in {
    val helper = new ServiceWrapperTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyServiceRegistration()

    helper.sendAvailability(MediaFacade.MediaArchiveUnavailable)
      .verifyRegistrationRemoved()
  }

  it should "register the service anew when the union archive is available again" in {
    val helper = new ServiceWrapperTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .sendAvailability(MediaFacade.MediaArchiveUnavailable)

    helper.sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyServiceRegistration(count = 2)
  }

  it should "not register the service if fetching of facade actors fails" in {
    val helper = new ServiceWrapperTestHelper
    helper.activate()
      .prepareArchiveActorsRequest(actors = Promise.failed(new Exception("BOOM")))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)

    helper.expectNoMessageOnBus()
  }

  it should "not register the archive if the facade becomes unavailable while fetching actors" in {
    val helper = new ServiceWrapperTestHelper
    helper.activate()
      .prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .sendAvailability(MediaFacade.MediaArchiveUnavailable)
      .processUIFuture()

    helper.verifyNoServiceRegistration()
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ServiceWrapperTestHelper {
    /** The test message bus. */
    private val messageBus = new MessageBusTestImpl

    /** The client application context. */
    private val clientContext = createClientContext()

    /** Mock for the OSGi service registration for the facade actors. */
    private val serviceRegistration = mock[ServiceRegistration[MediaFacadeActors]]

    /** Mock for the facade actors object. */
    private val facadeActors = mock[MediaFacadeActors]

    /** The bundle context mock. */
    private val bundleContext = createBundleContext()

    /** The registration for the archive availability extension. */
    private var arcRegistration: ArchiveAvailabilityRegistration = _

    /** The service wrapper to be tested. */
    private val serviceWrapper = new MediaFacadeActorsServiceWrapper(clientContext, bundleContext)

    /**
      * Activates the test instance.
      *
      * @return this test helper
      */
    def activate(): ServiceWrapperTestHelper = {
      serviceWrapper.activate()
      arcRegistration = fetchRegistration()
      this
    }

    /**
      * Deactivates the test instance.
      *
      * @return this test helper
      */
    def deactivate(): ServiceWrapperTestHelper = {
      serviceWrapper.deactivate()
      this
    }

    /**
      * Verifies that the test instance has published a valid registration as
      * archive availability listener.
      *
      * @return this test helper
      */
    def verifyArcListenerRegistration(): ServiceWrapperTestHelper = {
      arcRegistration.id should be(serviceWrapper.componentID)
      this
    }

    /**
      * Checks that a de-registration message for the archive availability
      * extension was sent on the message bus.
      *
      * @return this test helper
      */
    def verifyArcListenerDeRegistration(): ServiceWrapperTestHelper = {
      val unReg = messageBus.expectMessageType[ArchiveAvailabilityUnregistration]
      unReg.id should be(serviceWrapper.componentID)
      this
    }

    /**
      * Checks that the message bus listener registration has been removed.
      *
      * @return this test helper
      */
    def verifyMessageBusDeRegistration(): ServiceWrapperTestHelper = {
      messageBus.currentListeners should have size 0
      this
    }

    /**
      * Prepares a request for the actors of the union media archive. The mock
      * is prepared to return a ''Future'' object for the actors based on the
      * provided promise.
      *
      * @param actors  promise for the archive actors
      * @param timeout a timeout for the request
      * @return this test helper
      */
    def prepareArchiveActorsRequest(actors: Promise[MediaFacadeActors] =
                                    Promise.successful(facadeActors),
                                    timeout: Timeout = Timeout(10.seconds))
    : ServiceWrapperTestHelper = {
      when(clientContext.mediaFacade.requestFacadeActors()(eqArg(timeout),
        any(classOf[ExecutionContext])))
        .thenReturn(actors.future)
      this
    }

    /**
      * Expects a message to be sent on the UI bus for completing a future in
      * the UI thread. This message is delivered.
      *
      * @return this test helper
      */
    def processUIFuture(): ServiceWrapperTestHelper = {
      messageBus.processNextMessage[Any]()
      this
    }

    /**
      * Checks that no message has been published on the message bus.
      *
      * @return this test helper
      */
    def expectNoMessageOnBus(): ServiceWrapperTestHelper = {
      messageBus.expectNoMessage(300.millis)
      this
    }

    /**
      * Sends a message to the consumer function registered by the test
      * instance.
      *
      * @param event the event to be sent
      * @return this test helper
      */
    def sendAvailability(event: MediaFacade.MediaArchiveAvailabilityEvent):
    ServiceWrapperTestHelper = {
      arcRegistration.callback(event)
      this
    }

    /**
      * Verifies that a service registration for the facade actors has been
      * created.
      *
      * @param count the expected number of registrations
      * @return this test helper
      */
    def verifyServiceRegistration(count: Int = 1): ServiceWrapperTestHelper = {
      verify(bundleContext, times(count))
        .registerService(classOf[MediaFacadeActors], facadeActors, null)
      this
    }

    /**
      * Verifies that the service registration has been removed again.
      *
      * @return this test helper
      */
    def verifyRegistrationRemoved(): ServiceWrapperTestHelper = {
      verify(serviceRegistration).unregister()
      this
    }

    /**
      * Verifies that no service registration was created.
      *
      * @return this test helper
      */
    def verifyNoServiceRegistration(): ServiceWrapperTestHelper = {
      verify(bundleContext, never()).registerService(eqArg(classOf[MediaFacadeActors]),
        any(), any())
      this
    }

    /**
      * Initializes the timeout property for retrieving the facade actors in
      * the configuration.
      *
      * @param timeOutSecs the timeout in seconds
      * @return this test helper
      */
    def initActorsRetrievalTimeout(timeOutSecs: Int): ServiceWrapperTestHelper = {
      clientContext.managementConfiguration.addProperty("media.initTimeout", timeOutSecs)
      this
    }

    /**
      * Obtains the registration for the archive availability extension on the
      * message bus.
      *
      * @return the registration object
      */
    private def fetchRegistration(): ArchiveAvailabilityRegistration =
      messageBus.expectMessageType[ArchiveAvailabilityRegistration]

    /**
      * Creates a mock for the bundle context. The mock is already prepared
      * to return a service registration.
      *
      * @return the mock for the bundle context
      */
    private def createBundleContext(): BundleContext = {
      val ctx = mock[BundleContext]
      when(ctx.registerService(classOf[MediaFacadeActors], facadeActors, null))
        .thenReturn(serviceRegistration)
      ctx
    }

    /**
      * Creates a mock ''ClientApplicationContext''.
      *
      * @return the client context
      */
    private def createClientContext(): ClientApplicationContext = {
      val clientContext = mock[ClientApplicationContext]
      when(clientContext.managementConfiguration).thenReturn(new PropertiesConfiguration)
      when(clientContext.actorSystem).thenReturn(system)
      when(clientContext.messageBus).thenReturn(messageBus)
      when(clientContext.mediaFacade).thenReturn(mock[MediaFacade])
      clientContext
    }
  }

}
