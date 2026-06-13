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

package de.oliver_heger.linedj.platform.archiveclient

import com.github.cloudfiles.core.http.factory.HttpRequestSenderFactoryImpl
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import org.apache.commons.configuration2.{BaseHierarchicalConfiguration, ImmutableHierarchicalConfiguration}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.osgi.framework.{BundleContext, ServiceRegistration}
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

object ArchiveClientComponentSpec:
  /** The URI returned for the archive by the test discovery. */
  private val ArchiveUri = "https://archive.example.com/test"

  /**
    * The timeout for the test archive as configured in the test platform 
    * config.
    */
  private val ArchiveTimeout = Timeout(38.seconds)
end ArchiveClientComponentSpec

/**
  * Test class for [[ArchiveClientComponent]].
  */
class ArchiveClientComponentSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar:
  def this() = this(ActorSystem("ArchiveClientComponentSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ArchiveClientComponentSpec.*

  "ArchiveClientComponent" should "handle a deactivation without activation" in :
    val context = mock[ComponentContext]
    val component = new ArchiveClientComponent()

    component.deactivate(context)

    verifyNoInteractions(context)

  it should "close the discovery handle on deactivation if discovery is still in progress" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivate()
      .verifyDiscoveryHandleClosed()

  it should "start discovery with the parameters from the configuration" in :
    val helper = new ComponentTestHelper

    val params = helper.activate()
      .verifyDiscoveryFactory()

    params.multicastAddress should be("231.1.2.3")
    params.port should be(10101)
    params.requestCode should be("Hello!")
    params.timeout should be(58.seconds)
    params.minBackoff should be(19.seconds)
    params.maxBackoff should be(5.minutes)

  it should "start discovery with default parameters for undefined optional settings" in :
    val config = ArchiveClientConfigTestHelper.testConfig: c =>
      c.clearProperty("platform.mediaArchive.discovery.timeout")
      c.clearProperty("platform.mediaArchive.discovery.minBackoff")
      c.clearProperty("platform.mediaArchive.discovery.maxBackoff")
    val helper = new ComponentTestHelper

    val params = helper.initConfiguration(config)
      .activate()
      .verifyDiscoveryFactory()
    helper.deactivate()

    params.multicastAddress should be("231.1.2.3")
    params.port should be(10101)
    params.requestCode should be("Hello!")
    params.timeout should be(ServerDiscovery.DefaultTimeout)
    params.minBackoff should be(ServerDiscovery.DefaultMinBackoff)
    params.maxBackoff should be(ServerDiscovery.DefaultMaxBackoff)

  it should "not start discovery if there is no discovery section in the configuration" in :
    val config = new BaseHierarchicalConfiguration
    val helper = new ComponentTestHelper

    helper.initConfiguration(config)
      .activate()
      .verifyDiscoverySkipped()
      .deactivate()

  it should "not start discovery if mandatory parameters are missing" in :
    val properties = List("multicastAddress", "port", "requestCode")

    forEvery(properties): property =>
      val config = ArchiveClientConfigTestHelper.testConfig: c =>
        c.clearProperty(s"platform.mediaArchive.discovery.$property")
      val helper = new ComponentTestHelper

      helper.initConfiguration(config)
        .activate()
        .verifyDiscoverySkipped()
        .deactivate()

  it should "register the archive service when the discovery is successful" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .succeedDiscovery()
      .verifyArchiveServiceRegistration()

  it should "set a default timeout for the archive service if not specified in the client config" in :
    val config = ArchiveClientConfigTestHelper.testConfig: c =>
      c.clearProperty("platform.mediaArchive.requestTimeout")
    val helper = new ComponentTestHelper

    helper.initConfiguration(config)
      .initArchiveFactory(ArchiveClientConfig.DefaultArchiveTimeout)
      .activate()
      .succeedDiscovery()
      .verifyArchiveServiceRegistration()

  it should "unregister a registered archive service on deactivation" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .succeedDiscovery()
      .verifyArchiveServiceRegistration()
      .deactivate()
      .verifyArchiveServiceUnregistration()

  it should "close the archive service on deactivation" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .succeedDiscovery()
      .verifyArchiveServiceRegistration()
      .deactivate()
      .verifyArchiveServiceClosed()
  
  /**
    * A test helper class that manages a test component instance and its 
    * dependencies.
    */
  private class ComponentTestHelper:
    /**
      * The promise to be returned by the mock discovery handle. This is used 
      * to set the result of the discovery operation.
      */
    private val discoveryPromise = Promise[String]()

    /** The mock discovery handle. */
    private val discoveryHandle = createDiscoveryHandle()

    /** The mock for the discovery factory. */
    private val discoveryFactory = createDiscoveryFactory()

    /** The mock for the archive service. */
    private val archiveService = mock[ArchiveServiceImpl]

    /** The mock for the archive service factory. */
    private val archiveServiceFactory = mock[ArchiveServiceImpl.Factory]

    /** Mock for the registration of the archive service. */
    private val archiveServiceRegistration = mock[ServiceRegistration[ArchiveService]]

    /** The mock bundle context. */
    private val bundleContext = createBundleContext()

    /** The mock component context. */
    private val componentContext = createComponentContext()

    /** The component to be tested. */
    private val component = createComponent()

    component.initActorSystem(system)
    initArchiveFactory()
    initConfiguration()

    /**
      * Prepares the mock for the archive factory to expect an invocation.
      * The function only needs to be called if a non-standard timeout is
      * desired.
      *
      * @param archiveTimeout the archive timeout
      * @return this test helper
      */
    final def initArchiveFactory(archiveTimeout: Timeout = ArchiveTimeout): ComponentTestHelper =
      reset(archiveServiceFactory)
      when(archiveServiceFactory.apply(argEq(ArchiveUri), argEq(HttpRequestSenderFactoryImpl))
        (using argEq(system), argEq(archiveTimeout))).thenReturn(archiveService)
      this

    /**
      * Invokes the test component to initialize the configuration service 
      * based on the given configuration. Using this function, a non-standard
      * configuration can be passed.
      *
      * @param platformConfig the configuration to be used
      * @return this test helper
      */
    final def initConfiguration(platformConfig: ImmutableHierarchicalConfiguration = 
                                ArchiveClientConfigTestHelper.defaultTestConfig): ComponentTestHelper =
      val configService = new ConfigService:
        override def config: ImmutableHierarchicalConfiguration = platformConfig

      component.initConfigService(configService)
      this

    /**
      * Activates the test component.
      *
      * @return this test helper
      */
    def activate(): ComponentTestHelper =
      component.activate(componentContext)
      this

    /**
      * Deactivates the test component.
      *
      * @return this test helper
      */
    def deactivate(): ComponentTestHelper =
      component.deactivate(componentContext)
      this

    /**
      * Simulates a successful discovery operation that yields the test archive
      * URI.
      *
      * @return this test helper
      */
    def succeedDiscovery(): ComponentTestHelper =
      discoveryPromise.success(ArchiveUri)
      this

    /**
      * Verifies that the discovery factory was called and returns the passed
      * in parameters.
      *
      * @return the discovery parameters
      */
    def verifyDiscoveryFactory(): ServerDiscovery.DiscoveryParams =
      val captParams = ArgumentCaptor.forClass(classOf[ServerDiscovery.DiscoveryParams])
      verify(discoveryFactory).apply(
        captParams.capture(),
        argEq(ArchiveClientComponent.ArchiveDiscoveryName)
      )(using any())
      captParams.getValue

    /**
      * Verifies that no discovery operation was started.
      *
      * @return this test helper
      */
    def verifyDiscoverySkipped(): ComponentTestHelper =
      verifyNoInteractions(discoveryFactory)
      this

    /**
      * Verifies that the discovery handle was closed.
      *
      * @return this test helper
      */
    def verifyDiscoveryHandleClosed(): ComponentTestHelper =
      verify(discoveryHandle).close()
      this

    /**
      * Verifies that the archive service was registered as OSGi service.
      *
      * @return this test helper
      */
    def verifyArchiveServiceRegistration(): ComponentTestHelper =
      verify(bundleContext, timeout(3000)).registerService(classOf[ArchiveService], archiveService, null)
      this

    /**
      * Verifies that the archive service has been unregistered.
      *
      * @return this test helper
      */
    def verifyArchiveServiceUnregistration(): ComponentTestHelper =
      verify(archiveServiceRegistration).unregister()
      this

    /**
      * Verifies that the archive has been closed.
      *
      * @return this test helper
      */
    def verifyArchiveServiceClosed(): ComponentTestHelper =
      verify(archiveService).close()
      this

    /**
      * Creates a mock discovery handle that is returned by the mock discovery
      * factory. The handle returns the result future from the managed
      * promise.
      *
      * @return the mock discovery handle
      */
    private def createDiscoveryHandle(): ServerDiscovery.DiscoveryHandle =
      val handle = mock[ServerDiscovery.DiscoveryHandle]
      when(handle.futResult).thenReturn(discoveryPromise.future)
      handle

    /**
      * Creates a mock factory to trigger a discovery operation. The mock is
      * prepared to expect an invocation which is handled to return the managed
      * mock handle.
      *
      * @return the initialized discovery factory mock
      */
    private def createDiscoveryFactory(): ServerDiscovery.Factory =
      val factory = mock[ServerDiscovery.Factory]
      when(factory.apply(any(), argEq(ArchiveClientComponent.ArchiveDiscoveryName))(using any()))
        .thenReturn(discoveryHandle)
      factory

    /**
      * Creates a mock bundle context that is prepared to expect service
      * registrations.
      *
      * @return the mock bundle context
      */
    private def createBundleContext(): BundleContext =
      val bc = mock[BundleContext]
      when(bc.registerService(classOf[ArchiveService], archiveService, null)).thenReturn(archiveServiceRegistration)
      bc

    /**
      * Creates a mock component context that can be used to activate and
      * deactivate the test component.
      *
      * @return the mock component context
      */
    private def createComponentContext(): ComponentContext =
      val cc = mock[ComponentContext]
      when(cc.getBundleContext).thenReturn(bundleContext)
      cc

    /**
      * Creates the component to be tested.
      *
      * @return the component under test
      */
    private def createComponent(): ArchiveClientComponent =
      new ArchiveClientComponent(
        discoveryFactory = discoveryFactory,
        archiveServiceFactory = archiveServiceFactory
      )
