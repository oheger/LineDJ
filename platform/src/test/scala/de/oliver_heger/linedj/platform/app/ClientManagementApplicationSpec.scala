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

package de.oliver_heger.linedj.platform.app

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency}
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData
import de.oliver_heger.linedj.platform.mediaifc.ext.{ArchiveAvailabilityExtension, AvailableMediaExtension, MetadataCache, StateListenerExtension}
import de.oliver_heger.linedj.platform.mediaifc.{MediaFacade, MediaFacadeFactory}
import de.oliver_heger.linedj.utils.ActorFactory
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.WindowManager
import org.apache.commons.configuration.{PropertiesConfiguration, XMLConfiguration}
import org.apache.pekko.actor.{Actor, ActorSystem, Terminated}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.{ArgumentCaptor, Mockito}
import org.osgi.framework.{Bundle, BundleContext}
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.File
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

object ClientManagementApplicationSpec:
  /** LineDJ application ID. */
  private val ApplicationID = "ClientManagementApplicationSpec"

  /** The name of the user configuration file. */
  private val UserConfigFile = ".lineDJ-" + ApplicationID + "-management.xml"

  /**
    * Returns a file pointing to the user configuration of the management
    * application.
    *
    * @return the user configuration file
    */
  private def userConfigFile(): File =
    val userHome = new File(System.getProperty("user.home"))
    new File(userHome, UserConfigFile)

/**
  * Test class for ''ClientManagementApplication''.
  */
class ClientManagementApplicationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll
  with MockitoSugar with ApplicationTestSupport:
  import ClientManagementApplicationSpec._

  override protected def afterAll(): Unit =
    val configFile = userConfigFile()
    if configFile.exists() then
      configFile.delete()

  /**
    * Creates a mock component context which allows access to the bundle
    * context.
    *
    * @return the mock component context
    */
  private def createComponentContext(): ComponentContext =
    val compCtx = mock[ComponentContext]
    val bc = mock[BundleContext]
    when(compCtx.getBundleContext).thenReturn(bc)
    compCtx

  /**
    * Starts the specified application. The reference to the media facade
    * factory is initialized. Optionally, the component context can
    * be provided.
    *
    * @param app              the application
    * @param optCompCtx       an optional component context
    * @param optFacadeFactory an optional media facade factory
    * @return the same application
    */
  private def runApp(app: ClientManagementApplication, optCompCtx: Option[ComponentContext] =
  None, optFacadeFactory: Option[MediaFacadeFactory] = None): ClientManagementApplication =
    app.initMediaFacadeFactory(optFacadeFactory getOrElse createMediaFacadeFactoryMock())
    val cctx = optCompCtx getOrElse createComponentContext()
    app activate cctx
    app

  /**
    * Creates a mock application context that is equipped with a mock bean
    * context and an empty configuration.
    *
    * @return the mock application context
    */
  private def createAppCtxWithBC(): ApplicationContext =
    val appCtx = mock[ApplicationContext]
    val bc = mock[BeanContext]
    when(appCtx.getBeanContext).thenReturn(bc)
    when(appCtx.getConfiguration).thenReturn(new PropertiesConfiguration)
    appCtx

  /**
    * Creates a mock application context with a bean context that contains the
    * specified named beans.
    *
    * @param beans a map with the beans for the mock bean context
    * @return the mock application context
    */
  private def createAppCtxWithBeans(beans: Map[String, AnyRef]): ApplicationContext =
    val appCtx = createAppCtxWithBC()
    addBeans(appCtx.getBeanContext, beans)
    appCtx

  /**
    * Returns a map for use by ''createAppCtxWithBeans()'' that contains the
    * specified bean for the message bus plus some additional standard beans
    * required by the application.
    *
    * @param bus the message bus
    * @return the map with the message bus bean
    */
  private def messageBusBeanMap(bus: MessageBus): Map[String, AnyRef] =
  Map("LineDJ_messageBus" -> bus)

  /**
    * Creates a mock media facade factory. If a facade is passed in, it is
    * returned. Otherwise, a mock facade is created and returned by the
    * factory.
    * @param optFacade an optional facade to be returned
    * @return the factory mock
    */
  private def createMediaFacadeFactoryMock(optFacade: Option[MediaFacade] = None):
  MediaFacadeFactory =
    val facade = optFacade getOrElse mock[MediaFacade]
    val factory = mock[MediaFacadeFactory]
    when(factory.createMediaFacade(any(), any())).thenReturn(facade)
    factory

  "A ClientManagementApplication" should "provide access to the actor system" in:
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementApplication

    app initActorSystem actorSystem
    app.actorSystem should be(actorSystem)

  it should "provide access to an initialized actor factory" in:
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementApplication

    app initActorSystem actorSystem
    app.actorFactory.actorSystem should be(actorSystem)

  it should "create the media facade" in:
    val actorSystem = mock[ActorSystem]
    val facade = mock[MediaFacade]
    val factory = createMediaFacadeFactoryMock(Some(facade))
    val app = new ClientManagementApplicationTestImpl
    app initActorSystem actorSystem
    runApp(app, optFacadeFactory = Some(factory))

    val captActorFactory = ArgumentCaptor.forClass(classOf[ActorFactory])
    val captMsgBus = ArgumentCaptor.forClass(classOf[MessageBus])
    verify(factory).createMediaFacade(captActorFactory.capture(), captMsgBus.capture())
    captActorFactory.getValue should be(app.actorFactory)
    val beanMsgBus = queryBean[MessageBus](app, "LineDJ_messageBus")
    captMsgBus.getValue should be(beanMsgBus)
    app.mediaFacade should be(facade)
    verify(facade).activate(true)

  it should "return the message bus from the media facade" in:
    val actorSystem = mock[ActorSystem]
    val facade = mock[MediaFacade]
    val factory = createMediaFacadeFactoryMock(Some(facade))
    val bus = mock[MessageBus]
    when(facade.bus).thenReturn(bus)
    val app = new ClientManagementApplicationTestImpl
    app initActorSystem actorSystem
    runApp(app, optFacadeFactory = Some(factory))

    app.messageBus should be(bus)

  it should "configure the media facade" in:
    val bus = mock[MessageBus]
    val appCtx = createAppCtxWithBeans(messageBusBeanMap(bus))
    val facade = mock[MediaFacade]
    val factory = createMediaFacadeFactoryMock(Some(facade))
    val app = new ClientManagementApplication
    app initMediaFacadeFactory factory

    app.createMediaFacade(appCtx) should be(facade)
    verify(facade).initConfiguration(appCtx.getConfiguration)

  it should "register a message bus listener for shutdown handling" in:
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementAppWithMsgBus
    app initActorSystem actorSystem
    runApp(app)

    val exitHandler = mock[Runnable]
    app setExitHandler exitHandler
    val shutdownMsg = ShutdownHandler.Shutdown(app)
    app.mockBus.findListenerForMessage(shutdownMsg) foreach { r =>
      r(shutdownMsg)
    }
    verify(exitHandler).run()

  it should "ignore a Shutdown message with wrong content" in:
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementAppWithMsgBus
    app initActorSystem actorSystem
    runApp(app)

    val shutdownMsg = ShutdownHandler.Shutdown(null)
    val listener = app.mockBus.findListenerForMessage(shutdownMsg).get
    val exitHandler = mock[Runnable]
    app setExitHandler exitHandler
    listener.apply(shutdownMsg)
    verify(exitHandler, never()).run()

  /**
    * Helper method for retrieving a specific extension registered by the
    * specified application.
    *
    * @param app    the application
    * @param appCtx the application context
    * @param facade the media facade
    * @param t      the class tag for the extension class
    * @tparam E the type of the extension
    * @return the extension of this type
    */
  private def findExtension[E](app: ClientManagementApplication, appCtx: ApplicationContext,
                               facade: MediaFacade)
                              (implicit t: ClassTag[E]): E =
    val extensions = app.createMediaIfcExtensions(appCtx, facade)
    val ext = extensions.find(_.getClass == t.runtimeClass)
    ext.get.asInstanceOf[E]

  it should "create an extension for the media archive availability" in:
    val facade = mock[MediaFacade]
    val app = new ClientManagementApplication

    findExtension[ArchiveAvailabilityExtension](app, createAppCtxWithBC(), facade)

  it should "create an extension for state listeners of the media archive" in:
    val facade = mock[MediaFacade]
    val app = new ClientManagementApplication

    val ext = findExtension[StateListenerExtension](app, createAppCtxWithBC(), facade)
    ext.mediaFacade should be(facade)

  it should "create an extension for available media of the media archive" in:
    val facade = mock[MediaFacade]
    val app = new ClientManagementApplication

    val ext = findExtension[AvailableMediaExtension](app, createAppCtxWithBC(), facade)
    ext.mediaFacade should be(facade)

  it should "create an extension for the metadata cache" in:
    val facade = mock[MediaFacade]
    val appCtx = createAppCtxWithBC()
    val CacheSize = 2222
    appCtx.getConfiguration.addProperty("media.cacheSize", CacheSize)
    val app = new ClientManagementApplication

    val cacheExt = findExtension[MetadataCache](app, appCtx, facade)
    cacheExt.mediaFacade should be(facade)
    cacheExt.cacheSize should be(CacheSize)

  it should "use a default size for the metadata cache" in:
    val cacheExt = findExtension[MetadataCache](new ClientManagementApplication,
      createAppCtxWithBC(), mock[MediaFacade])
    cacheExt.cacheSize should be(ClientManagementApplication.DefaultMetadataCacheSize)

  it should "register media archive extensions on the message bus" in:
    def createListener(): MessageBusListener =
      val listener = mock[MessageBusListener]
      val receive = mock[Actor.Receive]
      when(listener.receive).thenReturn(receive)
      listener

    val bus = mock[MessageBus]
    val appCtx = createAppCtxWithBeans(messageBusBeanMap(bus))
    val facade = mock[MediaFacade]
    val extension1 = createListener()
    val extension2 = createListener()
    val app = new ClientManagementApplicationTestImpl:
      override private[app] def createMediaIfcExtensions(_appCtx: ApplicationContext,
                                                         facade: MediaFacade):
      Iterable[MessageBusListener] =
        _appCtx should be(appCtx)
        List(extension1, extension2)
    app initMediaFacadeFactory createMediaFacadeFactoryMock(Some(facade))

    app.createMediaFacade(appCtx)
    val inOrder = Mockito.inOrder(bus, facade)
    inOrder.verify(bus).registerListener(extension1.receive)
    inOrder.verify(bus).registerListener(extension2.receive)
    inOrder.verify(facade).activate(true)

  it should "provide access to the shared window manager" in:
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementApplicationTestImpl
    app initActorSystem actorSystem
    runApp(app)

    app.mockWindowManager should be(app.mockWindowManager)

  it should "correctly extract the window manager and stage factory from the bean context" in:
    val appCtx = mock[ApplicationContext]
    val beanCtx = mock[BeanContext]
    val windowManager = mock[WindowManager]
    when(appCtx.getBeanContext).thenReturn(beanCtx)
    addBean(beanCtx, "jguiraffe.windowManager", windowManager)

    val app = new ClientManagementApplication
    app extractWindowManager appCtx should be(windowManager)

  /**
    * Checks whether the exit handler works correctly if termination of the
    * actor system yields the given future.
    *
    * @param futTerminate the future for the actor system's termination
    */
  private def checkExitHandler(futTerminate: Future[Terminated]): Unit =
    val system = mock[ActorSystem]
    when(system.terminate()).thenReturn(futTerminate)
    val componentContext = createComponentContext()
    val bundleContext = componentContext.getBundleContext
    val bundle = mock[Bundle]
    val latch = new CountDownLatch(1)
    when(bundleContext.getBundle(0)).thenReturn(bundle)
    doAnswer((_: InvocationOnMock) => latch.countDown()).when(bundle).stop()
    val app = new ClientManagementApplicationTestImpl
    app initActorSystem system
    runApp(app, Some(componentContext))

    val exitHandler = app.getExitHandler
    exitHandler.run()
    latch.await(3, TimeUnit.SECONDS) shouldBe true
    val io = Mockito.inOrder(system, bundle)
    io.verify(system).terminate()
    io.verify(bundle).stop()

  it should "set an OSGi-compliant exit handler" in:
    implicit val ec: ExecutionContextExecutor = ExecutionContext.global
    val term = Terminated(null)(existenceConfirmed = true, addressTerminated = true)
    checkExitHandler(Future(term))

  it should "exit the app even if shutdown of the actor system fails" in:
    val futTerm = Future.failed[Terminated](new RuntimeException("BOOM"))
    checkExitHandler(futTerm)

  it should "return undefined configuration data per default" in:
    val app = new ClientManagementApplicationTestImpl

    app.mediaIfcConfig should be(None)

  it should "track services of type MediaIfcConfigData" in:
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus

    app setMediaIfcConfig confData
    app.mediaIfcConfig should be(Some(confData))

  it should "detect a removed config data service" in:
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus
    app setMediaIfcConfig confData

    app unsetMediaIfcConfig confData
    app.mediaIfcConfig should be(None)

  it should "send a notification for an updated media interface config" in:
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus

    app setMediaIfcConfig confData
    val msg = app.mockBus.expectMessageType[ClientManagementApplication.MediaIfcConfigUpdated]
    msg.currentConfigData should be(Some(confData))

  it should "send a notification for a removed media interface config" in:
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus
    app setMediaIfcConfig confData
    app.mockBus.expectMessageType[ClientManagementApplication.MediaIfcConfigUpdated]

    app unsetMediaIfcConfig confData
    val msg = app.mockBus.expectMessageType[ClientManagementApplication.MediaIfcConfigUpdated]
    msg.currentConfigData should be(None)

  it should "ignore an unrelated remove notification" in:
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus
    app setMediaIfcConfig confData
    app.mockBus.expectMessageType[ClientManagementApplication.MediaIfcConfigUpdated]

    app unsetMediaIfcConfig mock[MediaIfcConfigData]
    app.mediaIfcConfig should be(Some(confData))
    app.mockBus.expectNoMessage(100.millis)

  it should "provide access to the management configuration" in:
    val app = runApp(new ClientManagementApplicationTestImpl)

    app.managementConfiguration should be theSameInstanceAs app.getUserConfiguration
    val config = app.managementConfiguration.asInstanceOf[XMLConfiguration]
    config.getFile.getName should be(UserConfigFile)
    config.getFile.delete()

  it should "create a correct ServiceDependenciesManager" in:
    val componentContext = createComponentContext()
    val app = runApp(new ClientManagementAppWithMsgBus(), optCompCtx = Some(componentContext))

    val depManager = app.createServiceDependenciesManager()
    depManager.bundleContext should be(componentContext.getBundleContext)

  it should "register the ServiceDependenciesManager at the message bus" in:
    val app = new ClientManagementAppWithMsgBus
    runApp(app)

    val optRec = app.mockBus.findListenerForMessage(RegisterService(ServiceDependency("foo")))
    optRec shouldBe defined

  it should "create a correct MediaFacadeActorsServiceWrapper" in:
    val componentContext = createComponentContext()
    val app = runApp(new ClientManagementAppWithMsgBus(), optCompCtx = Some(componentContext))

    val facadeWrapper = app.createFacadeServiceWrapper()
    facadeWrapper.clientAppContext should be(app)
    facadeWrapper.bundleContext should be(componentContext.getBundleContext)

  it should "activate the MediaFacadeActorsServiceWrapper" in:
    val wrapper = mock[MediaFacadeActorsServiceWrapper]
    val app = new ClientManagementAppWithMsgBus:
      override private[app] def createFacadeServiceWrapper() = wrapper
    runApp(app)

    verify(wrapper).activate()

  /**
    * A test implementation of the management application which prevents
    * certain critical beans to be instantiated. In particular, the JavaFX
    * application must not be started more than once, which is triggered by the
    * stage factory.
    *
    * @param mockExtensions       a flag whether extensions for the media
    *                             interface should be mocked
    * @param mockShutdownHandling a flag whether registration of a shutdown
    *                             listener should be mocked
    * @param mockOsgiSupport      a flag whether listener registrations for
    *                             OSGi support should be mocked
    */
  private class ClientManagementApplicationTestImpl(mockExtensions: Boolean = true,
                                                    mockShutdownHandling: Boolean = true,
                                                    mockOsgiSupport: Boolean = true)
    extends ClientManagementApplication:
    /** A mock for the window manager bean. */
    val mockWindowManager: WindowManager = mock[WindowManager]

    /**
      * @inheritdoc This implementation either calls the super method or (if
      *             mocking is disabled) returns an empty collection.
      */
    override private[app] def createMediaIfcExtensions(appCtx: ApplicationContext,
                                                       facade: MediaFacade):
    Iterable[MessageBusListener] =
    if mockExtensions then List.empty
    else super.createMediaIfcExtensions(appCtx, facade)

    override private[app] def extractWindowManager(appCtx: ApplicationContext): WindowManager = mockWindowManager

    /**
      * @inheritdoc Calls the super method if mocking is disabled.
      */
    override private[app] def initShutdownHandling(bus: MessageBus): Unit =
      if !mockShutdownHandling then
        super.initShutdownHandling(bus)

    /**
      * @inheritdoc Calls the super method if mocking is disabled.
      */
    override private[app] def installOsgiServiceSupport(): Unit =
      if !mockOsgiSupport then
        super.installOsgiServiceSupport()

    /**
      * Queries a system property and returns its value.
      *
      * @param key the key of the property
      * @return an option for the property's value
      */
    override def getSystemProperty(key: String): Option[String] =
      if key == "LineDJ_ApplicationID" then Some(ApplicationID)
      else super.getSystemProperty(key)

  /**
    * A test management application implementation which uses a mock message
    * bus. This can be used to test whether messages have been published on the
    * bus.
    */
  private class ClientManagementAppWithMsgBus
    extends ClientManagementApplicationTestImpl(mockShutdownHandling = false,
      mockOsgiSupport = false):
    /** The mock message bus. */
    val mockBus: MessageBusTestImpl = new MessageBusTestImpl

    override def messageBus: MessageBus = mockBus
