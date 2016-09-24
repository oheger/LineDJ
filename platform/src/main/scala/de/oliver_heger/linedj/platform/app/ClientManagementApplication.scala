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

import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData
import de.oliver_heger.linedj.platform.mediaifc.{MediaFacade, MediaFacadeFactory}
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{JavaFxWindowManager, StageFactory}
import org.apache.commons.configuration.Configuration
import org.osgi.service.component.ComponentContext

import scala.annotation.tailrec

object ClientManagementApplication {
  /** The name of the bean representing the message bus. */
  val BeanMessageBus = "lineDJ_messageBus"

  /** Configuration property for the server address. */
  val PropServerAddress = "remote.server.address"

  /** Configuration property for the server port. */
  val PropServerPort = "remote.server.port"

  /** The name of the remote relay actor. */
  val RelayActorName = "RemoteRelayActor"

  /** Constant for the default server address. */
  val DefaultServerAddress = "127.0.0.1"

  /** Constant for the default server port. */
  val DefaultServerPort = 2552

  /**
    * A message published by [[ClientManagementApplication]] on the UI message
    * bus when there is a change in the configuration of the interface to the
    * media archive. The message contains the configuration available or
    * ''None'' if no configuration service is registered.
    *
    * @param currentConfigData an option with the currently available config
    *                          service
    */
  case class MediaIfcConfigUpdated(currentConfigData: Option[MediaIfcConfigData])

  /**
    * Creates an exit handler for shutting down this application in an OSGi
    * context.
    * @param compContext the ''ComponentContext''
    * @return the exit handler
    */
  private def createExitHandler(compContext: ComponentContext): Runnable = {
    new Runnable {
      override def run(): Unit = {
        val systemBundle = compContext.getBundleContext.getBundle(0)
        systemBundle.stop()
      }
    }
  }
}

/**
  * An application which represents the LineDJ client platform in an OSGi
  * environment.
  *
  * This is a non-visual JGUIraffe application (i.e. it does not define a main
  * window). Its task is to create the central [[ClientApplicationContext]] and
  * to register it as an OSGi service. This allows sub applications -
  * implemented as OSGi bundles - to startup and connect with the platform. So
  * a loosely coupled, extensible application is created from an arbitrary
  * number of sub applications.
  *
  * To achieve its tasks, this class is a ''declarative services'' component.
  * The reference to the central actor system is injected by the runtime; also
  * notifications about registered applications are received. On the other
  * hand, an instance exposes itself as ''ClientApplicationContext'' which is
  * a prerequisite for other applications to start up.
  *
  * When a new sub application starts it is passed to this object by the SCR
  * (service component runtime). Then a shutdown listener can be registered,
  * so that the application takes part in shutdown management: Closing one of
  * the sub application windows causing the whole client platform to shutdown.
  * This is also controlled by this management application. To achieve this,
  * it registers itself as shutdown listener at all managed sub applications.
  * When the first application shuts down all other sub applications are sent
  * a shutdown message, and the container is terminated. Note: This requires
  * that all sub applications have dummy exit handlers which do not actually
  * exit the virtual machine.
  *
  * Another task of this application is to establish a connection to the media
  * archive. This is done by declaring a dependency to a
  * [[de.oliver_heger.linedj.platform.mediaifc.MediaFacadeFactory]] service.
  * From this service a new facade instance is created and made available via
  * the [[ClientApplicationContext]]. It may be possible that access to the
  * media archive requires configuration; in this case, the responsible bundle
  * can register a service of type
  * [[de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData]]. This
  * class monitors services of this type and sends notifications on the UI
  * message bus when configuration data becomes available or unavailable.
  *
  * Via the OSGi runtime the communication between all involved application
  * objects takes part in a loosely coupled way without direct interaction.
  */
