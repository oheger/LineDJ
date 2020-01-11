/*
 * Copyright 2015-2020 The Developers Team.
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

import java.util

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.comm.{MessageBusListener, ServiceDependencies}
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService,
ServiceDependency, UnregisterService}
import org.osgi.framework.{BundleContext, ServiceRegistration}

/**
  * An internally used class responsible for updating OSGi service
  * registrations for LineDJ platform services.
  *
  * This class is listening on the message bus for requests to add or remove a
  * service registration. It updates the OSGi service registry accordingly.
  *
  * @param bundleContext the bundle context
  */
private class ServiceDependenciesManager(val bundleContext: BundleContext)
  extends MessageBusListener {
  /** A map with service registrations managed by this instance. */
  private var registrations = Map.empty[ServiceDependency, ServiceRegistration[ServiceDependency]]

  override def receive: Receive = {
    case RegisterService(s@ServiceDependency(name)) =>
      val props = new util.Hashtable[String, AnyRef]
      props.put(ServiceDependencies.PropertyServiceName, name)
      val reg = bundleContext.registerService(classOf[ServiceDependency], s, props)
      registrations += s -> reg

    case UnregisterService(dep) =>
      registrations.get(dep) foreach { reg =>
        reg.unregister()
        registrations -= dep
      }
  }
}
