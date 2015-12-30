/*
 * Copyright 2015 The Developers Team.
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

import java.util.concurrent.{TimeUnit, CountDownLatch}

import akka.actor.ActorSystem
import de.oliver_heger.linedj.client.remoting.{ActorFactory, MessageBus, RemoteMessageBus}
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{JavaFxWindowManager, StageFactory}
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{Mockito, ArgumentCaptor}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.osgi.framework.{Bundle, BundleContext}
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ClientManagementApplication''.
  */
class ClientManagementApplicationSpec extends FlatSpec with Matchers with MockitoSugar {
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
    * Starts the specified application. Optionally, the component context can
    * be provided.
    * @param app the application
    * @return the same application
    */
  private def runApp(app: ClientManagementApplication, optCompCtx: Option[ComponentContext] =
  None): ClientManagementApplication = {
    val cctx = optCompCtx getOrElse createComponentContext()
    app activate cctx
    app
  }

  /**
    * Queries the given application for a bean with a specific name. This bean
    * is checked against a type. If this type is matched, the bean is returned;
    * otherwise, an exception is thrown.
    * @param app the application
    * @param name the name of the bean
    * @param m the manifest
    * @tparam T the expected bean type
    * @return the bean of this type
    */
  private def queryBean[T](app: Application, name: String)(implicit m: Manifest[T]): T = {
    app.getApplicationContext.getBeanContext.getBean(name) match {
      case t: T => t
      case b =>
        throw new AssertionError(s"Unexpected bean for name '$name': $b")
    }
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
    * Creates a mock remote message bus factory that returns the specified
    * remote bus.
    * @param remoteBus the remote message bus
    * @return the factory mock
    */
  private def createRemoteMessageBusFactoryMock(remoteBus: RemoteMessageBus):
  RemoteMessageBusFactory = {
    val factory = mock[RemoteMessageBusFactory]
    when(factory.createRemoteMessageBus(any(classOf[ActorFactory]), any(classOf[MessageBus])))
      .thenReturn(remoteBus)
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

  it should "create a default remote message bus factory" in {
    val app = new ClientManagementApplication

    app.remoteMessageBusFactory shouldBe a[RemoteMessageBusFactory]
  }

  it should "create the remote message bus" in {
    val actorSystem = mock[ActorSystem]
    val remoteBus = mock[RemoteMessageBus]
    val factory = createRemoteMessageBusFactoryMock(remoteBus)
    val app = new ClientManagementApplicationTestImpl(factory)
    app initActorSystem actorSystem
    runApp(app)

    val captActorFactory = ArgumentCaptor.forClass(classOf[ActorFactory])
    val captMsgBus = ArgumentCaptor.forClass(classOf[MessageBus])
    verify(factory).createRemoteMessageBus(captActorFactory.capture(), captMsgBus.capture())
    captActorFactory.getValue should be(app.actorFactory)
    val beanMsgBus = queryBean[MessageBus](app, "lineDJ_messageBus")
    captMsgBus.getValue should be(beanMsgBus)

    verify(remoteBus).updateConfiguration("127.0.0.1", 2552)
    verify(remoteBus).activate(true)
  }

  it should "return the message bus from the remote message bus" in {
    val actorSystem = mock[ActorSystem]
    val remoteBus = mock[RemoteMessageBus]
    val factory = createRemoteMessageBusFactoryMock(remoteBus)
    val bus = mock[MessageBus]
    when(remoteBus.bus).thenReturn(bus)
    val app = new ClientManagementApplicationTestImpl(factory)
    app initActorSystem actorSystem
    runApp(app)

    app.messageBus should be(bus)
  }

  it should "take configuration into account when creating the remote message bus" in {
    val appCtx = mock[ApplicationContext]
    val beanCtx = mock[BeanContext]
    val bus = mock[MessageBus]
    val remoteBus = mock[RemoteMessageBus]
    val factory = createRemoteMessageBusFactoryMock(remoteBus)
    val config = new PropertiesConfiguration
    val remoteAddress = "remote.host.test"
    val remotePort = 1234
    config.addProperty("remote.server.address", remoteAddress)
    config.addProperty("remote.server.port", remotePort)
    expectBean(beanCtx, "lineDJ_messageBus", bus)
    when(appCtx.getBeanContext).thenReturn(beanCtx)
    when(appCtx.getConfiguration).thenReturn(config)
    val app = new ClientManagementApplication(factory)

    app.createRemoteMessageBus(appCtx) should be(remoteBus)
    verify(remoteBus).updateConfiguration(remoteAddress, remotePort)
  }

  it should "provide access to the shared stage factory" in {
    val actorSystem = mock[ActorSystem]
    val remoteBus = mock[RemoteMessageBus]
    val factory = createRemoteMessageBusFactoryMock(remoteBus)
    val app = new ClientManagementApplicationTestImpl(factory)
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
    val app = new ClientManagementApplicationTestImpl(createRemoteMessageBusFactoryMock
    (mock[RemoteMessageBus]))
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
    val app = runApp(new ClientManagementApplicationTestImpl(createRemoteMessageBusFactoryMock
    (mock[RemoteMessageBus])))
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

  /**
    * A test implementation of the management application which prevents
    * certain critical beans to be instantiated. In particular, the JavaFX
    * application must not be started more than once, which is triggered by the
    * stage factory.
    * @param factory the remote message bus factory
    */
  private class ClientManagementApplicationTestImpl(factory: RemoteMessageBusFactory) extends
  ClientManagementApplication(factory) {
    /** A mock stage factory used per default by this object. */
    val mockStageFactory = mock[StageFactory]

    override private[app] def extractStageFactory(appCtx: ApplicationContext): StageFactory = {
      mockStageFactory
    }
  }

}
