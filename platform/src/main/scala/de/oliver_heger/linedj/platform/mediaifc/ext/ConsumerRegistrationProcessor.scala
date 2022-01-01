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

package de.oliver_heger.linedj.platform.mediaifc.ext

import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider

/**
  * A class responsible for the processing of ''ConsumerRegistration'' objects.
  *
  * This class plays an analogous role to the ''MessageBusRegistration'' class
  * for message bus listeners: The purpose is to provide a means to specify
  * providers for ''ConsumerRegistration'' objects in a declarative way (in UI
  * or bean definition scripts), so that they are processed automatically when
  * an application starts. In order to achieve this, an instance of this class
  * has to be declared in the script passing all
  * [[de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider]]
  * objects existing in the application to the constructor. The instance is
  * also injected the ''MessageBus'' bean which triggers the automatic
  * registration.
  *
  * Provided that an instance of this class is declared in the UI script for
  * an applications's main window under the name
  * ''LineDJ_consumerRegistration'', this bean is instantiated automatically,
  * and the registration of consumers is done.
  *
  * The ''removeRegistrations()'' method should be configured as ''shutdown
  * method''. It will then take care that all registrations added by this bean
  * are removed again.
  *
  * ''Note'': This implementation assumes that an instance is invoked only in
  * the UI thread. It is not thread-safe.
  *
  * @param providers a collection with the providers to process
  */
class ConsumerRegistrationProcessor(val providers: java.util.Collection[ConsumerRegistrationProvider]) {
  /** The message bus. */
  private var messageBus: MessageBus = _

  /** Stores the registrations made by this instance. */
  private var registrations = Iterable.empty[ConsumerRegistration[_]]

  /**
    * Injects the ''MessageBus'' into this bean. This triggers the processing
    * of the providers passed to the constructor. This method is typically
    * called by the dependency injection framework; so that the registration of
    * consumers happens automatically.
    *
    * @param bus the ''MessageBus''
    */
  def setMessageBus(bus: MessageBus): Unit = {
    messageBus = bus
    import scala.jdk.CollectionConverters._
    registrations = providers.asScala.flatMap(_.registrations)
    registrations foreach bus.publish
  }

  /**
    * Removes all registrations that have been added by this object. This
    * method should be referenced in a UI script as ''shutdown method''. It
    * then takes care about the removal of consumer registrations.
    */
  def removeRegistrations(): Unit = {
    registrations map(_.unRegistration) foreach messageBus.publish
  }
}
