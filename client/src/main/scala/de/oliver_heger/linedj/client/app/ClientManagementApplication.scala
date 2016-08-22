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

import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import de.oliver_heger.linedj.client.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.client.mediaifc.RemoteMessageBus
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{JavaFxWindowManager, StageFactory}
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
  * Via the OSGi runtime the communication between all involved application
  * objects takes part in a loosely coupled way without direct interaction.
  *
  * @param remoteMessageBusFactory a factory for the remote message bus
  */
class ClientManagementApplication(private[app] val remoteMessageBusFactory:
                                  RemoteMessageBusFactory) extends Application with
ClientApplicationContext {

  import ClientManagementApplication._

  /** Stores the central actor system. */
  private var system: ActorSystem = _

  /** The actor factory. */
  private var factory: ActorFactory = _

  /** The remote message bus. */
  private var remoteBus: RemoteMessageBus = _

  /** The central stage factory. */
  private var beanStageFactory: StageFactory = _

  /** A list with the currently registered client applications. */
  private val registeredClients = new AtomicReference(List.empty[Application])

  /** Flag whether the application is currently shutting down. */
  private var shutdownInProgress = false

  /**
    * Creates a new instance of ''ClientManagementApplication'' with default
    * settings.
    */
  def this() = this(new RemoteMessageBusFactory)

  override def actorSystem: ActorSystem = system

  override def remoteMessageBus: RemoteMessageBus = remoteBus

  override def messageBus: MessageBus = remoteMessageBus.bus

  override def actorFactory: ActorFactory = factory

  override def stageFactory: StageFactory = beanStageFactory

  /**
    * Initializes the central actor system. This method is called by the SCR.
    * @param system the actor system
    */
  def initActorSystem(system: ActorSystem): Unit = {
    this.system = system
    factory = new ActorFactory(system)
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
    Application.startup(this, Array.empty)
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
    remoteBus = createRemoteMessageBus(appCtx)
    appCtx
  }

  /**
    * Creates and configures the remote message bus.
    * @param appCtx the application context
    * @return the remote message bus
    */
  private[app] def createRemoteMessageBus(appCtx: ApplicationContext): RemoteMessageBus = {
    val bus = appCtx.getBeanContext.getBean(BeanMessageBus).asInstanceOf[MessageBus]
    val remoteBus = remoteMessageBusFactory.createRemoteMessageBus(actorFactory, bus)
    val address = appCtx.getConfiguration.getString(PropServerAddress, DefaultServerAddress)
    val port = appCtx.getConfiguration.getInt(PropServerPort, DefaultServerPort)
    remoteBus.updateConfiguration(address, port)
    remoteBus activate true
    remoteBus
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