class ClientManagementApplication extends Application with
ClientApplicationContext with ApplicationSyncStartup {
  import ClientManagementApplication._

  /**
    * Holds a reference to the current ''MediaIfcConfigData'' service. This
    * field is accessed from the OSGi management thread; therefore, special
    * care for its synchronization has to be taken.
    */
  private val refMediaIfcConfig = new AtomicReference[MediaIfcConfigData]

  /** Stores the central actor system. */
  private var system: ActorSystem = _

  /** The actor factory. */
  private var factory: ActorFactory = _

  /** The media facade factory. */
  private var mediaFacadeFactory: MediaFacadeFactory = _

  /** Stores the media facade. */
  private var mediaFacadeField: MediaFacade = _

  /** The central stage factory. */
  private var beanStageFactory: StageFactory = _

  /** A list with the currently registered client applications. */
  private val registeredClients = new AtomicReference(List.empty[Application])

  /** Flag whether the application is currently shutting down. */
  private var shutdownInProgress = false

  override def actorSystem: ActorSystem = system

  override def mediaFacade: MediaFacade = mediaFacadeField

  override def messageBus: MessageBus = mediaFacade.bus

  override def actorFactory: ActorFactory = factory

  override def stageFactory: StageFactory = beanStageFactory

  override def mediaIfcConfig: Option[MediaIfcConfigData] = Option(refMediaIfcConfig.get())

  override def managementConfiguration: Configuration = getUserConfiguration

  /**
    * Sets a service reference of type ''MediaIfcConfigData''. This method is
    * called by the SCR when such a service is detected.
    *
    * @param configData the ''MediaIfcConfigData'' service
    */
  def setMediaIfcConfig(configData: MediaIfcConfigData): Unit = {
    refMediaIfcConfig set configData
    messageBus publish MediaIfcConfigUpdated(Some(configData))
  }

  /**
    * Notifies this object that a ''MediaIfcConfigData'' service is no longer
    * available. This method is called by the SCR when a service registration
    * is removed from the OSGi registry. This method sends a corresponding
    * update notification.
    *
    * @param configData the ''MediaIfcConfigData'' service that was removed
    */
  def unsetMediaIfcConfig(configData: MediaIfcConfigData): Unit = {
    if (refMediaIfcConfig.compareAndSet(configData, null)) {
      messageBus publish MediaIfcConfigUpdated(None)
    }
  }

  /**
    * Initializes the central actor system. This method is called by the SCR.
    * @param system the actor system
    */
  def initActorSystem(system: ActorSystem): Unit = {
    this.system = system
    factory = new ActorFactory(system)
  }

  /**
    * Initializes the ''MediaFacadeFactory'' service. This method is called by
    * the SCR.
    *
    * @param factory the factory
    */
  def initMediaFacadeFactory(factory: MediaFacadeFactory): Unit = {
    mediaFacadeFactory = factory
  }

  /**
    * Activates this component and starts up the management application. This
    * method is called by the SCR. Note: It is probably not best practice to
    * start up the application in the main OSGi thread; however, it must be
    * fully initialized before other components can start.
    * @param compContext the component context
    */
  def activate(compContext: ComponentContext): Unit = {
    setExitHandler(createExitHandler(compContext))
    startApplication(this, "management")
  }

  /**
    * Adds a client application to this management application. This method is
    * called by the SCR whenever an application is registered. Such
    * applications are then management by this object.
    * @param app the client application
    */
  def addClientApplication(app: Application): Unit = {
    updateRegisteredApps(app :: _)
    app setExitHandler createClientApplicationExitHandler(app)
  }

  /**
    * Removes a client application from this management application. This
    * method is called by the SCR when an application is uninstalled.
    * @param app the client application to be removed
    */
  def removeClientApplication(app: Application): Unit = {
    updateRegisteredApps(_ filterNot (_ == app))
  }

  /**
    * Returns a sequence with the currently registered client applications.
    * @return a sequence with all client applications
    */
  def clientApplications: Seq[Application] = registeredClients.get()

  /**
    * @inheritdoc This implementation initializes some additional beans related
    *             to remoting.
    */
  override protected def createApplicationContext(): ApplicationContext = {
    val appCtx = super.createApplicationContext()
    beanStageFactory = extractStageFactory(appCtx)
    mediaFacadeField = createMediaFacade(appCtx)
    appCtx
  }

  /**
    * Creates and configures the facade to the media archive.
    * @param appCtx the application context
    * @return the ''MediaFacade''
    */
  private[app] def createMediaFacade(appCtx: ApplicationContext): MediaFacade = {
    val bus = appCtx.getBeanContext.getBean(BeanMessageBus).asInstanceOf[MessageBus]
    val facade = mediaFacadeFactory.createMediaFacade(actorFactory, bus)
    facade initConfiguration appCtx.getConfiguration
    facade activate true
    facade
  }

  /**
    * Extracts the stage factory bean from the application context. The stage
    * factory is obtained from the window manager, which is expected to be of
    * type ''JavaFxWindowManager''.
    * @param appCtx the application context
    * @return the ''StageFactory''
    */
  private[app] def extractStageFactory(appCtx: ApplicationContext): StageFactory = {
    val windowManager = appCtx.getBeanContext.getBean("jguiraffe.windowManager")
      .asInstanceOf[JavaFxWindowManager]
    windowManager.stageFactory
  }

  /**
    * Triggers a shutdown operation of the whole LineDJ client application on
    * behalf of the specified application. This method is eventually called
    * when a client application was closed. It ensures that all other client
    * applications are correctly shut down and then shuts down this
    * application.
    * @param app the client application that was closed
    */
  private def triggerShutdown(app: Application): Unit = {
    if (!shutdownInProgress) {
      shutdownInProgress = true
      clientApplications filterNot (_ == app) foreach (_.shutdown())
      shutdown()
    }
  }

  /**
    * Creates an exit handler for a client application. This handler notifies
    * this management application about the close operation. This will in turn
    * shutdown the whole LineDJ client platform.
    * @param app the affected client application
    * @return the exit handler for this client application
    */
  private def createClientApplicationExitHandler(app: Application): Runnable =
    new Runnable {
      override def run(): Unit = {
        triggerShutdown(app)
      }
    }

  /**
    * Updates the list with registered applications in a thread-safe manner
    * using the provided update function.
    * @param f the update function
    */
  @tailrec
  private def updateRegisteredApps(f: List[Application] => List[Application]): Unit = {
    val list = registeredClients.get()
    val newList = f(list)
    if (!registeredClients.compareAndSet(list, newList)) updateRegisteredApps(f)
  }
}