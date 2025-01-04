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

package de.oliver_heger.linedj.platform.mediaifc.ext

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.{AvailableMediaRegistration, AvailableMediaUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, GetAvailableMedia}
import de.oliver_heger.linedj.shared.archive.metadata.{MetadataScanCompleted$, MetadataScanStarted$}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''AvailableMediaExtension''.
  */
class AvailableMediaExtensionSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  /**
    * Creates a registration for a media consumer based on mock objects.
    *
    * @return the registration
    */
  private def createRegistration(): ConsumerRegistration[AvailableMedia] =
  AvailableMediaRegistration(ComponentID(),
    mock[ConsumerFunction[AvailableMedia]])

  /**
    * Verifies that one or more requests for metadata have been sent.
    *
    * @param ext   the extension test object
    * @param count the number of expected requests
    */
  private def expectMediaDataRequest(ext: AvailableMediaExtension, count: Int = 1): Unit =
    verify(ext.mediaFacade, times(count)).send(MediaActors.MediaManager, GetAvailableMedia)

  /**
    * Creates a test extension object that is configured with a mock facade.
    *
    * @return the test extension
    */
  private def createExtension(): AvailableMediaExtension =
  new AvailableMediaExtension(mock[MediaFacade])

  "An AvailableMediaExtension" should "send messages to consumers" in:
    val media = mock[AvailableMedia]
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive reg2

    ext receive media
    verify(reg1.callback).apply(media)
    verify(reg2.callback).apply(media)

  it should "add a state listener registration for the first consumer" in:
    val ext = createExtension()
    ext receive createRegistration()
    ext receive createRegistration()

    verify(ext.mediaFacade).registerMetadataStateListener(ext.componentID)

  it should "request media data for the first consumer" in:
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive reg2

    expectMediaDataRequest(ext)

  it should "cache media data and pass it to new consumers" in:
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val mediaData = mock[AvailableMedia]
    val ext = createExtension()
    ext receive reg1

    ext receive mediaData
    ext receive reg2
    verify(reg1.callback).apply(mediaData)
    verify(reg2.callback).apply(mediaData)

  it should "only request media data if no cached data is available" in:
    val ext = createExtension()
    ext receive mock[AvailableMedia]

    ext receive createRegistration()
    verify(ext.mediaFacade, never()).send(MediaActors.MediaManager, GetAvailableMedia)

  it should "allow removing a consumer" in:
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg

    ext receive AvailableMediaUnregistration(reg.id)
    ext.consumerMap shouldBe empty

  it should "send only a single request for media data" in:
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg

    ext receive AvailableMediaUnregistration(reg.id)
    ext receive reg
    expectMediaDataRequest(ext)

  it should "reset media data when the archive becomes available (again)" in:
    val reg = createRegistration()
    val ext = createExtension()
    ext receive mock[AvailableMedia]

    ext receive MediaFacade.MediaArchiveAvailable
    ext receive reg
    expectMediaDataRequest(ext)
    verify(reg.callback, never()).apply(any(classOf[AvailableMedia]))

  it should "request new data when the archive becomes available if necessary" in:
    val ext = createExtension()
    ext receive mock[AvailableMedia]
    ext receive createRegistration()

    ext receive MediaFacade.MediaArchiveAvailable
    expectMediaDataRequest(ext)
    verify(ext.mediaFacade).registerMetadataStateListener(ext.componentID)

  it should "skip actions when the archive becomes available if no consumers" in:
    val ext = createExtension()

    ext receive MediaFacade.MediaArchiveAvailable
    verify(ext.mediaFacade, never()).send(MediaActors.MediaManager, GetAvailableMedia)
    verify(ext.mediaFacade, never()).registerMetadataStateListener(ext.componentID)

  it should "reset the request pending flag" in:
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg
    ext receive AvailableMediaUnregistration(reg.id)

    ext receive MediaFacade.MediaArchiveAvailable
    ext receive reg
    expectMediaDataRequest(ext, 2)

  it should "reset media data when a scan starts" in:
    val reg = createRegistration()
    val ext = createExtension()
    ext receive mock[AvailableMedia]

    ext receive MetadataScanStarted$
    ext receive reg
    expectMediaDataRequest(ext)
    verify(reg.callback, never()).apply(any(classOf[AvailableMedia]))

  it should "request new data when a scan starts if necessary" in:
    val ext = createExtension()
    ext receive mock[AvailableMedia]
    ext receive createRegistration()

    ext receive MetadataScanStarted$
    expectMediaDataRequest(ext)

  it should "skip actions when a scan starts if no consumers" in:
    val ext = createExtension()

    ext receive MetadataScanCompleted$
    verify(ext.mediaFacade, never()).send(MediaActors.MediaManager, GetAvailableMedia)
    verify(ext.mediaFacade, never()).unregisterMetadataStateListener(ext.componentID)

  it should "reset the request pending flag if a scan starts" in:
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg
    ext receive AvailableMediaUnregistration(reg.id)

    ext receive MetadataScanStarted$
    ext receive reg
    expectMediaDataRequest(ext, 2)

  it should "remove the state listener registration if a scan starts and no consumers" in:
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg
    ext receive AvailableMediaUnregistration(reg.id)

    ext receive MetadataScanStarted$
    verify(ext.mediaFacade).unregisterMetadataStateListener(ext.componentID)

  it should "create an un-registration object from a registration" in:
    val reg = createRegistration()

    val unReg = reg.unRegistration
    unReg should be(AvailableMediaUnregistration(reg.id))
