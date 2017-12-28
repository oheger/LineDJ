/*
 * Copyright 2015-2017 The Developers Team.
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

import java.util
import java.util.Collections

import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ConsumerRegistrationProcessor''.
  */
class ConsumerRegistrationProcessorSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a mock consumer registration provider that returns the specified
    * registration objects.
    *
    * @param regs the registration objects
    * @return the mock provider
    */
  private def createProvider(regs: ConsumerRegistration[_]*): ConsumerRegistrationProvider = {
    val provider = mock[ConsumerRegistrationProvider]
    when(provider.registrations).thenReturn(regs)
    provider
  }

  "A ConsumerRegistrationProcessor" should "register all consumers" in {
    val bus = mock[MessageBus]
    val reg1, reg2, reg3, reg4 = mock[ConsumerRegistration[String]]
    val provider1 = createProvider(reg1)
    val provider2 = createProvider(reg2, reg3)
    val provider3 = createProvider(reg4)
    val processor = new ConsumerRegistrationProcessor(java.util.Arrays.asList(provider1,
      provider2, provider3))

    processor setMessageBus bus
    val inOrder = Mockito.inOrder(bus)
    inOrder.verify(bus).publish(reg1)
    inOrder.verify(bus).publish(reg2)
    inOrder.verify(bus).publish(reg3)
    inOrder.verify(bus).publish(reg4)
  }

  it should "handle the removal of registrations" in {
    val bus = mock[MessageBus]
    val reg1, reg2 = mock[ConsumerRegistration[String]]
    val unReg1, unReg2 = new Object
    doReturn(unReg1).when(reg1).unRegistration
    doReturn(unReg2).when(reg2).unRegistration
    val providers = Collections.singletonList(createProvider(reg1, reg2))
    val processor = new ConsumerRegistrationProcessor(providers)
    processor setMessageBus bus

    processor.removeRegistrations()
    verify(bus).publish(unReg1)
    verify(bus).publish(unReg2)
  }

  it should "handle the removal of registrations if it has not been initialized before" in {
    val processor = new ConsumerRegistrationProcessor(new util.ArrayList)

    processor.removeRegistrations() // should not crash
  }
}
