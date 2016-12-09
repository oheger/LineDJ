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

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ShutdownListener}
import net.sf.jguiraffe.gui.app.{Application, ApplicationShutdownListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowClosingStrategy}

/**
  * A base trait for shutdown manager implementations.
  *
  * A ''shutdown manager'' is responsible to determine when the OSGi container
  * hosting the LineDJ platform should shutdown and actually do the shutdown.
  *
  * With multiple applications running on the platform, it is not always
  * obvious when the whole container should terminate. When the user closes an
  * application window, does this mean that only this application should be
  * closed or should the whole platform go down. To have the opportunity to
  * decide this in a flexible way, a shutdown manager has to be added to the
  * platform. The manager monitors the installed applications to find out when
  * it is time to shutdown the platform.
  *
  * There is no official interface for a shutdown manager service because this
  * functionality can fully be implemented using the whiteboard pattern: the
  * manager can track the application services available and apply means to
  * monitor them.
  *
  * This base trait implements useful functionality in this area. It already
  * contains functionality to track application and ''ShutdownListener''
  * services. (This is done using the corresponding actor implementations.) On
  * each application, it registers a special shutdown listener and a window
  * closing strategy. So it can monitor when a specific application is ended by
  * the user. It then invokes notification methods that can be overridden in
  * concrete implementations to react on these events. (The base
  * implementations are empty, so that such events are simply ignored.)
  *
  * A concrete implementation typically implements this notification methods to
  * react in a suitable way when an application is closed. For instance, an
  * application could then terminate the whole platform or just hide the window
  * of this application. This trait also offers a method that does the actual
  * shutdown of the platform (including the notification of shutdown listeners
  * with veto rights). This method can be called by derived classes when they
  * detect their specific shutdown event.
  *
  * This trait is intended to be used as a declarative services component. It
  * already provides methods to pass in or remove services it is interested in.
  * A concrete implementation just has to provide the corresponding XML
  * declaration. The thread-safety of this implementation targets this use
  * case!
  */
trait BaseShutdownManager {
  /**
    * A special shutdown listener to be registered at all installed
    * applications. This listener determines when an application is going to
    * shutdown.
    */
  private val ShutdownTracker = createShutdownListener()

  /**
    * A special window closing strategy registered at every application main
    * window to find out when the window gets closed.
    */
  private val ClosingStrategy = createWindowClosingStrategy()

  /** The shutdown manager actor. */
  private var shutdownManagerActor: ActorRef = _

  /** The shutdown listener actor. */
  private var shutdownListenerActor: ActorRef = _

  /** The application context. */
  private var applicationContext: ClientApplicationContext = _

  /**
    * Initializes the ''ClientApplicationContext''. Typically, this method is
    * invoked by the declarative services runtime.
    *
    * @param context the ''ClientApplicationContext''
    */
  def initApplicationContext(context: ClientApplicationContext): Unit = {
    applicationContext = context
  }

  /**
    * The activation method. This method must be invoked before this component
    * can be used. All required dependencies must have been set before. A
    * concrete class (implemented as a declarative service) typically defines
    * an ''activate(ComponentContext)'' method. From there, this method can be
    * called.
    */
  def setUp(): Unit = {
    shutdownManagerActor = applicationContext.actorFactory.createActor(
      Props(classOf[ShutdownManagementActor], applicationContext.messageBus,
        applicationContext), "shutdownApplicationManager")
    shutdownListenerActor = applicationContext.actorFactory.createActor(
      Props(classOf[ShutdownListenerActor], applicationContext.messageBus,
        shutdownManagerActor), "shutdownListenerManager")
  }

  /**
    * Notifies this component that an application service has been added. This
    * method is typically invoked by the declarative services runtime.
    *
    * @param app the new application service
    */
  def addApplication(app: Application): Unit = {
    app addShutdownListener ShutdownTracker
    app.getApplicationContext.getMainWindow setWindowClosingStrategy ClosingStrategy
    shutdownManagerActor ! ShutdownManagementActor.AddApplication(app)
  }

  /**
    * Notifies this component that an application service is not longer
    * available. This method is typically invoked by the declarative services
    * runtime.
    *
    * @param app the application that is gone
    */
  def removeApplication(app: Application): Unit = {
    // Note: No need to do some un-registrations; the application is destroyed
    // anyway.
    shutdownManagerActor ! ShutdownManagementActor.RemoveApplication(app)
  }

  /**
    * Notifies this component that a shutdown listener service has been
    * added. This method is typically invoked by the declarative services
    * runtime.
    *
    * @param listener the new listener service
    */
  def addShutdownListener(listener: ShutdownListener): Unit = {
    shutdownListenerActor ! ShutdownListenerActor.AddShutdownListener(listener)
  }

  /**
    * Notifies this component that a ''ShutdownListener'' service is no longer
    * available. This method is typically invoked by the declarative services
    * runtime.
    *
    * @param listener the removed listener service
    */
  def removeShutdownListener(listener: ShutdownListener): Unit = {
    shutdownListenerActor ! ShutdownListenerActor.RemoveShutdownListener(listener)
  }

  /**
    * Triggers the shutdown of the whole platform. This method can be invoked
    * by a derived class when it receives its special shutdown trigger.
    */
  protected def triggerShutdown(): Unit = {
    shutdownListenerActor ! BaseShutdownActor.Process(ShutdownTracker)
  }

  /**
    * Notifies this object that a shutdown action was triggered on the
    * specified application. This method is called by a special shutdown
    * listener that has been registered at the application. A concrete
    * implementation can react on this event. This base implementation is
    * empty.
    *
    * @param app the application affected
    */
  protected def onApplicationShutdown(app: Application): Unit = {}

  /**
    * Notifies this object that the user closed an application window. This
    * method is called by a special closing strategy that has been registered
    * at the application window. A concrete implementation can react on this
    * event. This base implementation is empty.
    *
    * @param window the window which has been closed
    */
  protected def onWindowClosing(window: Window): Unit = {}

  /**
    * Creates a shutdown listener which is registered at all installed
    * applications to track when they should be shutdown.
    *
    * @return the shutdown listener
    */
  private def createShutdownListener(): ApplicationShutdownListener =
    new ApplicationShutdownListener {
      /**
        * @inheritdoc This is just a dummy implementation.
        */
      override def shutdown(app: Application): Unit = {}

      /**
        * @inheritdoc This implementation always returns '''false''' to prevent a
        *             normal shutdown of the application. However, it invokes the
        *             ''onApplicationShutdown()'' method, so that a derived class
        *             can decide how to handle this event.
        */
      override def canShutdown(app: Application): Boolean = {
        onApplicationShutdown(app)
        false
      }
    }

  /**
    * Creates a ''WindowClosingStrategy'' that is registered at all application
    * main windows. It determines when the user closes a window and notifies
    * this object.
    *
    * @return the ''WindowClosingStrategy''
    */
  private def createWindowClosingStrategy(): WindowClosingStrategy =
    new WindowClosingStrategy {
      override def canClose(window: Window): Boolean = {
        onWindowClosing(window)
        false
      }
    }
}
