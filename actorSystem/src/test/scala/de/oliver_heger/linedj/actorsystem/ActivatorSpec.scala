/*
 * Copyright 2015-2025 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.actorsystem

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.osgi.OsgiActorSystemFactory
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyLong, eq as argEq}
import org.mockito.Mockito.*
import org.osgi.framework.{Bundle, BundleContext, BundleEvent, BundleListener, ServiceRegistration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

/**
  * Test class for ''Activator''.
  */
class ActivatorSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  /**
    * Creates a mock for a [[Bundle]] with the given properties.
    *
    * @param name  the symbolic bundle name
    * @param state the state of the bundle
    * @return the mock bundle
    */
  private def createBundle(name: String, state: Int = Bundle.ACTIVE): Bundle =
    val bundle = mock[Bundle]
    when(bundle.getSymbolicName).thenReturn(name)
    when(bundle.getState).thenReturn(state)
    bundle

  "An Activator" should "create a correct executor service" in :
    val activator = new Activator

    val service = activator.createExecutor()

    // Can only test that a valid object is returned.
    service.close()

  it should "create a correct actor system factory" in :
    val bundle = mock[Bundle]
    val context = mock[BundleContext]
    when(context.getBundle).thenReturn(bundle)

    val activator = new Activator
    val factory = activator.createActorSystemFactory(context)

    factory.context should be(context)

  it should "create the actor system if the weaving bundle is active after a delay" in :
    val spiFlyBundle = createBundle("org.apache.aries.spifly.dynamic.bundle")
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array(spiFlyBundle))
      .start()
      .expectSchedule(Activator.DefaultSpiFlyDelay)
      .verifyActorSystemRegistration()
      .verifyExecutorClosed()

    verify(helper.bundleContext, never()).addBundleListener(any())

  it should "obtain the name of the actor system from system properties" in :
    val ActorSystemName = "myTestActorSystem"
    val spiFlyBundle = createBundle("org.apache.aries.spifly.dynamic.bundle")
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array(spiFlyBundle))
      .setSystemProperty(Activator.PropActorSystemName, ActorSystemName)
      .start()
      .expectSchedule(Activator.DefaultSpiFlyDelay)
      .verifyActorSystemRegistration(ActorSystemName)
      .verifyExecutorClosed()

  it should "obtain the SpiFly delay from system properties" in :
    val SpiFlyDelay = 1234L
    val spiFlyBundle = createBundle("org.apache.aries.spifly.dynamic.bundle")
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array(spiFlyBundle))
      .setSystemProperty(Activator.PropSpiFlyDelayMs, SpiFlyDelay.toString)
      .start()
      .expectSchedule(SpiFlyDelay)
      .verifyActorSystemRegistration()
      .verifyExecutorClosed()

  it should "unregister the actor system when the bundle is stopped" in :
    val spiFlyBundle = createBundle("org.apache.aries.spifly.dynamic.bundle")
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array(spiFlyBundle))
      .start()
      .expectSchedule(Activator.DefaultSpiFlyDelay)
      .stop()
      .verifyServiceUnregistered()

  it should "handle a stop call before the registration of the actor system" in :
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array.empty)
      .start()
      .stop()
      .verifyExecutorClosed()
    verify(helper.bundleContext).removeBundleListener(any())

  it should "wait for the SpiFly bundle to start up" in :
    val spiFlyBundle = createBundle("org.apache.aries.spifly.dynamic.bundle")
    val someBundle = createBundle("some-bundle")
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array(someBundle))
      .start()
      .expectNoSchedule()
      .sendBundleEvent(new BundleEvent(BundleEvent.STARTED, spiFlyBundle))
      .expectSchedule(Activator.DefaultSpiFlyDelay)
      .verifyActorSystemRegistration()
      .verifyExecutorClosed()

    verify(helper.bundleContext).removeBundleListener(any())

  it should "correctly evaluate the state of the SpiFly bundle" in :
    val spiFlyBundle = createBundle("org.apache.aries.spifly.dynamic.bundle", Bundle.STARTING)
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array(spiFlyBundle))
      .start()
      .sendBundleEvent(new BundleEvent(BundleEvent.INSTALLED, createBundle("some-bundle")))

  it should "correctly evaluate the bundle event type" in :
    val event = new BundleEvent(BundleEvent.STARTING, createBundle("org.apache.aries.spifly.dynamic.bundle"))
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array.empty)
      .start()
      .sendBundleEvent(event)
      .expectNoSchedule()

  it should "handle a stop() call before the delay is over" in :
    val spiFlyBundle = createBundle("org.apache.aries.spifly.dynamic.bundle")
    val helper = new ActivatorTestHelper

    helper.initExistingBundles(Array.empty)
      .start()
      .stop()
      .sendBundleEvent(new BundleEvent(BundleEvent.STARTED, spiFlyBundle))
      .expectNoSchedule()

  /**
    * A test helper class that manages a test instance and its mock
    * dependencies.
    */
  private class ActivatorTestHelper:
    /** Mock for the bundle context. */
    val bundleContext: BundleContext = mock[BundleContext]

    /** The actor system created by the activator. */
    private val actorSystem = mock[ActorSystem]

    /** The mock executor service. */
    private val executorService = mock[ScheduledExecutorService]

    /** The mock for the actor system factory. */
    private val actorSystemFactory = createFactoryMock()

    /** The service registration for the actor system. */
    private val serviceRegistration = createServiceRegistration()

    /** A map to simulate system properties. */
    private val systemProperties = collection.mutable.Map.empty[String, String]

    /** The instance to be tested. */
    private val testActivator = createActivator()

    /**
      * Adds a simulated system property.
      *
      * @param key   the property key
      * @param value the property value
      * @return this test helper
      */
    def setSystemProperty(key: String, value: String): ActivatorTestHelper =
      systemProperties += key -> value
      this

    /**
      * Prepares the mock bundle context to return the given existing bundles.
      *
      * @param bundles the bundles existing in the system
      * @return this test helper
      */
    def initExistingBundles(bundles: Array[Bundle]): ActivatorTestHelper =
      when(bundleContext.getBundles).thenReturn(bundles)
      this

    /**
      * Invokes the ''start'' method on the test activator.
      *
      * @return this test helper
      */
    def start(): ActivatorTestHelper =
      testActivator.start(bundleContext)
      this

    /**
      * Invokes the ''stop'' method on the test activator.
      *
      * @return this test helper
      */
    def stop(): ActivatorTestHelper =
      testActivator.stop(bundleContext)
      this

    /**
      * Verifies that an actor system was correctly created and registered as
      * an OSGi service.
      *
      * @param name the expected name of the actor system
      * @return this test helper
      */
    def verifyActorSystemRegistration(name: String = Activator.DefaultActorSystemName): ActivatorTestHelper =
      verify(actorSystemFactory).createActorSystem(name)
      verify(bundleContext).registerService(classOf[ActorSystem], actorSystem, null)
      this

    /**
      * Expects that a task was scheduled on the executor service with the
      * given delay. Simulates the execution of this task.
      *
      * @param delayMs the expected delay in milliseconds
      * @return this test helper
      */
    def expectSchedule(delayMs: Long): ActivatorTestHelper =
      val captor = ArgumentCaptor.forClass(classOf[Runnable])
      verify(executorService).schedule(captor.capture(), argEq(delayMs), argEq(TimeUnit.MILLISECONDS))
      captor.getValue.run()
      this

    /**
      * Checks that no task has been scheduled on the executor service yet.
      *
      * @return this test helper
      */
    def expectNoSchedule(): ActivatorTestHelper =
      verify(executorService, never()).schedule(any[Runnable], anyLong(), any())
      this

    /**
      * Sends the given event to the registered bundle listener.
      *
      * @param event the event to send
      * @return this test helper
      */
    def sendBundleEvent(event: BundleEvent): ActivatorTestHelper =
      val captor = ArgumentCaptor.forClass(classOf[BundleListener])
      verify(bundleContext).addBundleListener(captor.capture())
      captor.getValue.bundleChanged(event)
      this

    /**
      * Verifies that the executor service has been correctly closed.
      *
      * @return this test helper
      */
    def verifyExecutorClosed(): ActivatorTestHelper =
      verify(executorService, times(1)).shutdownNow()
      this

    /**
      * Verifies that the service for the actor system has been unregistered.
      *
      * @return this test helper
      */
    def verifyServiceUnregistered(): ActivatorTestHelper =
      verify(serviceRegistration, times(1)).unregister()
      this

    /**
      * Creates a mock for the actor system factory.
      *
      * @return the mock actor system factory
      */
    private def createFactoryMock(): OsgiActorSystemFactory =
      val factory = mock[OsgiActorSystemFactory]
      when(factory.createActorSystem(any[String]())).thenReturn(actorSystem)
      factory

    /**
      * Creates a mock for the service registration that can be used to verify
      * that the service is correctly unregistered when the activator is
      * stopped.
      *
      * @return the mock registration
      */
    private def createServiceRegistration(): ServiceRegistration[ActorSystem] =
      val registration = mock[ServiceRegistration[ActorSystem]]
      when(bundleContext.registerService(classOf[ActorSystem], actorSystem, null)).thenReturn(registration)
      registration

    /**
      * Creates the instance to be tested.
      *
      * @return the test [[Activator]] instance.
      */
    private def createActivator(): Activator =
      new Activator:
        override private[actorsystem] def createActorSystemFactory(context: BundleContext): OsgiActorSystemFactory =
          context should be(bundleContext)
          actorSystemFactory

        override private[actorsystem] def createExecutor(): ScheduledExecutorService =
          executorService

        override def getSystemProperty(key: String): Option[String] =
          systemProperties.get(key)
