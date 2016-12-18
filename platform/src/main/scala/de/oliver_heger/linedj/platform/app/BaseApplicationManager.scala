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

import akka.actor.Actor
import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.app.ApplicationManager.{ApplicationRegistered, ApplicationRemoved, ApplicationTitleUpdated}
import net.sf.jguiraffe.gui.app.{Application, ApplicationShutdownListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowClosingStrategy}
import org.slf4j.LoggerFactory

object BaseApplicationManager {

  /**
    * An internal message class used by [[BaseApplicationManager]] to trigger
    * the shutdown of all applications available.
    */
  private[app] case object ShutdownApplications

  /**
    * Constant for an exit handler that does nothing. This handler is set at
    * managed applications to prevent that they shutdown the whole platform.
    */
  private val DummyExitHandler = new Runnable {
    override def run(): Unit = {}
  }
}

/**
  * A base trait for ''ApplicationManager'' implementations.
  *
  * An ''application manager'' is responsible for keeping track on the list of
  * applications and related services which are currently available on the
  * platform. It also has to implement correct shutdown behavior.
  *
  * With multiple applications running on the platform, it is not always
  * obvious when the whole container should terminate. When the user closes an
  * application window, does this mean that only this application should be
  * closed or should the whole platform go down? To have the opportunity to
  * decide this in a flexible way, an application manager has been added to the
  * platform. The manager monitors the installed applications to find out when
  * it is time to shutdown the platform.
  *
  * This base trait implements useful functionality in this area. It already
  * contains functionality to track application and ''ShutdownListener''
  * services. (This is done using [[UIServiceManager]] instances.) On
  * each application, it registers a special shutdown listener and a window
  * closing strategy. So it can monitor when a specific application is ended by
  * the user. It then invokes notification methods that can be overridden in
  * concrete implementations to react on these events. (The base
  * implementations are empty, so that such events are simply ignored.)
  *
  * A concrete implementation typically overrides these notification methods to
  * react in a suitable way when an application is closed. For instance, an
  * implementation could then terminate the whole platform or just hide the window
  * of this application. This trait also offers a method that does the actual
  * shutdown of the platform (including the notification of shutdown listeners
  * with veto rights). This method can be called by derived classes when they
  * detect their specific shutdown event.
  *
  * In addition, a list of all applications currently available can be queried,
  * but this method must only be called on the event dispatch thread. In
  * general, application services need to be accessed on the event dispatch
  * thread only. Therefore, this trait uses the UI message bus to interact with
  * these services and to synchronize updates.
  *
  * This trait is intended to be used as a declarative services component. It
  * already provides methods to pass in or remove services it is interested in.
  * A concrete implementation just has to provide the corresponding XML
  * declaration. The thread-safety of this implementation targets this use
  * case!
  */
trait BaseApplicationManager extends ApplicationManager {
  import BaseApplicationManager._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

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

  /** The application context. */
  private var applicationContext: ClientApplicationContext = _

  /** Stores the service manager for applications. */
  private var fieldApplicationServiceManager: UIServiceManager[ClientApplication] = _

  /** Stores the service manager for shutdown listeners. */
  private var fieldShutdownListenerManager: UIServiceManager[ShutdownListener] = _

  /** The registration ID for the message bus listener. */
  private var messageBusRegistrationID = 0

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
    fieldApplicationServiceManager =
      UIServiceManager[ClientApplication](classOf[ClientApplication],
      applicationContext.messageBus)
    fieldShutdownListenerManager =
      UIServiceManager[ShutdownListener](classOf[ShutdownListener],
        applicationContext.messageBus)

