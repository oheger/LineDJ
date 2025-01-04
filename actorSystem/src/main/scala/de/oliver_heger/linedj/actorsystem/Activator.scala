/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.actorsystem

import com.typesafe.config.ConfigFactory
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.osgi.OsgiActorSystemFactory
import org.osgi.framework.*

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

object Activator:
  /**
    * Constant for a system property that defines the name of the actor
    * system.
    */
  final val PropActorSystemName = "LineDJ_ActorSystemName"

  /**
    * Constant for a system property that defines the delay after the SpiFly
    * weaving bundle has started to wait for its completion. The property value
    * is interpreted as a number in milliseconds.
    */
  final val PropSpiFlyDelayMs = "LineDJ_SpiFlyDelayMs"

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
    Option(ref.get()).foreach { value =>
      if ref.compareAndSet(value, null.asInstanceOf[A]) then
        f(value)
    }
end Activator

/**
  * A bundle activator which creates and registers the central client-side
  * actor system as OSGi service.
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
  private val serviceRegistration = new AtomicReference[ServiceRegistration[ActorSystem]]

  override def start(context: BundleContext): Unit =
    println("Starting LineDJ ActorSystem activator.")

    executorService.set(createExecutor())
    if isSpiFlyBundleActive(context) then
      triggerDelayedRegistration(context)
    else
      println("Waiting for the start of the SpiFly dynamic weaving bundle.")
      val listener = createBundleListener(context)
      context.addBundleListener(listener)
      bundleListener.set(listener)

  override def stop(context: BundleContext): Unit =
    println("Stopping LineDJ ActorSystem activator.")
    shutdownExecutorService()
    removeBundleListener(context)
    safeCleanup(serviceRegistration)(_.unregister())

  /**
    * Returns the [[OsgiActorSystemFactory]] to create the actor system.
    *
    * @param context the current [[BundleContext]]
    * @return the factory for creating the actor system
    */
  private[actorsystem] def createActorSystemFactory(context: BundleContext): OsgiActorSystemFactory =
    OsgiActorSystemFactory(context, ConfigFactory.empty())

  /**
    * Returns a [[ScheduledExecutorService]] that is used to wait for the
    * completion of the SpiFly weaving process.
    *
    * @return the executor to wait for the configured delay
    */
  private[actorsystem] def createExecutor(): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()

  /**
    * Creates the [[BundleListener]] that waits for the start of the SpiFly
    * weaving bundle and then triggers the creation and registration of the
    * actor system.
    *
    * @param context the bundle context
    * @return the bundle listener
    */
  private def createBundleListener(context: BundleContext): BundleListener =
    (event: BundleEvent) =>
      if event.getType == BundleEvent.STARTED && event.getBundle.getSymbolicName == SpiFlyBundleName then
        triggerDelayedRegistration(context)

  /**
    * Schedules a task that creates and registers the actor system after a
    * proper delay.
    *
    * @param context the bundle context
    */
  private def triggerDelayedRegistration(context: BundleContext): Unit =
    Option(executorService.get()).foreach {
      val delay = fetchSpiFlyDelay()
      println(s"Waiting $delay ms for the completion of the SpiFly weaving process.")
      _.schedule(createRegistrationTask(context), delay, TimeUnit.MILLISECONDS)
    }

  /**
    * Returns a task for creating and registering the actor system.
    *
    * @param context the bundle context
    * @return the task
    */
  private def createRegistrationTask(context: BundleContext): Runnable =
    () =>
      val factory = createActorSystemFactory(context)
      val system = factory.createActorSystem(fetchActorSystemName())
      serviceRegistration.set(context.registerService(classOf[ActorSystem], system, null))
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
    * Determines the name of the actor system to be created. The name can be
    * specified using a system property. If it is not provided, a default name
    * is used.
    *
    * @return the name of the actor system
    */
  private def fetchActorSystemName(): String =
    getSystemProperty(PropActorSystemName) getOrElse DefaultActorSystemName

  /**
    * Determines the delay to wait for the SpiFly weaving process from the
    * corresponding system property. If it is not provided, a default delay is
    * used.
    *
    * @return the SpiFly delay (in milliseconds)
    */
  private def fetchSpiFlyDelay(): Long =
    getSystemProperty(PropSpiFlyDelayMs).map(_.toLong) getOrElse DefaultSpiFlyDelay
