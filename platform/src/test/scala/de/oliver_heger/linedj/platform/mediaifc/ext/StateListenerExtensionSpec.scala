/*
 * Copyright 2015-2016 The Developers Team.
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

import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.{ConsumerFunction, ConsumerID, ConsumerRegistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.StateListenerExtension.StateListenerUnregistration
import de.oliver_heger.linedj.shared.archive.metadata._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object StateListenerExtensionSpec {
  /** A test meta data state event. */
  private val State = MetaDataStateUpdated(MetaDataState(1, 2, 3, 4, scanInProgress = false))
}

/**
  * Test class for ''StateListenerExtension''.
  */
class StateListenerExtensionSpec extends FlatSpec with Matchers with MockitoSugar {

  import StateListenerExtensionSpec._

  /**
    * Creates a registration for state event consumers. All components are
    * mocks.
    *
    * @return the registration
    */
  private def createRegistration(): ConsumerRegistration[MetaDataStateEvent] = {
    val func = mock[ConsumerFunction[MetaDataStateEvent]]
    val id = mock[ConsumerID]
    StateListenerExtension.StateListenerRegistration(id, func)
  }

  /**
    * Creates a test instance for the extension with a mock facade.
    *
    * @return the test instance
    */
  private def createExtension(): StateListenerExtension =
  new StateListenerExtension(mock[MediaFacade])

  "A StateListenerExtension" should "pass state events to consumers" in {
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive reg2

    ext receive MetaDataScanStarted
    ext receive MetaDataScanCompleted
    verify(reg1.callback).apply(MetaDataScanStarted)
    verify(reg2.callback).apply(MetaDataScanStarted)
    verify(reg1.callback).apply(MetaDataScanCompleted)
    verify(reg2.callback).apply(MetaDataScanCompleted)
  }

  it should "create a listener registration for the first consumer" in {
    val ext = createExtension()
    ext receive createRegistration()
    ext receive createRegistration()

    verify(ext.mediaFacade).registerMetaDataStateListener()
  }

  it should "remove the listener registration if there are no consumers" in {
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive reg2

    ext receive StateListenerUnregistration(reg1.id)
    ext receive StateListenerUnregistration(reg2.id)
    verify(ext.mediaFacade).unregisterMetaDataStateListener()
  }

  it should "pass the current state to new consumers" in {
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1

    ext receive State
    ext receive reg2
    verify(reg1.callback).apply(State)
    verify(reg2.callback).apply(State)
  }

  it should "reset the current state when the last consumer is removed" in {
    val reg1 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive State

    ext receive StateListenerUnregistration(reg1.id)
    val reg2 = createRegistration()
    ext receive reg2
    verify(reg2.callback, never()).apply(State)
  }

  it should "create a new registration when the archive becomes available" in {
    val ext = createExtension()

    ext onArchiveAvailable true
    verify(ext.mediaFacade).registerMetaDataStateListener()
  }

  it should "only re-register the state listener if there are consumers" in {
    val ext = createExtension()

    ext onArchiveAvailable false
    verify(ext.mediaFacade, never()).registerMetaDataStateListener()
  }

  it should "reset the current state when the archive becomes available (again)" in {
    val ext = createExtension()
    ext receive State

    ext onArchiveAvailable false
    val reg = createRegistration()
    ext receive reg
    verify(reg.callback, never()).apply(State)
  }
}
