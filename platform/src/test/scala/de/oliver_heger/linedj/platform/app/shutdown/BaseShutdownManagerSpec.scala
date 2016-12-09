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

package de.oliver_heger.linedj.platform.app.shutdown

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ShutdownListener}
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext, ApplicationShutdownListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowClosingStrategy}
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
  * Test class for ''BaseShutdownManager''.
  */
class BaseShutdownManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("BaseShutdownManagerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a mock for an application. The application also has a main
    * window.
    *
    * @return the mock application
    */
  private def createApplicationMock(): Application = {
    val app = mock[Application]
    val appCtx = mock[ApplicationContext]
    val window = mock[Window]
    when(app.getApplicationContext).thenReturn(appCtx)
    when(appCtx.getMainWindow).thenReturn(window)
    app
  }

  /**
    * Obtains an ''ApplicationShutdownListener'' that has been registered at a
    * mock application.
    *
    * @param app the mock application
    * @return the registered shutdown listener
    */
  private def fetchRegisteredShutdownListener(app: Application):
  ApplicationShutdownListener = {
    val captor = ArgumentCaptor.forClass(classOf[ApplicationShutdownListener])
    verify(app).addShutdownListener(captor.capture())
    captor.getValue
  }

  /**
    * Obtains a ''WindowClosingStrategy'' that has been registered at the given
    * application's main window.
    *
    * @param app the mock application
    * @return the registered window closing strategy
    */
  private def fetchRegisteredWindowClosingStrategy(app: Application):
  WindowClosingStrategy = {
    val captor = ArgumentCaptor.forClass(classOf[WindowClosingStrategy])
    verify(app.getApplicationContext.getMainWindow).setWindowClosingStrategy(captor.capture())
    captor.getValue
  }

  "A BaseShutdownManager" should "pass an application to the right actor" in {
    val helper = new BaseShutdownManagerTestHelper
    val app = createApplicationMock()

    helper.manager addApplication app
    helper.probeShutdownManagerActor.expectMsg(ShutdownManagementActor.AddApplication(app))
  }

  it should "init an application with a shutdown listener" in {
    val helper = new BaseShutdownManagerTestHelper
    val app = createApplicationMock()

    helper.manager addApplication app
    val listener = fetchRegisteredShutdownListener(app)
    listener.canShutdown(app) shouldBe false
    helper.manager.shutdownApps should be(List(app))
  }

  it should "register a shutdown listener that ignores a shutdown callback" in {
    val helper = new BaseShutdownManagerTestHelper
    val app = createApplicationMock()

    helper.manager addApplication app
    val listener = fetchRegisteredShutdownListener(app)
    reset(app)
    listener shutdown app
    verifyZeroInteractions(app)
  }

  it should "init an application with a window closing strategy" in {
    val helper = new BaseShutdownManagerTestHelper
    val app = createApplicationMock()

    helper.manager addApplication app
    val strategy = fetchRegisteredWindowClosingStrategy(app)
    val window = app.getApplicationContext.getMainWindow
    strategy.canClose(window) shouldBe false
    helper.manager.closedWindows should be(List(window))
  }

  it should "notify the actor about a removed application" in {
    val helper = new BaseShutdownManagerTestHelper
    val app = createApplicationMock()

    helper.manager removeApplication app
    helper.probeShutdownManagerActor.expectMsg(
      ShutdownManagementActor.RemoveApplication(app))
  }

  it should "pass a shutdown listener to the right actor" in {
    val helper = new BaseShutdownManagerTestHelper
    val listener = mock[ShutdownListener]

    helper.manager addShutdownListener listener
    helper.probeShutdownListenerActor
      .expectMsg(ShutdownListenerActor.AddShutdownListener(listener))
  }

  it should "notify the actor about a removed shutdown listener" in {
    val helper = new BaseShutdownManagerTestHelper
    val listener = mock[ShutdownListener]

    helper.manager removeShutdownListener listener
    helper.probeShutdownListenerActor
      .expectMsg(ShutdownListenerActor.RemoveShutdownListener(listener))
  }

  it should "support triggering the platform's shutdown" in {
    val helper = new BaseShutdownManagerTestHelper
    val app = createApplicationMock()
    helper.manager addApplication app
    val listener = fetchRegisteredShutdownListener(app)

    helper.manager.triggerShutdown()
    helper.probeShutdownListenerActor.expectMsg(BaseShutdownActor.Process(listener))
  }

  /**
    * A test helper class managing all dependencies of the manager to be
    * tested.
    */
  private class BaseShutdownManagerTestHelper {
    /** Test probe for the shutdown manager actor. */
    val probeShutdownManagerActor = TestProbe()

    /** Test probe for the shutdown listener actor. */
    val probeShutdownListenerActor = TestProbe()

    /** A mock for the message bus. */
    val bus: MessageBus = mock[MessageBus]

    /** A mock for the application context. */
    val appContext: ClientApplicationContext =
      createApplicationContext(createActorFactory())

    /** The manager to be tested. */
    val manager: ShutdownManagerTestImpl = createShutdownManager()

    /**
      * Returns a factory that allows creating the dependency actors.
      *
      * @return the actor factory
      */
    private def createActorFactory(): ActorFactory =
      new ActorFactory(system) {
        override def createActor(props: Props, name: String): ActorRef =
          name match {
            case "shutdownApplicationManager" =>
              props.actorClass() should be(classOf[ShutdownManagementActor])
              props.args should be(List(bus, appContext))
              probeShutdownManagerActor.ref

            case "shutdownListenerManager" =>
              props.actorClass() should be(classOf[ShutdownListenerActor])
              props.args should be(List(bus, probeShutdownManagerActor.ref))
              probeShutdownListenerActor.ref
          }
      }

    /**
      * Creates a mock for the client application context.
      *
      * @param actorFactory the actor factory
      * @return the mock for the context
      */
    private def createApplicationContext(actorFactory: ActorFactory):
    ClientApplicationContext = {
      val context = mock[ClientApplicationContext]
      when(context.messageBus).thenReturn(bus)
      when(context.actorFactory).thenReturn(actorFactory)
      context
    }

    /**
      * Creates and initializes the test instance.
      *
      * @return the test instance
      */
    private def createShutdownManager(): ShutdownManagerTestImpl = {
      val man = new ShutdownManagerTestImpl
      man initApplicationContext appContext
      man.setUp()
      man
    }
  }

  private class ShutdownManagerTestImpl extends BaseShutdownManager {
    /** Stores a list with applications passed to onApplicationShutdown(). */
    var shutdownApps: List[Application] = List.empty[Application]

    /** Stores a list with windows passed to onWindowClosing(). */
    var closedWindows: List[Window] = List.empty[Window]

    /**
      * Just increase visibility.
      */
    override def triggerShutdown(): Unit = super.triggerShutdown()

    /**
      * @inheritdoc Tracks this invocation.
      */
    override protected def onApplicationShutdown(app: Application): Unit = {
      super.onApplicationShutdown(app)
      shutdownApps = app :: shutdownApps
    }

    /**
      * @inheritdoc Tracks this invocation.
      */
    override protected def onWindowClosing(window: Window): Unit = {
      super.onWindowClosing(window)
      closedWindows = window :: closedWindows
    }
  }

}
