/*
 * Copyright 2015-2022 The Developers Team.
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
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus, MessageBusListener}
import de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData
import de.oliver_heger.linedj.platform.mediaifc.ext.{ArchiveAvailabilityExtension, AvailableMediaExtension, MetaDataCache, StateListenerExtension}
import de.oliver_heger.linedj.platform.mediaifc.{MediaFacade, MediaFacadeFactory}
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.builder.window.WindowManager
import org.apache.commons.configuration.Configuration
import org.apache.commons.logging.Log
import org.osgi.framework.BundleContext
import org.osgi.service.component.ComponentContext

import scala.concurrent.ExecutionContext

object ClientManagementApplication {
  /** The prefix for beans read from the bean definition file. */
  final val BeanPrefix = "LineDJ_"

  /** The name of the bean representing the message bus. */
  final val BeanMessageBus: String = BeanPrefix + "messageBus"

  /** The name of the bean representing the consumer ID factory. */
  final val BeanConsumerIDFactory: String = BeanPrefix + "consumerIDFactory"

  /** Configuration property for the meta data cache size. */
  final val PropMetaDataCacheSize = "media.cacheSize"

  /**
    * Configuration property determining the timeout for the shutdown of the
    * whole platform. When the platform goes down all currently active
    * components are given the chance to execute their specific shutdown logic,
    * e.g. to store some current data. This property defines the maximum time
    * span (in milliseconds) to wait until all components have finished their
    * shutdown. Afterwards, the platform is forced to stop.
    */
  final val PropShutdownTimeout = "platform.shutdownTimeout"

  /**
    * The default size for the meta data cache. This is the number of entries
    * the cache can hold.
    */
  final val DefaultMetaDataCacheSize = 2500

  /** The default timeout for shutting down the platform. */
  final val DefaultShutdownTimeoutMillis = 5000

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
    *
    * @param compContext the ''ComponentContext''
    * @param actorSystem the actor system
    * @return the exit handler
    */
  private def createExitHandler(compContext: ComponentContext, actorSystem: ActorSystem,
                                log: Log): Runnable = {
    () => {
      log.info("Exit handler called.")
      log.info("Stopping actor system.")
      implicit val ec: ExecutionContext = ExecutionContext.global
      actorSystem.terminate() onComplete { _ =>
        log.info("Stopping system bundle.")
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
  * The reference to the central actor system is injected by the runtime. On
  * the other hand, an instance exposes itself as ''ClientApplicationContext''
  * which is a prerequisite for other applications to start up.
  *
  * This application is also responsible for shutting down the whole platform.
  * It closely collaborates with the platform's [[ApplicationManager]] to do
  * this. It installs a message bus listener for the shutdown command, so that
  * a shutdown can be triggered externally.
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
  * To further support this programming model, some functionality is provided
  * to represent message bus listener services as OSGi services. This is useful
  * if modules depend on other modules that add such listener services. In this
  * case, the bundles can use standard OSGi mechanisms to obtain service
  * instances and receive notifications when they are available. For the same
  * purpose, a
  * [[de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors]]
  * service is registered when the actors implementing the media facade
  * interface have been retrieved.
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

  /** The shared window manager. */
  private var beanWindowManager: WindowManager = _

  /**
    * Holds the bundle context. Note: There is no need to synchronize this
    * field. It is set in the OSGi management thread at startup; from there
    * the thread to create the application is started.
    */
  private var bundleContext: BundleContext = _

  override def actorSystem: ActorSystem = system

  override def mediaFacade: MediaFacade = mediaFacadeField

  override def messageBus: MessageBus = mediaFacade.bus

  override def actorFactory: ActorFactory = factory

  override def windowManager: WindowManager = beanWindowManager

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
    *
    * @param system the actor system
    */
  def initActorSystem(system: ActorSystem): Unit = {
    log.info("Actor system was set.")
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
    log.info("MediaFacadeFactory was set.")
    mediaFacadeFactory = factory
  }

  /**
    * Activates this component and starts up the management application. This
    * method is called by the SCR. Note: It is probably not best practice to
    * start up the application in the main OSGi thread; however, it must be
    * fully initialized before other components can start.
    *
    * @param compContext the component context
    */
  def activate(compContext: ComponentContext): Unit = {
    log.info("Activating ClientManagementApplication.")
    bundleContext = compContext.getBundleContext
    setExitHandler(createExitHandler(compContext, system, log))
    startApplication(this, "management")
  }

  /**
    * @inheritdoc This implementation initializes some additional beans related
    *             to remoting.
    */
  override protected def createApplicationContext(): ApplicationContext = {
    val appCtx = super.createApplicationContext()
    beanWindowManager = extractWindowManager(appCtx)
    mediaFacadeField = createMediaFacade(appCtx)
    installOsgiServiceSupport()
    initShutdownHandling(messageBus)
    appCtx
  }

  /**
    * Creates and configures the facade to the media archive.
    *
    * @param appCtx the application context
    * @return the ''MediaFacade''
    */
  private[app] def createMediaFacade(appCtx: ApplicationContext): MediaFacade = {
    val bus = appCtx.getBeanContext.getBean(BeanMessageBus).asInstanceOf[MessageBus]
    val facade = mediaFacadeFactory.createMediaFacade(actorFactory, bus)
    facade initConfiguration appCtx.getConfiguration

    createMediaIfcExtensions(appCtx, facade) foreach (ext => bus registerListener ext.receive)
    facade activate true
    facade
  }

  /**
    * Returns a list of extensions for th
    * e media archive interface that need to
    * be registered at the message bus. The LineDJ platform offers a number of
    * default extensions supporting extended use case when interacting with the
    * media archive. These extensions are created here.
    *
    * @param appCtx the application context
    * @param facade the ''MediaFacade''
    * @return the extensions to be registered on the message bus
    */
  private[app] def createMediaIfcExtensions(appCtx: ApplicationContext, facade: MediaFacade):
  Iterable[MessageBusListener] =
    List(new ArchiveAvailabilityExtension, new StateListenerExtension(facade),
      new AvailableMediaExtension(facade),
      new MetaDataCache(facade,
        appCtx.getConfiguration.getInt(PropMetaDataCacheSize, DefaultMetaDataCacheSize)))

  /**
    * Extracts the shared window manager from the application context. This
    * window manager is then made available via the client application context,
    * so that it can be used by all active application.
    *
    * @param appCtx the application context
    * @return he window manager
    */
  private[app] def extractWindowManager(appCtx: ApplicationContext): WindowManager =
    appCtx.getBeanContext.getBean("jguiraffe.windowManager").asInstanceOf[WindowManager]

  /**
    * Installs some special message bus listeners that implement the OSGi
    * service registration support for LineDJ services.
    */
  private[app] def installOsgiServiceSupport(): Unit = {
    messageBus registerListener createServiceDependenciesManager().receive
    createFacadeServiceWrapper().activate()
  }

  /**
    * Creates the helper object for doing OSGi service registrations for LineDJ
    * services.
    *
    * @return the ''ServiceDependenciesManager''
    */
  private[app] def createServiceDependenciesManager(): ServiceDependenciesManager =
    new ServiceDependenciesManager(bundleContext)

  /**
    * Creates the helper object for registering the facade actors as OSGi
    * service.
    *
    * @return the ''MediaFacadeActorsServiceWrapper''
    */
  private[app] def createFacadeServiceWrapper(): MediaFacadeActorsServiceWrapper =
    new MediaFacadeActorsServiceWrapper(this, bundleContext)

  /**
    * Installs a message bus listener that reacts on a ''Shutdown'' command.
    * Using this mechanism the platform can be shutdown.
    *
    * @param bus the message bus
    */
  private[app] def initShutdownHandling(bus: MessageBus): Unit = {
    val shutdownHandler = new ShutdownHandler(this)
    bus registerListener shutdownHandler.receive
  }
}
