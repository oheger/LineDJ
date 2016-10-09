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

import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension
.{AvailableMediaRegistration, AvailableMediaUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.{ConsumerFunction,
ConsumerID, ConsumerRegistration}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, GetAvailableMedia}
import de.oliver_heger.linedj.shared.archive.metadata.MetaDataScanCompleted
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''AvailableMediaExtension''.
  */
class AvailableMediaExtensionSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a registration for a media consumer based on mock objects.
    *
    * @return the registration
    */
  private def createRegistration(): ConsumerRegistration[AvailableMedia] =
  AvailableMediaRegistration(mock[ConsumerID],
    mock[ConsumerFunction[AvailableMedia]])

  /**
    * Verifies that one or more requests for meta data have been sent.
    *
    * @param ext   the extension test object
    * @param count the number of expected requests
    */
  private def expectMediaDataRequest(ext: AvailableMediaExtension, count: Int = 1): Unit = {
    verify(ext.mediaFacade, times(count)).send(MediaActors.MediaManager, GetAvailableMedia)
  }

  /**
    * Creates a test extension object that is configured with a mock facade.
    *
    * @return the test extension
    */
  private def createExtension(): AvailableMediaExtension =
  new AvailableMediaExtension(mock[MediaFacade])

  "An AvailableMediaExtension" should "send messages to consumers" in {
    val media = mock[AvailableMedia]
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive reg2

    ext receive media
    verify(reg1.callback).apply(media)
    verify(reg2.callback).apply(media)
  }

  it should "add a state listener registration for the first consumer" in {
    val ext = createExtension()
    ext receive createRegistration()
    ext receive createRegistration()

    verify(ext.mediaFacade).registerMetaDataStateListener()
  }

  it should "only add one state listener registration" in {
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive AvailableMediaUnregistration(reg1.id)

    ext receive reg2
    verify(ext.mediaFacade).registerMetaDataStateListener()
  }

  it should "request media data for the first consumer" in {
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val ext = createExtension()
    ext receive reg1
    ext receive reg2

    expectMediaDataRequest(ext)
  }

  it should "cache media data and pass it to new consumers" in {
    val reg1 = createRegistration()
    val reg2 = createRegistration()
    val mediaData = mock[AvailableMedia]
    val ext = createExtension()
    ext receive reg1

    ext receive mediaData
    ext receive reg2
    verify(reg1.callback).apply(mediaData)
    verify(reg2.callback).apply(mediaData)
  }

  it should "only request media data if no cached data is available" in {
    val ext = createExtension()
    ext receive mock[AvailableMedia]

    ext receive createRegistration()
    verify(ext.mediaFacade, never()).send(MediaActors.MediaManager, GetAvailableMedia)
  }

  it should "allow removing a consumer" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg

    ext receive AvailableMediaUnregistration(reg.id)
    ext.consumerList shouldBe 'empty
  }

  it should "send only a single request for media data" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg

    ext receive AvailableMediaUnregistration(reg.id)
    ext receive reg
    expectMediaDataRequest(ext)
  }

  it should "reset media data when the archive becomes available (again)" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive mock[AvailableMedia]

    ext receive MediaFacade.MediaArchiveAvailable
    ext receive reg
    expectMediaDataRequest(ext)
    verify(reg.callback, never()).apply(any(classOf[AvailableMedia]))
  }

  it should "request new data when the archive becomes available if necessary" in {
    val ext = createExtension()
    ext receive mock[AvailableMedia]
    ext receive createRegistration()

    ext receive MediaFacade.MediaArchiveAvailable
    expectMediaDataRequest(ext)
  }

  it should "add a listener registration when the archive becomes available if necessary" in {
    val ext = createExtension()
    ext receive createRegistration()

    ext receive MediaFacade.MediaArchiveAvailable
    verify(ext.mediaFacade, times(2)).registerMetaDataStateListener()
  }

  it should "skip actions when the archive becomes available if no consumers" in {
    val ext = createExtension()

    ext receive MediaFacade.MediaArchiveAvailable
    verify(ext.mediaFacade, never()).send(MediaActors.MediaManager, GetAvailableMedia)
    verify(ext.mediaFacade, never()).registerMetaDataStateListener()
  }

  it should "reset the request pending flag" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg
    ext receive AvailableMediaUnregistration(reg.id)

    ext receive MediaFacade.MediaArchiveAvailable
    ext receive reg
    expectMediaDataRequest(ext, 2)
  }

  it should "reset the state listener registered flag" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg
    ext receive AvailableMediaUnregistration(reg.id)

    ext receive MediaFacade.MediaArchiveAvailable
    ext receive reg
    verify(ext.mediaFacade, times(2)).registerMetaDataStateListener()
  }


  it should "reset media data when a scan is complete" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive mock[AvailableMedia]

    ext receive MetaDataScanCompleted
    ext receive reg
    expectMediaDataRequest(ext)
    verify(reg.callback, never()).apply(any(classOf[AvailableMedia]))
  }

  it should "request new data when a scan is complete if necessary" in {
    val ext = createExtension()
    ext receive mock[AvailableMedia]
    ext receive createRegistration()

    ext receive MetaDataScanCompleted
    expectMediaDataRequest(ext)
  }

  it should "skip actions when a scan is complete if no consumers" in {
    val ext = createExtension()

    ext receive MetaDataScanCompleted
    verify(ext.mediaFacade, never()).send(MediaActors.MediaManager, GetAvailableMedia)
    verify(ext.mediaFacade, never()).unregisterMetaDataStateListener()
  }

  it should "reset the request pending flag if a scan is complete" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg
    ext receive AvailableMediaUnregistration(reg.id)

    ext receive MetaDataScanCompleted
    ext receive reg
    expectMediaDataRequest(ext, 2)
  }

  it should "remove the state listener registration if a scan is complete and no consumers" in {
    val reg = createRegistration()
    val ext = createExtension()
    ext receive reg
    ext receive AvailableMediaUnregistration(reg.id)

    ext receive MetaDataScanCompleted
    verify(ext.mediaFacade).unregisterMetaDataStateListener()
  }
}
