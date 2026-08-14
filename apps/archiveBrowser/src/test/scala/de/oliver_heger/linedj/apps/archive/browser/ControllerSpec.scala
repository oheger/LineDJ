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
import net.sf.jguiraffe.gui.builder.components.model.{TreeHandler, TreeNodePath}
import net.sf.jguiraffe.resources.Message
import org.apache.commons.configuration.{AbstractConfiguration, HierarchicalConfiguration}
import org.apache.commons.configuration.tree.DefaultExpressionEngine
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

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

  /** Details of the test medium. */
  private val TestMediumDetails = ArchiveModel.MediumDetails(
    overview = RockMedium,
    description = "Very good rock music",
    orderMode = Some(ArchiveModel.OrderMode.RandomSongs),
    archiveName = "Test-Archive"
  )

  /** The URL to query detail information about the test medium. */
  private val QueryMediumUrl = s"/api/archive/media/${RockMedium.id.checksum}"

  /** The URL to query the artists of the test medium. */
  private val QueryArtistUrl = s"$QueryMediumUrl/artists"

  /** The URL to query the albums of the test medium. */
  private val QueryAlbumUrl = s"$QueryMediumUrl/albums"

  /**
    * Returns the URL to query information about the given medium.
    *
    * @param medium the medium
    * @return the URL for this medium
    */
  private def mediumUrl(medium: ArchiveModel.MediumOverview): String = s"/api/archive/media/${medium.id.checksum}"
end ControllerSpec

/**
  * Test class for [[Controller]].
  */
class ControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import ControllerSpec.*
  import scala.jdk.CollectionConverters.*

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

  it should "populate the artist tree view when a medium is selected" in :
    val artists = List(
      ArchiveModel.ArtistInfo("art1", "Dire Straits"),
      ArchiveModel.ArtistInfo("art2", "Mike Oldfield"),
      ArchiveModel.ArtistInfo("art3", "Never released")
    )
    val albums = List(
      ArchiveModel.AlbumInfo("alb1", "Brothers in Arms", "art1"),
      ArchiveModel.AlbumInfo("alb2", "Crisis", "art2"),
      ArchiveModel.AlbumInfo("alb3", "Five Miles Out", "art2"),
      ArchiveModel.AlbumInfo("alb4", "Love over Gold", "art1"),
      ArchiveModel.AlbumInfo("alb5", "Tubular Bells I", "art2"),
      ArchiveModel.AlbumInfo("alb?", "Undefined Artist", "art0"),
      ArchiveModel.AlbumInfo("albUnk", "Unknown Artist", "artUnknown")
    )
    val helper = new ControllerTestHelper

    helper.expectArchiveRequestData(QueryArtistUrl, ArchiveModel.ItemsResult(artists))
      .expectArchiveRequestData(QueryAlbumUrl, ArchiveModel.ItemsResult(albums))
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .passSelectedMedium(RockMedium.id)
      .handleArchiveRequest()

    val direStraitsAlbums = "" +: List("Brothers in Arms", "Love over Gold")
    val mikeOldfieldAlbums = "" +: List("Crisis", "Five Miles Out", "Tubular Bells I")
    val direStraitsConfig = helper.artistTreeModel.configurationAt("Dire Straits")
    direStraitsConfig.getKeys.asScala.toList should contain theSameElementsAs direStraitsAlbums
    val mikeOldfieldConfig = helper.artistTreeModel.configurationAt("Mike Oldfield")
    mikeOldfieldConfig.getKeys.asScala.toList should contain theSameElementsAs mikeOldfieldAlbums
    helper.artistTreeModel.getProperty("Dire Straits") should be("Dire Straits")
    helper.artistTreeModel.getProperty("Dire Straits|Brothers in Arms") should be(Controller.AlbumID("alb1"))
    helper.artistTreeModel.getProperty("Dire Straits|Love over Gold") should be(Controller.AlbumID("alb4"))
    helper.artistTreeModel.getProperty("Mike Oldfield") should be("Mike Oldfield")
    helper.artistTreeModel.getProperty("Mike Oldfield|Tubular Bells I") should be(Controller.AlbumID("alb5"))
    helper.artistTreeModel.configurationAt("Never released").getKeys.asScala.toList should contain only ""
    helper.artistTreeModel.getProperty("Never released") should be(Controller.ArtistID("art3"))

    helper.verifyArtistTreeReset()

  it should "add an artist node before its albums to the tree model" in :
    val artists = List(
      ArchiveModel.ArtistInfo("art1", "Dire Straits"),
      ArchiveModel.ArtistInfo("art2", "Never released")
    )
    val albums = List(
      ArchiveModel.AlbumInfo("alb1", "Brothers in Arms", "art1"),
      ArchiveModel.AlbumInfo("alb2", "Love over Gold", "art1")
    )
    val addedKeys = ListBuffer.empty[String]
    val helper = new ControllerTestHelper
    helper.artistTreeModel.addConfigurationListener(e =>
      if !e.isBeforeUpdate && e.getType == AbstractConfiguration.EVENT_ADD_PROPERTY then
        addedKeys += e.getPropertyName
    )

    helper.expectArchiveRequestData(QueryArtistUrl, ArchiveModel.ItemsResult(artists))
      .expectArchiveRequestData(QueryAlbumUrl, ArchiveModel.ItemsResult(albums))
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .passSelectedMedium(RockMedium.id)
      .handleArchiveRequest()

    val expectedOrder = List(
      "Dire Straits",
      "Dire Straits|Brothers in Arms",
      "Dire Straits|Love over Gold",
      "Never released"
    )
    addedKeys.toList should be(expectedOrder)

  it should "clear the artist tree view before loading data for the new medium" in :
    val artists = List(ArchiveModel.ArtistInfo("art1", "Dire Straits"))
    val albums = List(ArchiveModel.AlbumInfo("alb1", "Brothers in Arms", "art1"))
    val helper = new ControllerTestHelper
    helper.artistTreeModel.addProperty("foo", "bar")

    helper.expectArchiveRequestData(QueryArtistUrl, ArchiveModel.ItemsResult(artists))
      .expectArchiveRequestData(QueryAlbumUrl, ArchiveModel.ItemsResult(albums))
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .passSelectedMedium(RockMedium.id)

    helper.artistTreeModel.isEmpty shouldBe true

  it should "clear the artist tree view if no medium is selected" in :
    val helper = new ControllerTestHelper
    helper.artistTreeModel.addProperty("foo", "bar")

    helper.simulateMediumSelection(None)

    helper.artistTreeModel.isEmpty shouldBe true

  it should "update the status controller when a medium is selected" in :
    val artists = List(ArchiveModel.ArtistInfo("art1", "Dire Straits"))
    val albums = List(ArchiveModel.AlbumInfo("alb1", "Brothers in Arms", "art1"))
    val helper = new ControllerTestHelper

    helper.expectArchiveRequestData(QueryArtistUrl, ArchiveModel.ItemsResult(artists))
      .expectArchiveRequestData(QueryAlbumUrl, ArchiveModel.ItemsResult(albums))
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .passSelectedMedium(RockMedium.id)

    helper.activeLoadOperations should be(1)
    helper.currentStatusMessage should be(Some(Message(null, Controller.ResMediumLoading, RockMedium.id.checksum)))

  it should "update the status controller when medium data has been loaded" in :
    val artists = List(ArchiveModel.ArtistInfo("art1", "Dire Straits"))
    val albums = List(ArchiveModel.AlbumInfo("alb1", "Brothers in Arms", "art1"))
    val helper = new ControllerTestHelper

    helper.expectArchiveRequestData(QueryArtistUrl, ArchiveModel.ItemsResult(artists))
      .expectArchiveRequestData(QueryAlbumUrl, ArchiveModel.ItemsResult(albums))
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .passSelectedMedium(RockMedium.id)
      .handleArchiveRequest()

    helper.activeLoadOperations should be(0)
    helper.statusMediumTitle should be(Some(TestMediumDetails.title))

  it should "update the status controller when loading fails" in :
    val ErrorMessage = "Loading of data failed."
    val failedFuture: Future[ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]] =
      Future.failed(new IllegalArgumentException(ErrorMessage))
    val helper = new ControllerTestHelper

    helper.expectArchiveRequestData(
        QueryArtistUrl,
        ArchiveModel.ItemsResult(List(ArchiveModel.ArtistInfo("a1", "Artist")))
      )
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .expectArchiveRequest(QueryAlbumUrl, failedFuture)
      .passSelectedMedium(RockMedium.id)
      .handleArchiveRequest()

    helper.activeLoadOperations should be(0)
    helper.currentStatusMessage should be(Some(Message(null, Controller.ResErrorLoading, ErrorMessage)))

  it should "update the status controller when no medium is selected" in :
    val artists = List(ArchiveModel.ArtistInfo("art1", "Dire Straits"))
    val albums = List(ArchiveModel.AlbumInfo("alb1", "Brothers in Arms", "art1"))
    val helper = new ControllerTestHelper

    helper.expectArchiveRequestData(QueryArtistUrl, ArchiveModel.ItemsResult(artists))
      .expectArchiveRequestData(QueryAlbumUrl, ArchiveModel.ItemsResult(albums))
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .passSelectedMedium(RockMedium.id)
      .handleArchiveRequest()
      .simulateMediumSelection(None)

    helper.statusMediumTitle shouldBe empty

  it should "update the UI when loading of a medium fails" in :
    val failedAlbumsFuture: Future[ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]] =
      Future.failed(new IllegalArgumentException("Some error"))
    val failedArtistsFuture: Future[ArchiveModel.ItemsResult[ArchiveModel.ArtistInfo]] =
      Future.failed(new IllegalStateException("Some other error"))
    val helper = new ControllerTestHelper
    helper.artistTreeModel.addProperty("foo", "bar")

    helper.expectArchiveRequest(QueryAlbumUrl, failedAlbumsFuture)
      .expectArchiveRequest(QueryArtistUrl, failedArtistsFuture)
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .passSelectedMedium(RockMedium.id)
      .handleArchiveRequest()

    helper.artistTreeModel.isEmpty shouldBe true

  it should "ignore a result for a no-longer selected medium" in :
    val rockArtists = List(ArchiveModel.ArtistInfo("art1", "Dire Straits"))
    val rockAlbums = List(ArchiveModel.AlbumInfo("alb1", "Brothers in Arms", "art1"))
    val classicArtists = List(ArchiveModel.ArtistInfo("art2", "Beethoven"))
    val classicAlbums = List(ArchiveModel.AlbumInfo("alb2", "Symphony No 9", "art2"))
    val classicUrl = mediumUrl(ClassicMedium)
    val classicDetails = ArchiveModel.MediumDetails(
      overview = ClassicMedium,
      description = "Classics",
      orderMode = None,
      archiveName = "Some-Archive"
    )
    val helper = new ControllerTestHelper

    helper.expectArchiveRequestData(QueryArtistUrl, ArchiveModel.ItemsResult(rockArtists))
      .expectArchiveRequestData(QueryAlbumUrl, ArchiveModel.ItemsResult(rockAlbums))
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .expectArchiveRequestData(classicUrl + "/artists", ArchiveModel.ItemsResult(classicArtists))
      .expectArchiveRequestData(classicUrl + "/albums", ArchiveModel.ItemsResult(classicAlbums))
      .expectArchiveRequestData(classicUrl, classicDetails)
      .passSelectedMedium(RockMedium.id)
      .passSelectedMedium(ClassicMedium.id)
      .handleArchiveRequest()

    helper.artistTreeModel.isEmpty shouldBe true

  it should "ignore a failure notification for a no-longer selected medium" in :
    val ErrorMessage = "Loading of data failed."
    val failedAlbumsFuture: Future[ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]] =
      Future.failed(new IllegalArgumentException(ErrorMessage))
    val classicUrl = mediumUrl(ClassicMedium)
    val classicDetails = ArchiveModel.MediumDetails(
      overview = ClassicMedium,
      description = "Classics",
      orderMode = None,
      archiveName = "Some-Archive"
    )
    val helper = new ControllerTestHelper

    helper.expectArchiveRequestData(
        QueryArtistUrl,
        ArchiveModel.ItemsResult(List(ArchiveModel.ArtistInfo("a1", "Artist")))
      )
      .expectArchiveRequestData(QueryMediumUrl, TestMediumDetails)
      .expectArchiveRequest(QueryAlbumUrl, failedAlbumsFuture)
      .expectArchiveRequestData(
        classicUrl + "/artists",
        ArchiveModel.ItemsResult(List(ArchiveModel.ArtistInfo("a2", "Artist")))
      )
      .expectArchiveRequestData(
        classicUrl + "/albums",
        ArchiveModel.ItemsResult(List(ArchiveModel.AlbumInfo("alb1", "Album", "a2")))
      )
      .expectArchiveRequestData(classicUrl, classicDetails)
      .passSelectedMedium(RockMedium.id)
      .passSelectedMedium(ClassicMedium.id)
      .handleArchiveRequest()
      .handleArchiveRequest()

    helper.currentStatusMessage should be(Some(Message(null, Controller.ResMediumLoading, ClassicMedium.id.checksum)))

  /**
    * A test helper class managing a controller instance to be tested and its
    * dependencies.
    */
  private class ControllerTestHelper:
    /**
      * The message bus passed to the test controller instance. It is used to
      * check the messages published by the controller.
      */
    private val messageBus = new MessageBusTestImpl

    /** A mock for the archive service. */
    private val archiveService = mock[ArchiveService]

    /** Stub for the list handler for the combo with media. */
    private val comboMedia = new ListComponentHandlerStub

    /**
      * The configuration acting as model for the tree view with artist data.
      */
    val artistTreeModel: HierarchicalConfiguration = createTreeModelConfig()

    /** Mock for the tree handler for artist/album information. */
    private val artistTreeHandler = createTreeHandler(artistTreeModel)

    /** The controller to be tested. */
    private val controller = createController()

    /**
      * A counter for the invocations of the status line controller that
      * indicate started and ended load operations.
      */
    private var activeLoadCounter = 0

    /** Stores the last medium title for the status line. */
    private var optMediumTitle: Option[String] = None

    /** Stores the last message shown in the status line. */
    private var optStatusMessage: Option[Message] = None

    /**
      * Returns the number of active load operations the controller has
      * reported to the status line controller.
      *
      * @return the active load operations
      */
    def activeLoadOperations: Int = activeLoadCounter

    /**
      * Returns the last medium title that was passed to the status line
      * controller.
      *
      * @return the medium title in the status line
      */
    def statusMediumTitle: Option[String] = optMediumTitle

    /**
      * Returns an [[Option]] with the last message that was passed to the
      * status line controller for being displayed.
      *
      * @return the currently displayed status message
      */
    def currentStatusMessage: Option[Message] = optStatusMessage

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
      * Prepares the mock archive service to expect a request for a specific
      * URL. The request is answered with a given [[Future]].
      *
      * @param url    the expected URL for the request
      * @param result the [[Future]] with the result to return
      * @tparam T the type of the result
      * @return this test helper
      */
    def expectArchiveRequest[T](url: String, result: Future[T]): ControllerTestHelper =
      doReturn(result).when(archiveService).queryData(argEq(url))(using any())
      this

    /**
      * Convenience function to prepare the mock archive service to return a
      * successful result [[Future]] for a query to a specific URL.
      *
      * @param url    the expected URL for the request
      * @param result the result to return
      * @tparam T the type of the result
      * @return this test helper
      */
    def expectArchiveRequestData[T](url: String, result: T): ControllerTestHelper =
      expectArchiveRequest(url, Future.successful(result))

    /**
      * Handles the response of an archive request. Since requests to the
      * archive are asynchronous, the [[Future]] result needs to be passed
      * through the message bus, so that it can be processed in the UI thread.
      * This function therefore checks whether a message is published on the
      * bus. It is then passed to the controller to be further processed.
      *
      * @return this test helper
      */
    def handleArchiveRequest(): ControllerTestHelper =
      val message = messageBus.expectMessageType[Any]
      controller.receive(message)
      this

    /**
      * Notifies the test controller that the medium selection changed to the
      * given medium.
      *
      * @param mediumID the selected medium ID
      * @return this test helper
      */
    def passSelectedMedium(mediumID: Checksums.MediumChecksum): ControllerTestHelper =
      simulateMediumSelection(Some(mediumID))

    /**
      * Notifies the test controller about a change in the selected medium.
      *
      * @param optMediumID the optional selected medium
      * @return this test helper
      */
    def simulateMediumSelection(optMediumID: Option[Checksums.MediumChecksum]): ControllerTestHelper =
      controller.mediumSelected(optMediumID)
      this

    /**
      * Verifies that after updating the artist tree view for another medium,
      * it is reset to display only the first layer of nodes.
      *
      * @return this test helper
      */
    def verifyArtistTreeReset(): ControllerTestHelper =
      val inOrder = Mockito.inOrder(artistTreeHandler)
      inOrder.verify(artistTreeHandler).clearSelection()
      inOrder.verify(artistTreeHandler).collapse(new TreeNodePath(artistTreeModel.getRoot))
      inOrder.verify(artistTreeHandler).expand(new TreeNodePath(artistTreeModel.getRoot))
      this

    /**
      * Creates the configuration acting as tree model.
      *
      * @return the tree model configuration
      */
    private def createTreeModelConfig(): HierarchicalConfiguration =
      val exprEngine = new DefaultExpressionEngine
      exprEngine.setPropertyDelimiter("|")
      val config = new HierarchicalConfiguration
      config.setExpressionEngine(exprEngine)
      config

    /**
      * Creates a mock tree handler.
      *
      * @param model the model for the tree
      * @return the mock tree handler
      */
    private def createTreeHandler(model: HierarchicalConfiguration): TreeHandler =
      val handler = mock[TreeHandler]
      when(handler.getModel).thenReturn(model)
      handler

    /**
      * Creates a stub for the [[StatusLineController]] that allows tracking
      * the modifications of the status line.
      *
      * @return the stub controller for the status line
      */
    private def createStatusLineController(): StatusLineController =
      new StatusLineController(mock, mock, mock):
        override def setMediumTitle(optTitle: Option[String]): Unit =
          optMediumTitle = optTitle

        override def loadOperationStarts(): Unit =
          activeLoadCounter += 1

        override def loadOperationEnds(): Unit =
          activeLoadCounter -= 1

        override def setStatusMessage(message: Message): Unit =
          optStatusMessage = Some(message)

    /**
      * Creates the controller instance under test.
      *
      * @return the test controller instance
      */
    private def createController(): Controller =
      val c = new Controller(
        archiveService,
        ExecutionContext.global,
        messageBus,
        createStatusLineController(),
        comboMedia,
        artistTreeHandler
      )
      c.initialize()
      verify(archiveService).addChangeListener(c)
      c
