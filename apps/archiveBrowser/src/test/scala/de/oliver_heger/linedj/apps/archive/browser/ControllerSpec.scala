/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import de.oliver_heger.linedj.shared.archive.metadata.Checksums.MediumChecksum
import org.mockito.Mockito.*
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ControllerSpec:
  /** Test medium for rock music. */
  private val RockMedium = ArchiveModel.MediumOverview(MediumChecksum("medium1"), "Rock music")

  /** Test medium for pop music. */
  private val PopMedium = ArchiveModel.MediumOverview(MediumChecksum("medium2"), "Pop music")

  /** Test medium for classic music. */
  private val ClassicMedium = ArchiveModel.MediumOverview(MediumChecksum("medium3"), "Classics")

  /** An object with test media data. */
  private val TestMediaData = ArchiveModel.MediaOverview(
    List(
      RockMedium,
      PopMedium,
      ClassicMedium
    )
  )

  /** The sorted list of test media. */
  private val SortedTestMedia = List(ClassicMedium, PopMedium, RockMedium)
end ControllerSpec

/**
  * Test class for [[Controller]].
  */
class ControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import ControllerSpec.*

  "A Controller" should "remove the change listener registration when it gets destroyed" in :
    val helper = new ControllerTestHelper

    helper.verifyArchiveChangeListenerDeregistration()

  it should "process notifications about changed media" in :
    val helper = new ControllerTestHelper

    helper.testArchiveChangeListener()

  it should "store media information in the media combobox handler" in :
    val helper = new ControllerTestHelper

    helper.updateMedia()
      .expectMediaData(SortedTestMedia)
    helper.selectedMedium shouldBe empty

  it should "remove old media information when updating media" in :
    val helper = new ControllerTestHelper
    val oldData = List(
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("foo"), "Foo medium"),
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("bar"), "Bar medium"),
    )
    helper.updateMedia(ArchiveModel.MediaOverview(oldData))
      .setSelectedMedium(Checksums.MediumChecksum("bar"))

    helper.updateMedia()
      .expectMediaData(SortedTestMedia)
    helper.selectedMedium shouldBe empty

  it should "keep the selection when updating media data if possible" in :
    val MetalMedium = ArchiveModel.MediumOverview(Checksums.MediumChecksum("medium4"), "Metal")
    val MainstreamMedium = ArchiveModel.MediumOverview(Checksums.MediumChecksum("medium5"), "Mainstream")
    val moreMedia = List(MetalMedium, MainstreamMedium)
    val sortedUpdatedMedia = List(ClassicMedium, MainstreamMedium, MetalMedium, PopMedium, RockMedium)
    val helper = new ControllerTestHelper
    helper.updateMedia()
      .setSelectedMedium(Checksums.MediumChecksum("medium2"))

    helper.updateMedia(ArchiveModel.MediaOverview(TestMediaData.media ++ moreMedia))

    helper.expectMediaData(sortedUpdatedMedia)
    helper.selectedMedium should be(Some(Checksums.MediumChecksum("medium2")))

  /**
    * A test helper class managing a controller instance to be tested and its
    * dependencies.
    */
  private class ControllerTestHelper:
    /**
      * The message bus passed to the test controller instance. It is used to
      * check the messages published by the controller.
      */
    private val messageBus = new MessageBusTestImpl()

    /** A mock for the archive service. */
    private val archiveService = mock[ArchiveService]

    /** Stub for the list handler for the combo with media. */
    private val comboMedia = new ListComponentHandlerStub

    /** The controller to be tested. */
    private val controller = createController()

    /**
      * Verifies that the controller removes the change listener registration
      * at the archive service when it gets destroyed.
      */
    def verifyArchiveChangeListenerDeregistration(): Unit =
      controller.destroy()
      verify(archiveService).removeChangeListener(controller)

    /**
      * Tests whether the test controller correctly processes a notification
      * about changed media data.
      */
    def testArchiveChangeListener(): Unit =
      controller.archiveStateChanged(TestMediaData)
      messageBus.expectMessageType[Controller.MediaChanged] should be(Controller.MediaChanged(TestMediaData))

    /**
      * Passes the given media data to the test controller.
      *
      * @param mediaData the media data
      * @return this test helper
      */
    def updateMedia(mediaData: ArchiveModel.MediaOverview = TestMediaData): ControllerTestHelper =
      controller.receive(Controller.MediaChanged(mediaData))
      this

    /**
      * Checks whether the combobox with media contains the expected data.
      *
      * @param expectedData a list with the expected media
      * @return this test helper
      */
    def expectMediaData(expectedData: Iterable[ArchiveModel.MediumOverview]): ControllerTestHelper =
      comboMedia.getListModel.size() should be(expectedData.size)
      forEvery(expectedData.zipWithIndex):
        case (medium, index) =>
          comboMedia.getListModel.getValueObject(index) should be(medium.id)
          comboMedia.getListModel.getDisplayObject(index) should be(medium.title)
      this

    /**
      * Returns the ID of the medium currently selected in the combobox.
      *
      * @return an [[Option]] with the ID of the selected medium
      */
    def selectedMedium: Option[Checksums.MediumChecksum] =
      Option(comboMedia.getData).map(_.asInstanceOf[Checksums.MediumChecksum])

    /**
      * Sets the ID of the selected medium in the combobox.
      *
      * @param id the ID to select
      * @return this test helper
      */
    def setSelectedMedium(id: Checksums.MediumChecksum): ControllerTestHelper =
      comboMedia.setData(id.asInstanceOf[AnyRef])
      this

    /**
      * Creates the controller instance under test.
      *
      * @return the test controller instance
      */
    private def createController(): Controller =
      val c = new Controller(
        archiveService,
        messageBus,
        comboMedia
      )
      c.initialize()
      verify(archiveService).addChangeListener(c)
      c
