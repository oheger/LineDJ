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

package de.oliver_heger.linedj.platform.mediaifc.disabled

import akka.util.Timeout
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.shared.archive.media.{GetAvailableMedia, MediumID}
import org.apache.commons.configuration.Configuration
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for ''DisabledMediaFacade''. Note: This class can sometimes
  * only test that a method invocation does not cause an exception.
  */
class DisabledMediaFacadeSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "A DisabledMediaFacade" should "implement activate()" in {
    val bus = mock[MessageBus]
    val facade = new DisabledMediaFacade(bus)

    facade activate true
    verifyZeroInteractions(bus)
  }

  it should "implement send()" in {
    val bus = mock[MessageBus]
    val facade = new DisabledMediaFacade(bus)

    facade.send(MediaActors.MediaManager, GetAvailableMedia)
    verifyZeroInteractions(bus)
  }

  it should "implement initConfiguration()" in {
    val config = mock[Configuration]
    val bus = mock[MessageBus]
    val facade = new DisabledMediaFacade(bus)

    facade initConfiguration config
    verifyZeroInteractions(bus, config)
  }

  it should "publish a media state" in {
    val bus = mock[MessageBus]
    val facade = new DisabledMediaFacade(bus)

    facade.requestMediaState()
    verify(bus).publish(MediaFacade.MediaArchiveUnavailable)
  }

  it should "implement removeMetaDataListener()" in {
    val bus = mock[MessageBus]
    val facade = new DisabledMediaFacade(bus)

    facade removeMetaDataListener MediumID("mid", None)
    verifyZeroInteractions(bus)
  }

  it should "implement requestActor()" in {
    val bus = mock[MessageBus]
    val facade = new DisabledMediaFacade(bus)
    implicit val timeout: Timeout = Timeout(3.seconds)

    val future = facade requestActor MediaActors.MediaManager
    Await.result(future, 3.seconds) should be(None)
  }

  it should "implement registerMetaDataStateListener()" in {
    val facade = new DisabledMediaFacade(mock[MessageBus])

    facade.registerMetaDataStateListener(ComponentID())
  }

  it should "implement unregisterMetaDataStateListener()" in {
    val facade = new DisabledMediaFacade(mock[MessageBus])

    facade.unregisterMetaDataStateListener(ComponentID())
  }
}
