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

package de.oliver_heger.linedj.client.app

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.ActorSystem
import de.oliver_heger.linedj.client.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.client.mediaifc.config.MediaIfcConfigData
import de.oliver_heger.linedj.client.mediaifc.{MediaFacade, MediaFacadeFactory}
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{JavaFxWindowManager, StageFactory}
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Mockito}
import org.osgi.framework.{Bundle, BundleContext}
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ClientManagementApplication''.
  */
class ClientManagementApplicationSpec extends FlatSpec with Matchers with MockitoSugar
with ApplicationTestSupport {
  /**
    * Creates a mock component context which allows access to the bundle
    * context.
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
    * Prepares a mock bean context to expect a query for a bean.
    * @param ctx the bean context
    * @param name the name of the bean
    * @param bean the bean itself
    */
  private def expectBean(ctx: BeanContext, name: String, bean: AnyRef): Unit = {
    doReturn(bean).when(ctx).getBean(name)
  }

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
    val beanMsgBus = queryBean[MessageBus](app, "lineDJ_messageBus")
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
    val appCtx = mock[ApplicationContext]
    val beanCtx = mock[BeanContext]
    val bus = mock[MessageBus]
    val facade = mock[MediaFacade]
    val factory = createMediaFacadeFactoryMock(Some(facade))
    val config = new PropertiesConfiguration
    expectBean(beanCtx, "lineDJ_messageBus", bus)
    when(appCtx.getBeanContext).thenReturn(beanCtx)
    when(appCtx.getConfiguration).thenReturn(config)
    val app = new ClientManagementApplication
    app initMediaFacadeFactory factory

    app.createMediaFacade(appCtx) should be(facade)
    verify(facade).initConfiguration(config)
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
    expectBean(beanCtx, "jguiraffe.windowManager", windowManager)
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

  /**
    * A test implementation of the management application which prevents
    * certain critical beans to be instantiated. In particular, the JavaFX
    * application must not be started more than once, which is triggered by the
    * stage factory.
    */
  private class ClientManagementApplicationTestImpl extends ClientManagementApplication {
    /** A mock stage factory used per default by this object. */
    val mockStageFactory = mock[StageFactory]

    override private[app] def extractStageFactory(appCtx: ApplicationContext): StageFactory = {
      mockStageFactory
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
