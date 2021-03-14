/*
 * Copyright 2015-2021 The Developers Team.
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

import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider

import scala.reflect.ClassTag

/**
  * An object providing some functionality for testing classes that implement
  * ''ConsumerRegistrationProvider''.
  */
object ConsumerRegistrationProviderTestHelper {
  /**
    * Obtains a consumer registration of the specified type from the given
    * iterable. Throws an exception if no such registration can be found.
    *
    * @param messages the list of messages to be searched
    * @param t        the class tag
    * @tparam T the type of the desired registration
    * @return the registration object
    */
  def findRegistration[T <: ConsumerRegistration[_]](messages: Iterable[Any])
                                                    (implicit t: ClassTag[T]): T = {
    val optReg = messages.find(t.runtimeClass == _.getClass)
    t.runtimeClass.asInstanceOf[Class[T]] cast optReg.get
  }

  /**
    * Obtains a consumer registration of the specified type from the given
    * provider. Throws an exception if no such registration can be found.
    *
    * @param provider the provider
    * @param t        the class tag
    * @tparam T the type of the desired registration
    * @return the registration object
    */
  def findRegistration[T <: ConsumerRegistration[_]](provider: ConsumerRegistrationProvider)
                                                    (implicit t: ClassTag[T]): T =
  findRegistration(provider.registrations)(t)

  /**
    * Checks that all registrations in the provided collection have unique IDs.
    *
    * @param registrations the collection with registrations
    * @return a flag whether the check is successful
    */
  def checkRegistrationIDs(registrations: Iterable[ConsumerRegistration[_]]): Boolean = {
    val idSet = registrations.map(_.id).toSet
    !idSet.contains(null) && idSet.size == registrations.size
  }

  /**
    * Checks that all registrations of the given provider have unique IDs.
    *
    * @param provider the provider
    * @return a flag whether the check is successful
    */
  def checkRegistrationIDs(provider: ConsumerRegistrationProvider): Boolean =
  checkRegistrationIDs(provider.registrations)
}
