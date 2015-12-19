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

package de.oliver_heger.linedj.browser.app

import akka.actor.ActorSystem
import de.oliver_heger.linedj.browser.config.BrowserConfig
import de.oliver_heger.linedj.client.ActorSystemTestHelper
import de.oliver_heger.linedj.client.bus.UIBus
import de.oliver_heger.linedj.client.remoting.{ActorFactory, RemoteMessageBus}
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.builder.window.Window
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

/**
 * Test class for ''BrowserApp''.
 */
class BrowserAppSpec extends FlatSpec with Matchers with BeforeAndAfterAll with MockitoSugar with
ActorSystemTestHelper {
  /** The name of the test actor system. */
  override val actorSystemName: String = "BrowserAppSpec"

  override protected def afterAll(): Unit = {
    shutdownActorSystem()
  }

  /**
   * Creates a new test application instance and starts it up. This instance
   * can then be used to test whether initialization was correctly.
   * @param mockInitUI flag whether initialization of the UI should be mocked
   * @return the instance of the application
   */
  private def createApp(mockInitUI: Boolean = true): BrowserAppTestImpl = {
    runApp(new BrowserAppTestImpl(mock[RemoteMessageBusFactory], Some(testActorSystem), mockInitUI))
  }

  /**
   * Runs the specified test application.
   * @param app the application to be started
   * @return the application
   */
  private def runApp(app: BrowserAppTestImpl): BrowserAppTestImpl = {
    Application.startup(app, Array.empty)
    app setExitHandler new Runnable {
      override def run(): Unit = {
        // do nothing
      }
    }
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
   * Executes a function on an application. It is ensured that the application
   * is shut down afterwards.
   * @param app the application
   * @param f the function to be applied
   * @return the result of the function
   */
  private def withApplication[T](app: BrowserAppTestImpl = createApp())(f: BrowserAppTestImpl =>
    T): T = {
    try f(app)
    finally app.shutdown()
  }

  "A BrowserApp" should "setup an actor system and shut it down gracefully" in {
    val application = runApp(new BrowserAppTestImpl(mock[RemoteMessageBusFactory], None,
      mockInitUI = true))
    val system = withApplication(application) { app =>
      val actorSystem = queryBean[ActorSystem](app, BrowserApp.BeanActorSystem)
      actorSystem should not be 'terminated
      actorSystem
    }
    system shouldBe 'terminated
    ActorSystemTestHelper waitForShutdown system
  }

  it should "allow passing an actor system to the constructor" in {
    val actorSystem = testActorSystem
    val system = withApplication(runApp(new BrowserAppTestImpl(mock[RemoteMessageBusFactory],
      Some(actorSystem), mockInitUI = true))) { app =>
      queryBean[ActorSystem](app, BrowserApp.BeanActorSystem)
    }
    system should be(actorSystem)
    system shouldBe 'terminated
  }

  it should "pass None for the actor system in the default constructor" in {
    val app = new BrowserApp
    app.optActorSystem shouldBe 'empty
  }

  it should "create an actor factory and store it in the bean context" in {
    withApplication() { app =>
      val actorSystem = queryBean[ActorSystem](app, BrowserApp.BeanActorSystem)
      val factory = queryBean[ActorFactory](app, BrowserApp.BeanActorFactory)
      factory.actorSystem should be(actorSystem)
    }
  }

  it should "create a bean for the BrowserConfig" in {
    withApplication() { app =>
      val config = queryBean[BrowserConfig](app, BrowserApp.BeanBrowserConfig)
      config.userConfiguration should be(app.getUserConfiguration)
    }
  }

  it should "create a default RemoteMessageBusFactory" in {
    val app = new BrowserApp
    app.remoteMessageBusFactory shouldBe a[RemoteMessageBusFactory]
  }

  it should "create a remote message bus" in {
    withApplication() { app =>
      verify(app.remoteMessageBusFactory).recreateRemoteMessageBus(app.getApplicationContext)
    }
  }

  ignore should "register message bus listeners correctly" in {
    val remoteBus = mock[RemoteMessageBus]
    val busFactory = mock[RemoteMessageBusFactory]
    val application = new BrowserAppTestImpl(busFactory, Some(testActorSystem), mockInitUI = false)
    when(application.remoteMessageBusFactory.recreateRemoteMessageBus(any(classOf[ApplicationContext]))).thenReturn(remoteBus)

    withApplication(runApp(application)) { app =>
      verify(remoteBus).activate(true)
      val uiBus = queryBean[UIBus](app, BrowserApp.BeanMessageBus)
      uiBus.busListeners.size should be > 1
    }
  }
}

/**
 * A test implementation of the main application class. This class does not
 * show the main window. It can be used to test whether the application has
 * been correctly initialized. Note that initialization of the UI is possible
 * only once; otherwise, JavaFX complains that it is already initialized.
 *
 * @param factory the remote message bus factory
 * @param actorSystem an optional actor system
 * @param mockInitUI flag whether initialization of the UI should be mocked
 */
private class BrowserAppTestImpl(factory: RemoteMessageBusFactory, actorSystem: Option[ActorSystem],
                                 mockInitUI: Boolean)
  extends BrowserApp(factory, actorSystem) {
  override def initGUI(appCtx: ApplicationContext): Unit = {
    if (!mockInitUI) {
      super.initGUI(appCtx)
    }
  }

  override def showMainWindow(window: Window): Unit = {
    // Do not show a window here
  }
}