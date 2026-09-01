/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.startup.impl

import com.typesafe.config.ConfigFactory
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.apache.commons.configuration2.{BaseHierarchicalConfiguration, ImmutableHierarchicalConfiguration}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.osgi.OsgiActorSystemFactory
import org.osgi.framework.*

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.util.{Failure, Success, Try}

object Activator:
  /**
    * Constant for a system property that defines the name or path to the
    * configuration file with settings for the LineDJ platform. The activator
    * loads this configuration file and exposes the settings via a service.
    */
  final val PropConfigFile = "LineDJ_PlatformConfigFile"

  /**
    * The name of the section in the platform configuration that contains the
    * platform-related properties.
    */
  final val PlatformSection = "platform"

  /**
    * Constant for the configuration property that defines the name of the
    * actor system.
    */
  final val PropActorSystemName = "actorSystemName"

  /**
    * Constant for the configuration property that defines the delay after the
    * SpiFly weaving bundle has started to wait for its completion. The
    * property value is interpreted as a number in milliseconds.
    */
  final val PropSpiFlyDelayMs = "spiFlyDelayMs"

  /** The default name of the actor system. */
  final val DefaultActorSystemName = "LineDJ_PlatformActorSystem"

  /** The default delay for waiting for the SpiFly weaving process. */
  final val DefaultSpiFlyDelay = 500

  /** The name of the SpiFly dynamic weaving bundle. */
  private val SpiFlyBundleName = "org.apache.aries.spifly.dynamic.bundle"

  /**
    * Checks whether the SpiFly bundle is already running.
    *
    * @param context the bundle context
    * @return a flag whether the SpiFly bundle is active
    */
  private def isSpiFlyBundleActive(context: BundleContext): Boolean =
    context.getBundles.exists { bundle =>
      bundle.getState == Bundle.ACTIVE && bundle.getSymbolicName == SpiFlyBundleName
    }

  /**
    * Performs a safe cleanup of a resource in an [[AtomicReference]]. Checks
    * whether the reference contains a value. If this is the case, and it has
    * not been cleaned up concurrently (which could theoretically happen if the
    * activator's ''stop()'' method is called at the same time when the SpiFly
    * delay is reached), then the given cleanup function is called which should
    * free the resource.
    *
    * @param ref the [[AtomicReference]] to be cleaned up
    * @param f   the cleanup function
    * @tparam A the type of the value to be cleaned up
    */
  private def safeCleanup[A](ref: AtomicReference[A])(f: A => Unit): Unit =
    Option(ref.get()).foreach: value =>
      if ref.compareAndSet(value, null.asInstanceOf[A]) then
        f(value)

  /**
    * Tries to load the platform configuration file from the given name.
    * Creates a service object that exposes this file if the load operation was
    * successful.
    *
    * @param name the name of the configuration file
    * @return a [[Try]] with the loaded configuration
    */
  private def loadPlatformConfigFile(name: String): Try[ConfigService] = Try:
    val ccl = Thread.currentThread().getContextClassLoader
    val platformConfig = try
      // Changing the CCL is necessary, since commons-configuration does a dynamic class loading
      // for the result class of the builder. This fails in the OSGi environment unless a correct
      // classloader is set.
      Thread.currentThread().setContextClassLoader(getClass.getClassLoader)
      val configs = new Configurations
      configs.xml(name)
    finally
      Thread.currentThread().setContextClassLoader(ccl)

    createConfigService(platformConfig)

  /**
    * Creates a [[ConfigService]] that exposes the given configuration.
    *
    * @param configuration the configuration to expose
    * @return the service
    */
  private def createConfigService(configuration: ImmutableHierarchicalConfiguration): ConfigService =
    new ConfigService:
      override val config: ImmutableHierarchicalConfiguration = configuration
end Activator

/**
  * A bundle activator which creates and registers the central client-side
  * actor system and the platform configuration as OSGi services.
  *
  * This class uses functionality provided by the Pekko OSGi integration to
  * correctly set up an actor system in an OSGi environment. Actually, the
  * functionality provided by Pekko would be sufficient for the use case at
  * hand. However, there is currently one problem with logging:
  *
  * Pekko uses slf4j 2.x as logging facade. This library uses a service loader
  * approach to find a logger implementation. For this to work, the dynamic
  * weaving bundle of Apache Aries SpiFly must be active first; otherwise, the
  * service loader does not yield any services, causing logging to be disabled
  * for the actor system. To prevent this, this activator implementation
  * contains logic which checks whether the weaving bundle is already active or
  * waits until it gets started. Then it waits for another configurable time
  * span to make sure that the bundle has done its work. Only then it is safe
  * to create the actor system.
  *
  * The actor system is then registered as an OSGi service. Some components
  * have a dependency on this actor system. They can start automatically as
  * soon as this object becomes available.
  *
* In addition, this class registers a [[ConfigService]] that exposes the
   * platform configuration. If a system property defines the name or path of
   * a platform configuration file and this file can be loaded, the resulting
   * configuration is exposed. Otherwise, an empty configuration is exposed, so
   * that clients always find a config service and can use default settings.
   */
