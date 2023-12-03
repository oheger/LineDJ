/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.platform.comm.ServiceDependencies
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency, UnregisterService}
import org.mockito.Mockito._
import org.osgi.framework.{BundleContext, ServiceRegistration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

object ServiceDependenciesManagerSpec:
  /** A test dependency instance used by test cases. */
  private val TestDependency = ServiceDependency("com.test.ServiceDep")

  /**
    * Creates a map with properties needed for the OSGi service registration.
    *
    * @return the map with expected registration properties
    */
  private def serviceProperties(): util.Hashtable[String, AnyRef] =
    val expProps = new util.Hashtable[String, AnyRef]
    expProps.put(ServiceDependencies.PropertyServiceName, TestDependency.serviceName)
    expProps

/**
  * Test class for ''ServiceDependenciesManager''.
  */
class ServiceDependenciesManagerSpec extends AnyFlatSpec with MockitoSugar:

  import ServiceDependenciesManagerSpec._

  /**
    * Creates a test manager instance.
    *
    * @return the test instance
    */
  private def createManager(): ServiceDependenciesManager =
    new ServiceDependenciesManager(mock[BundleContext])

  /**
    * Expects a registration of the test service instance and returns a new
    * mock service registration object.
    *
    * @return the mock registration object
    */
  private def expectRegistration(manager: ServiceDependenciesManager):
  ServiceRegistration[ServiceDependency] =
    val reg = mock[ServiceRegistration[ServiceDependency]]
    when(manager.bundleContext.registerService(classOf[ServiceDependency], TestDependency,
      serviceProperties())).thenReturn(reg)
    reg

  "A ServiceDependenciesManager" should "register a new service" in:
    val manager = createManager()
    expectRegistration(manager)

    manager receive RegisterService(TestDependency)
    verify(manager.bundleContext).registerService(classOf[ServiceDependency], TestDependency,
      serviceProperties())

  it should "remove a service registration again" in:
    val manager = createManager()
    val registration = expectRegistration(manager)
    manager receive RegisterService(TestDependency)

    manager receive UnregisterService(TestDependency)
    verify(registration).unregister()

  it should "ignore an unregister message for an unknown service" in:
    val manager = createManager()

    manager receive UnregisterService(TestDependency)

  it should "remove a service dependency after an unregister message" in:
    val manager = createManager()
    val registration = expectRegistration(manager)
    manager receive RegisterService(TestDependency)
    manager receive UnregisterService(TestDependency)

    manager receive UnregisterService(TestDependency)
    verify(registration).unregister()