    messageBusRegistrationID =
      applicationContext.messageBus registerListener messageBusListener
  }

  /**
    * The deactivation method. This method must be invoked when this instance
    * is no longer used. A concrete class (implemented as a declarative
    * service) typically defines a ''deactivate(ComponentContext)'' method.
    * From there, this method can be called.
    */
  def tearDown(): Unit = {
    applicationContext.messageBus removeListener messageBusRegistrationID
    applicationServiceManager.shutdown()
    shutdownListenerManager.shutdown()
  }

  /**
    * @inheritdoc Adds the application to the internal manager. Also sends a
    *             notification message on the message bus.
    */
  override def registerApplication(app: ClientApplication): Unit = {
    applicationServiceManager.addService(app, Some(adaptApplication))
    log.info("Added application {}.", app)
    publishMessage(ApplicationRegistered(app))
  }

  /**
    * @inheritdoc This implementation sends a corresponding notification on the
    *             UI message bus.
    */
  override def applicationTitleUpdated(app: ClientApplication, title: String): Unit = {
    publishMessage(ApplicationTitleUpdated(app, title))
  }

  /**
    * @inheritdoc This implementation delegates to the internal application
    *             service manager.
    */
  override def getApplications: Iterable[ClientApplication] =
    applicationServiceManager.services

  /**
    * @inheritdoc This implementation fetches the applications and then queries
    *             the main windows for their titles.
    */
  override def getApplicationsWithTitles: Iterable[(ClientApplication, String)] =
    getApplications map (a => (a, a.getApplicationContext.getMainWindow.getTitle))

  /**
    * Notifies this component that an application service is not longer
    * available. This method is typically invoked by the declarative services
    * runtime.
    *
    * @param app the application that is gone
    */
  def removeApplication(app: ClientApplication): Unit = {
    // Note: No need to do some un-registrations; the application is destroyed
    // anyway.
    applicationServiceManager removeService app
    publishMessage(ApplicationRemoved(app))
  }

  /**
    * Notifies this component that a shutdown listener service has been
    * added. This method is typically invoked by the declarative services
    * runtime.
    *
    * @param listener the new listener service
    */
  def addShutdownListener(listener: ShutdownListener): Unit = {
    shutdownListenerManager addService listener
    log.info("Added ShutdownListener service {}.", listener)
  }

  /**
    * Notifies this component that a ''ShutdownListener'' service is no longer
    * available. This method is typically invoked by the declarative services
    * runtime.
    *
    * @param listener the removed listener service
    */
  def removeShutdownListener(listener: ShutdownListener): Unit = {
    shutdownListenerManager removeService listener
  }

  /**
    * Triggers the shutdown of the whole platform. This method can be invoked
    * by a derived class when it receives its special shutdown trigger.
    */
  protected def triggerShutdown(): Unit = {
    log.info("Shutdown triggered.")
    shutdownListenerManager processServices processShutdownListeners
  }

  /**
    * Publishes the specified message on the UI message bus.
    *
    * @param msg the message to be published
    */
  protected def publishMessage(msg: Any): Unit = {
    applicationContext.messageBus publish msg
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
    * A message listener function that can be overridden in derived classes.
    * This trait registers a listener function at the UI message bus for
    * processing some internal messages. A derived class can add its own
    * message processing by overriding this method; it is invoked before the
    * specific message processing implemented by this trait. This base
    * implementation is empty.
    *
    * @return a custom message processing function
    */
  protected def onMessage: Receive = Actor.emptyBehavior

  /**
    * Returns the service manager for managing application services.
    *
    * @return the application service manager
    */
  private[app] def applicationServiceManager: UIServiceManager[ClientApplication] =
    fieldApplicationServiceManager

  /**
    * Returns the service manager for shutdown listener services.
    *
    * @return the shutdown listener service manager
    */
  private[app] def shutdownListenerManager: UIServiceManager[ShutdownListener] =
    fieldShutdownListenerManager

  /**
    * Returns the message bus listener function. This function is
    * registered at the message bus on startup time. It is a combination of a
    * function which can be defined by derived classes and an internal message
    * processing function.
    *
    * @return the message bus listener function
    */
  private def messageBusListener: Receive = onMessage orElse messageProcessing

  /**
    * The internal message processing function used by this trait.
    *
    * @return the internal message bus listener function
    */
  private def messageProcessing: Receive = {
    case ShutdownApplications =>
      applicationServiceManager processServices processApplications
  }

  /**
    * Adapts an application that is added to this manager. This method
    * registers the listeners monitoring shutdown at the specified application.
    * It also sets some properties to make sure that the application behaves
    * correctly.
    *
    * @param app the application
    * @return the same application
    */
  private def adaptApplication(app: ClientApplication): ClientApplication = {
    app addShutdownListener ShutdownTracker
    app.getApplicationContext.getMainWindow setWindowClosingStrategy ClosingStrategy
    app setExitHandler DummyExitHandler
    app
  }

  /**
    * The processing function for shutdown listeners. Invokes each listener.
    * If there is no veto, an internal message triggering application shutdown
    * is returned.
    *
    * @param listeners the shutdown listeners
    * @return optional shutdown message
    */
  private def processShutdownListeners(listeners: Iterable[ShutdownListener]): Option[Any] = {
    if (listeners forall (_.onShutdown()))
      Some(ShutdownApplications)
    else None
  }

  /**
    * The processing function for applications. This function removes the
    * special monitoring shutdown listener from the application and then calls
    * its ''shutdown()'' method. It returns a message to shutdown the platform.
    *
    * @param apps the applications
    * @return the platform shutdown message
    */
  private def processApplications(apps: Iterable[Application]): Option[Any] = {
    apps foreach { a =>
      a removeShutdownListener ShutdownTracker
      a.shutdown()
    }
    Some(ClientManagementApplication.Shutdown(applicationContext))
  }

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
