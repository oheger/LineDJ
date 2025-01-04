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

package de.oliver_heger.linedj.platform.comm

/**
  * An object providing functionality related to dependencies to LineDJ
  * platform services.
  *
  * With the classes and functions defined by this object it is possible to
  * mechanisms of the OSGi platform to lookup service dependencies. Refer to
  * the documentation of nested elements for further information.
  */
object ServiceDependencies:
  /**
    * The property used in OSGi service registrations or filters to define the
    * name of a service.
    */
  val PropertyServiceName = "serviceName"

  /**
    * A class representing a dependency to a service.
    *
    * The idea behind this class is to combine the OSGi service registration
    * mechanism with dependencies to services offered by extensions of the
    * LineDJ platform.
    *
    * Platform functionality is often provided in a loosely coupled way via
    * listeners on the message bus. As long as this functionality is offered by
    * core platform components, this is not a problem because client code
    * depending on the platform can be sure that the services are up and running
    * when it is activated.
    *
    * Situation is more complex when extension modules are involved that also
    * register message bus listeners. Per default, there is no defined order in
    * which modules are started; so it is possible that a consumer module is
    * started before the module that provides the desired functionality. In such
    * a constellation requests sent on the message bus may silently get lost.
    *
    * As a remedy, the message bus could be extended to manage dependencies in
    * some way. It could offer an API and a notification mechanism to check
    * whether specific services are already registered. However, this is exactly
    * what the OSGi service registry does.
    *
    * The core platform therefore offers a mechanism - by sending a specific
    * message on the bus - to announce that a service is now available. This
    * causes a registration of a service object of this class with a special
    * service name property to be registered in the OSGi registry. Other
    * modules can thus use the typical OSGi mechanisms to check for the presence
    * of this service. For instance, they could be implemented as declarative
    * services components with a mandatory reference to such a service object.
    * Then the DS runtime would ensure that the component is only started when
    * this service is available. This has the additional benefit that the
    * component would automatically be deactivated when the service is no longer
    * available. So, rather then re-implementing dependency management logic in
    * the platform, the mechanisms offered out-of-the-box by OSGi are used.
    *
    * @param serviceName the name of the represented service; this must be a name
    *                    unique in the LineDJ service landscape
    */
  case class ServiceDependency(serviceName: String)

  /**
    * A message class triggering a service registration.
    *
    * Sending a message of this type on the message bus indicates that the
    * referenced service is now available. This causes a corresponding service
    * registration in the OSGi registry, so that depending components can
    * detect the service newly available.
    *
    * Note that only a single service instance per name is supported. This
    * mechanism is intended for platform extension services offering specific
    * functionality.
    *
    * @param service the service to be registered
    */
  case class RegisterService(service: ServiceDependency)

  /**
    * A message class triggering that a service registration is removed again.
    *
    * Sending a message of this type on the message bus indicates that the
    * referenced service is no longer available. An OSGi service registration
    * created for it is now removed again.
    *
    * @param service the service affected
    */
  case class UnregisterService(service: ServiceDependency)

  /**
    * Generates a LDAP filter string for a service of the specified name.
    * This filter condition - if needed with additional criteria, e.g. for the
    * service class name - can be used to search for LineDJ platform services
    * that have been registered via the mechanisms offered by this object.
    *
    * @param name the name of the service in question
    * @return a filter string selecting exactly this service name
    */
  def serviceNameFilter(name: String): String =
    s"($PropertyServiceName=$name)"
