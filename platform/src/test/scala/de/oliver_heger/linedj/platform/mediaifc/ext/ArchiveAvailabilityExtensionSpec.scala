/*
 * Copyright 2015-2019 The Developers Team.
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

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaArchiveAvailabilityEvent
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ArchiveAvailabilityExtension''.
  */
class ArchiveAvailabilityExtensionSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a registration for an archive availability consumer.
    *
    * @return the new consumer registration
    */
  private def createRegistration(): ConsumerRegistration[MediaArchiveAvailabilityEvent] =
  ArchiveAvailabilityRegistration(ComponentID(),
    mock[ConsumerFunction[MediaArchiveAvailabilityEvent]])

  "An ArchiveAvailabilityExtension" should "pass events to registered consumers" in {
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = new ArchiveAvailabilityExtension
    ext receive reg1
    ext receive reg2

    ext receive MediaFacade.MediaArchiveAvailable
    ext receive MediaFacade.MediaArchiveUnavailable
    verify(reg1.callback).apply(MediaFacade.MediaArchiveAvailable)
    verify(reg2.callback).apply(MediaFacade.MediaArchiveAvailable)
    verify(reg1.callback, times(2)).apply(MediaFacade.MediaArchiveUnavailable)
    verify(reg2.callback, times(2)).apply(MediaFacade.MediaArchiveUnavailable)
  }

  it should "support removing consumers" in {
    val reg = createRegistration()
    val ext = new ArchiveAvailabilityExtension
    ext receive reg

    ext receive ArchiveAvailabilityExtension.ArchiveAvailabilityUnregistration(reg.id)
    ext receive MediaFacade.MediaArchiveAvailable
    verify(reg.callback, never()).apply(MediaFacade.MediaArchiveAvailable)
  }

  it should "propagate the last known state to newly added consumers" in {
    val reg = createRegistration()
    val ext = new ArchiveAvailabilityExtension

    ext receive MediaFacade.MediaArchiveAvailable
    ext receive reg
    verify(reg.callback).apply(MediaFacade.MediaArchiveAvailable)
  }

  it should "generate a correct un-registration object" in {
    val reg = createRegistration()

    val unReg = reg.unRegistration
    unReg should be(ArchiveAvailabilityUnregistration(reg.id))
  }
}
