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

package de.oliver_heger.linedj.browser

import akka.actor.ActorSystem
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.builder.window.Window
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''BrowserApp''.
 */
class BrowserAppSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
   * Creates a new test application instance and starts it up. This instance
   * can then be used to test whether initialization was correctly.
   * @return the instance of the application
   */
  private def createApp(): BrowserAppTestImpl = {
    val app = new BrowserAppTestImpl(mock[RemoteMessageBusFactory])
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
    val system = withApplication() { app =>
      val actorSystem = queryBean[ActorSystem](app, BrowserApp.BeanActorSystem)
      actorSystem should not be 'terminated
      actorSystem
    }
    system shouldBe 'terminated
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
}

/**
 * A test implementation of the main application class. This class does not
 * show the main window. It can be used to test whether the application has
 * been correctly initialized.
 */
private class BrowserAppTestImpl(factory: RemoteMessageBusFactory) extends BrowserApp(factory) {
  override def initGUI(appCtx: ApplicationContext): Unit = {
    //do nothing
  }

  override def showMainWindow(window: Window): Unit = {
    // Do not show a window here
  }
}