class Activator extends BundleActivator with SystemPropertyAccess:

  import Activator.*

  /** Stores the executor service. */
  private val executorService = new AtomicReference[ScheduledExecutorService]

  /** Stores the registered bundle listener. */
  private val bundleListener = new AtomicReference[BundleListener]

  /**
    * Stores the registration for the actor system service, so that it can be
    * unregistered when the bundle is stopped.
    */
  private val actorSystemRegistration = new AtomicReference[ServiceRegistration[ActorSystem]]

  /**
    * Stores the registration for the config service. The service is always
    * registered when the activator is started.
    */
  private val configRegistration = new AtomicReference[ServiceRegistration[ConfigService]]

  override def start(context: BundleContext): Unit =
    println("Starting LineDJ ActorSystem activator.")

    val configService = fetchConfigService
    configRegistration.set(context.registerService(classOf[ConfigService], configService, null))
    val platformConfig = fetchPlatformConfig(configService)

    executorService.set(createExecutor())
    if isSpiFlyBundleActive(context) then
      triggerDelayedRegistration(context, platformConfig)
    else
      println("Waiting for the start of the SpiFly dynamic weaving bundle.")
      val listener = createBundleListener(context, platformConfig)
      context.addBundleListener(listener)
      bundleListener.set(listener)

  override def stop(context: BundleContext): Unit =
    println("Stopping LineDJ ActorSystem activator.")
    shutdownExecutorService()
    removeBundleListener(context)
    safeCleanup(actorSystemRegistration)(_.unregister())
    safeCleanup(configRegistration)(_.unregister())

  /**
    * Returns the [[OsgiActorSystemFactory]] to create the actor system.
    *
    * @param context the current [[BundleContext]]
    * @return the factory for creating the actor system
    */
  private[impl] def createActorSystemFactory(context: BundleContext): OsgiActorSystemFactory =
    OsgiActorSystemFactory(context, ConfigFactory.empty())

  /**
    * Returns a [[ScheduledExecutorService]] that is used to wait for the
    * completion of the SpiFly weaving process.
    *
    * @return the executor to wait for the configured delay
    */
  private[impl] def createExecutor(): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()

  /**
    * Creates the [[BundleListener]] that waits for the start of the SpiFly
    * weaving bundle and then triggers the creation and registration of the
    * actor system.
    *
    * @param context the bundle context
    * @param config  the platform configuration
    * @return the bundle listener
    */
  private def createBundleListener(context: BundleContext,
                                   config: ImmutableHierarchicalConfiguration): BundleListener =
    (event: BundleEvent) =>
      if event.getType == BundleEvent.STARTED && event.getBundle.getSymbolicName == SpiFlyBundleName then
        triggerDelayedRegistration(context, config)

  /**
    * Schedules a task that creates and registers the actor system after a
    * proper delay.
    *
    * @param context the bundle context
    * @param config  the platform configuration
    */
  private def triggerDelayedRegistration(context: BundleContext, config: ImmutableHierarchicalConfiguration): Unit =
    Option(executorService.get()).foreach {
      val delay = fetchSpiFlyDelay(config)
      println(s"Waiting $delay ms for the completion of the SpiFly weaving process.")
      _.schedule(createRegistrationTask(context, config), delay, TimeUnit.MILLISECONDS)
    }

  /**
    * Returns a task for creating and registering the actor system.
    *
    * @param context the bundle context
    * @param config  the platform configuration
    * @return the task
    */
  private def createRegistrationTask(context: BundleContext, config: ImmutableHierarchicalConfiguration): Runnable =
    () =>
      val factory = createActorSystemFactory(context)
      val system = factory.createActorSystem(fetchActorSystemName(config))
      actorSystemRegistration.set(context.registerService(classOf[ActorSystem], system, null))
      shutdownExecutorService()
      removeBundleListener(context)

  /**
    * Makes sure that the managed executor service is shutdown exactly once.
    */
  private def shutdownExecutorService(): Unit =
    safeCleanup(executorService)(_.shutdownNow())

  /**
    * Removes the [[BundleListener]] used by this instance if it exists.
    *
    * @param context the bundle context
    */
  private def removeBundleListener(context: BundleContext): Unit =
    safeCleanup(bundleListener)(context.removeBundleListener)

  /**
    * Creates the [[ConfigService]] to be registered by this activator. If a
    * configuration file is defined in the system properties and can be loaded,
    * a service exposing the resulting configuration is created. Otherwise, a
    * service with an empty configuration is created, so that it is always
    * available.
    *
    * @return the [[ConfigService]]
    */
  private def fetchConfigService: ConfigService =
    getSystemProperty(PropConfigFile) match
      case Some(name) =>
        loadPlatformConfigFile(name) match
          case Failure(exception) =>
            println(s"Failed to load platform configuration file '$name'.")
            exception.printStackTrace()
            createConfigService(new BaseHierarchicalConfiguration)
          case Success(value) =>
            value
      case None =>
        createConfigService(new BaseHierarchicalConfiguration)

  /**
    * Obtains the section with the platform configuration from the given
    * config service. If the configuration does not have a platform section,
    * an empty configuration is returned, so that default values for all
    * properties are used.
    *
    * @param configService the [[ConfigService]]
    * @return the platform configuration
    */
  private def fetchPlatformConfig(configService: ConfigService): ImmutableHierarchicalConfiguration =
    Try(configService.config.immutableConfigurationAt(PlatformSection)).toOption
      .getOrElse(new BaseHierarchicalConfiguration)

  /**
    * Determines the name of the actor system to be created from the given
    * configuration. If it is not provided, a default name is used.
    *
    * @return the name of the actor system
    */
  private def fetchActorSystemName(config: ImmutableHierarchicalConfiguration): String =
    config.getString(PropActorSystemName, DefaultActorSystemName)

  /**
    * Determines the delay to wait for the SpiFly weaving process from the
    * corresponding configuration property. If it is not provided, a default
    * delay is used.
    *
    * @param config the platform configuration
    * @return the SpiFly delay (in milliseconds)
    */
  private def fetchSpiFlyDelay(config: ImmutableHierarchicalConfiguration): Long =
    config.getLong(PropSpiFlyDelayMs, DefaultSpiFlyDelay)
