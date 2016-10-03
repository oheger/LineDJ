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

package de.oliver_heger.linedj.platform.app

import java.io.File
import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{Actor, ActorSystem}
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus, MessageBusListener}
import de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.{ConsumerID, ConsumerIDFactory}
import de.oliver_heger.linedj.platform.mediaifc.ext.{ArchiveAvailabilityExtension, DefaultConsumerIDFactory, StateListenerExtension}
import de.oliver_heger.linedj.platform.mediaifc.{MediaFacade, MediaFacadeFactory}
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{JavaFxWindowManager, StageFactory}
import org.apache.commons.configuration.{PropertiesConfiguration, XMLConfiguration}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Mockito}
import org.osgi.framework.{Bundle, BundleContext}
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

object ClientManagementApplicationSpec {
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
  private def userConfigFile(): File = {
    val userHome = new File(System.getProperty("user.home"))
    new File(userHome, UserConfigFile)
  }
}

/**
  * Test class for ''ClientManagementApplication''.
  */
class ClientManagementApplicationSpec extends FlatSpec with Matchers with BeforeAndAfterAll
  with MockitoSugar with ApplicationTestSupport {
  import ClientManagementApplicationSpec._

  override protected def afterAll(): Unit = {
    val configFile = userConfigFile()
    if (configFile.exists()) {
      configFile.delete()
    }
  }

  /**
    * Creates a mock component context which allows access to the bundle
    * context.
    *
    * @return the mock component context
    */
  private def createComponentContext(): ComponentContext = {
    val compCtx = mock[ComponentContext]
    val bc = mock[BundleContext]
    when(compCtx.getBundleContext).thenReturn(bc)
    compCtx
  }

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
  None, optFacadeFactory: Option[MediaFacadeFactory] = None): ClientManagementApplication = {
    app.initMediaFacadeFactory(optFacadeFactory getOrElse createMediaFacadeFactoryMock())
    val cctx = optCompCtx getOrElse createComponentContext()
    app activate cctx
    app
  }

  /**
    * Creates a mock application context that is equipped with a mock bean
    * context and an empty configuration.
    *
    * @return the mock application context
    */
  private def createAppCtxWithBC(): ApplicationContext = {
    val appCtx = mock[ApplicationContext]
    val bc = mock[BeanContext]
    when(appCtx.getBeanContext).thenReturn(bc)
    when(appCtx.getConfiguration).thenReturn(new PropertiesConfiguration)
    appCtx
  }

  /**
    * Creates a mock application context with a bean context that contains the
    * specified named beans.
    *
    * @param beans a map with the beans for the mock bean context
    * @return the mock application context
    */
  private def createAppCtxWithBeans(beans: Map[String, AnyRef]): ApplicationContext = {
    val appCtx = createAppCtxWithBC()
    addBeans(appCtx.getBeanContext, beans)
    appCtx
  }

  /**
    * Returns a map for use by ''createAppCtxWithBeans()'' that contains the
    * specified bean for the message bus plus some additional standard beans
    * required by the application.
    *
    * @param bus the message bus
    * @return the map with the message bus bean
    */
  private def messageBusBeanMap(bus: MessageBus): Map[String, AnyRef] =
  Map("LineDJ_messageBus" -> bus,
    "LineDJ_consumerIDFactory" -> mock[DefaultConsumerIDFactory])

  /**
    * Creates a mock media facade factory. If a facade is passed in, it is
    * returned. Otherwise, a mock facade is created and returned by the
    * factory.
    * @param optFacade an optional facade to be returned
    * @return the factory mock
    */
  private def createMediaFacadeFactoryMock(optFacade: Option[MediaFacade] = None):
  MediaFacadeFactory = {
    val facade = optFacade getOrElse mock[MediaFacade]
    val factory = mock[MediaFacadeFactory]
    when(factory.createMediaFacade(any(classOf[ActorFactory]), any(classOf[MessageBus])))
      .thenReturn(facade)
    factory
  }

  "A ClientManagementApplication" should "provide access to the actor system" in {
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementApplication

    app initActorSystem actorSystem
    app.actorSystem should be(actorSystem)
  }

  it should "provide access to an initialized actor factory" in {
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementApplication

    app initActorSystem actorSystem
    app.actorFactory.actorSystem should be(actorSystem)
  }

  it should "create the media facade" in {
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
  }

  it should "return the message bus from the media facade" in {
    val actorSystem = mock[ActorSystem]
    val facade = mock[MediaFacade]
    val factory = createMediaFacadeFactoryMock(Some(facade))
    val bus = mock[MessageBus]
    when(facade.bus).thenReturn(bus)
    val app = new ClientManagementApplicationTestImpl
    app initActorSystem actorSystem
    runApp(app, optFacadeFactory = Some(factory))

    app.messageBus should be(bus)
  }

  it should "configure the media facade" in {
    val bus = mock[MessageBus]
    val appCtx = createAppCtxWithBeans(messageBusBeanMap(bus))
    val facade = mock[MediaFacade]
    val factory = createMediaFacadeFactoryMock(Some(facade))
    val app = new ClientManagementApplication
    app initMediaFacadeFactory factory

    app.createMediaFacade(appCtx) should be(facade)
    verify(facade).initConfiguration(appCtx.getConfiguration)
  }

  it should "create an extension for the media archive availability" in {
    val facade = mock[MediaFacade]
    val app = new ClientManagementApplication

    val extensions = app.createMediaIfcExtensions(facade)
    val archiveExt = extensions.find(_.isInstanceOf[ArchiveAvailabilityExtension])
    archiveExt should not be 'empty
  }

  it should "create an extension for state listeners of the media archive" in {
    val facade = mock[MediaFacade]
    val app = new ClientManagementApplication

    val extensions = app.createMediaIfcExtensions(facade)
    val listenerExt = extensions.find(_.isInstanceOf[StateListenerExtension])
    listenerExt.get.asInstanceOf[StateListenerExtension].mediaFacade should be(facade)
  }

  it should "register media archive extensions on the message bus" in {
    def createListener(): MessageBusListener = {
      val listener = mock[MessageBusListener]
      val receive = mock[Actor.Receive]
      when(listener.receive).thenReturn(receive)
      listener
    }

    val bus = mock[MessageBus]
    val appCtx = createAppCtxWithBeans(messageBusBeanMap(bus))
    val facade = mock[MediaFacade]
    val extension1 = createListener()
    val extension2 = createListener()
    val app = new ClientManagementApplicationTestImpl {
      override private[app] def createMediaIfcExtensions(facade: MediaFacade):
      Iterable[MessageBusListener] = List(extension1, extension2)
    }
    app initMediaFacadeFactory createMediaFacadeFactoryMock(Some(facade))

    app.createMediaFacade(appCtx)
    val inOrder = Mockito.inOrder(bus, facade)
    inOrder.verify(bus).registerListener(extension1.receive)
    inOrder.verify(bus).registerListener(extension2.receive)
    inOrder.verify(facade).activate(true)
  }

  it should "provide access to the shared stage factory" in {
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementApplicationTestImpl
    app initActorSystem actorSystem
    runApp(app)

    app.stageFactory should be(app.mockStageFactory)
  }

  it should "correctly extract the stage factory from the bean context" in {
    val appCtx = mock[ApplicationContext]
    val beanCtx = mock[BeanContext]
    val windowManager = mock[JavaFxWindowManager]
    val stageFactory = mock[StageFactory]
    when(appCtx.getBeanContext).thenReturn(beanCtx)
    addBean(beanCtx, "jguiraffe.windowManager", windowManager)
    when(windowManager.stageFactory).thenReturn(stageFactory)

    val app = new ClientManagementApplication
    app extractStageFactory appCtx should be(stageFactory)
  }

  it should "set an OSGi-compliant exit handler" in {
    val componentContext = createComponentContext()
    val bundleContext = componentContext.getBundleContext
    val bundle = mock[Bundle]
    when(bundleContext.getBundle(0)).thenReturn(bundle)
    val app = new ClientManagementApplicationTestImpl
    runApp(app, Some(componentContext))

    val exitHandler = app.getExitHandler
    exitHandler.run()
    verify(bundle).stop()
  }

  it should "allow adding applications concurrently" in {
    val count = 32
    val app = new ClientManagementApplication
    val latch = new CountDownLatch(1)
    val clientApps = 1 to count map (_ => mock[Application])
    val threads = clientApps map { a =>
      new Thread {
        override def run(): Unit = {
          latch.await(5, TimeUnit.SECONDS)
          app addClientApplication a
        }
      }
    }
    val removeApp = mock[Application]
    threads foreach (_.start())

    latch.countDown()
    app addClientApplication removeApp
    app removeClientApplication removeApp
    threads foreach (_.join(5000))
    val clients = app.clientApplications
    clients should have size count
    clients should contain only (clientApps: _*)
  }

  it should "implement correct shutdown behavior" in {
    def initShutdown(client: Application): Runnable = {
      val captExit = ArgumentCaptor.forClass(classOf[Runnable])
      verify(client).setExitHandler(captExit.capture())
      doAnswer(new Answer[Object] {
        override def answer(invocationOnMock: InvocationOnMock): Object = {
          captExit.getValue.run()
          null
        }
      }).when(client).shutdown()
      captExit.getValue
    }

    val client1, client2, client3 = mock[Application]
    val exitHandler = mock[Runnable]
    val app = runApp(new ClientManagementApplicationTestImpl)
    app addClientApplication client1
    app addClientApplication client2
    app addClientApplication client3
    app setExitHandler exitHandler

    val clientExitHandler = initShutdown(client2)
    initShutdown(client1)
    initShutdown(client3)
    clientExitHandler.run()
    verify(client1, Mockito.times(1)).shutdown()
    verify(client3, Mockito.times(1)).shutdown()
    verify(client2, Mockito.never()).shutdown()
    verify(exitHandler, Mockito.times(1)).run()
  }

  it should "return undefined configuration data per default" in {
    val app = new ClientManagementApplicationTestImpl

    app.mediaIfcConfig should be(None)
  }

  it should "track services of type MediaIfcConfigData" in {
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus

    app setMediaIfcConfig confData
    app.mediaIfcConfig should be(Some(confData))
  }

  it should "detect a removed config data service" in {
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus
    app setMediaIfcConfig confData

    app unsetMediaIfcConfig confData
    app.mediaIfcConfig should be(None)
  }

  it should "send a notification for an updated media interface config" in {
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus

    app setMediaIfcConfig confData
    verify(app.mockBus)
      .publish(ClientManagementApplication.MediaIfcConfigUpdated(Some(confData)))
  }

  it should "send a notification for a removed media interface config" in {
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus
    app setMediaIfcConfig confData

    app unsetMediaIfcConfig confData
    verify(app.mockBus).publish(ClientManagementApplication.MediaIfcConfigUpdated(None))
  }

  it should "ignore an unrelated remove notification" in {
    val confData = mock[MediaIfcConfigData]
    val app = new ClientManagementAppWithMsgBus
    app setMediaIfcConfig confData

    app unsetMediaIfcConfig mock[MediaIfcConfigData]
    app.mediaIfcConfig should be(Some(confData))
    verify(app.mockBus, never())
      .publish(ClientManagementApplication.MediaIfcConfigUpdated(None))
  }

  it should "provide access to the management configuration" in {
    val app = runApp(new ClientManagementApplicationTestImpl)

    app.managementConfiguration should be theSameInstanceAs app.getUserConfiguration
    val config = app.managementConfiguration.asInstanceOf[XMLConfiguration]
    config.getFile.getName should be(UserConfigFile)
    config.getFile.delete()
  }

  it should "define a correct bean for the ConsumerIDFactory" in {
    val actorSystem = mock[ActorSystem]
    val app = new ClientManagementApplicationTestImpl
    app initActorSystem actorSystem
    runApp(app)

    val factory = queryBean[DefaultConsumerIDFactory](app, "LineDJ_consumerIDFactory")
    app.consumerIDFactory should be(factory)
  }

  it should "offer a convenience method for creating consumer IDs" in {
    val cidFactory = mock[ConsumerIDFactory]
    val cid = mock[ConsumerID]
    when(cidFactory.createID(this)).thenReturn(cid)
    val app = new ClientManagementApplicationTestImpl {
      override def consumerIDFactory: ConsumerIDFactory = cidFactory
    }

    app createConsumerID this should be(cid)
  }

  /**
    * A test implementation of the management application which prevents
    * certain critical beans to be instantiated. In particular, the JavaFX
    * application must not be started more than once, which is triggered by the
    * stage factory.
    *
    * @param mockExtensions a flag whether extensions for the media interface
    *                       should be mocked
    */
  private class ClientManagementApplicationTestImpl(mockExtensions: Boolean = true)
    extends ClientManagementApplication {
    /** A mock stage factory used per default by this object. */
    val mockStageFactory = mock[StageFactory]

    /**
      * @inheritdoc This implementation either calls the super method or (if
      *             mocking is disabled) returns an empty collection.
      */
    override private[app] def createMediaIfcExtensions(facade: MediaFacade):
    Iterable[MessageBusListener] =
    if(mockExtensions) List.empty
    else super.createMediaIfcExtensions(facade)

    override private[app] def extractStageFactory(appCtx: ApplicationContext): StageFactory = {
      mockStageFactory
    }

    /**
      * Queries a system property and returns its value.
      *
      * @param key the key of the property
      * @return an option for the property's value
      */
    override def getSystemProperty(key: String): Option[String] = {
      if(key == "LineDJ_ApplicationID") Some(ApplicationID)
      else super.getSystemProperty(key)
    }
  }

  /**
    * A test management application implementation which uses a mock message
    * bus. This can be used to test whether messages have been published on the
    * bus.
    */
  private class ClientManagementAppWithMsgBus extends ClientManagementApplicationTestImpl {
    /** The mock message bus. */
    val mockBus = mock[MessageBus]

    override def messageBus: MessageBus = mockBus
  }
}
