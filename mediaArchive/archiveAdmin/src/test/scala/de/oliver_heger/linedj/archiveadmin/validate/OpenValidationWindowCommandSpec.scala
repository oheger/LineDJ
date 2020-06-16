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

package de.oliver_heger.linedj.archiveadmin.validate

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.testkit.TestKit
import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.{MediaFile, ValidationErrorCode}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{ValidatedItem, ValidationResult}
import de.oliver_heger.linedj.platform.app.{ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.Locator
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{eq => eqArg}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scalaz.Failure

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for the command classes to open the validation dialog.
  */
class OpenValidationWindowCommandSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("OpenValidationWindowCommandSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates an initialized mock for a ''ClientApplicationContext''.
    *
    * @return the mock context
    */
  private def createClientAppContext(): ClientApplicationContext = {
    val context = mock[ClientApplicationContext]
    when(context.managementConfiguration).thenReturn(new PropertiesConfiguration())
    when(context.actorSystem).thenReturn(system)
    context
  }

  /**
    * Creates a mock for a client application that returns a mock context.
    *
    * @return the mock client application
    */
  private def createClientApp(): ClientApplication = {
    val app = mock[ClientApplication]
    val context = createClientAppContext()
    when(app.clientApplicationContext).thenReturn(context)
    app
  }

  /**
    * Creates a mock client application with a configuration that sets the
    * parallelism for stream processing to 1.
    *
    * @return the mock client application
    */
  private def createClientAppWithParallelismDisabled(): ClientApplication = {
    val app = createClientApp()
    app.clientApplicationContext.managementConfiguration
      .addProperty(OpenValidationWindowCommand.PropValidationParallelism, 1)
    app
  }

  /**
    * Runs a stream with the validation flow returned by the given command.
    * The passed in files are sent to the stream; the resulting validated items
    * are returned.
    *
    * @param command the command
    * @param files   the files to be used as input
    * @return the validated items
    */
  private def checkValidationFlow(command: OpenValidationWindowCommand, files: List[MediaFile]*):
  List[ValidatedItem] = {
    val builderData = mock[ApplicationBuilderData]
    command.prepareBuilderData(builderData)
    val captor = ArgumentCaptor.forClass(classOf[Flow[List[MediaFile], ValidatedItem, Any]])
    verify(builderData).addProperty(eqArg(OpenValidationWindowCommand.PropFlow), captor.capture())

    val source = Source(files.toList)
    val sink = Sink.fold[List[ValidatedItem], ValidatedItem](List.empty)((lst, item) => item :: lst)
    val flow = captor.getValue
    val futResult = source.via(flow).runWith(sink)
    Await.result(futResult, 3.seconds).reverse
  }

  /**
    * Returns the validation result of the item from the given list that has
    * the specified properties.
    *
    * @param items the sequence of validated items
    * @param mid   the medium ID
    * @param uri   the URI of the element in question
    * @return the validation result for this element
    */
  private def findResultFor(items: Seq[ValidatedItem], mid: MediumID, uri: String): ValidationResult =
    items.find(item => item.medium == mid && item.uri == uri).get.result

  /**
    * Returns the validation result of the item from the given list that
    * corresponds to the specified media file.
    *
    * @param items the sequence of validated items
    * @param file  the file
    * @return the validation result for this file
    */
  private def findResultFor(items: Seq[ValidatedItem], file: MediaFile): ValidationResult =
    findResultFor(items, file.mediumID, file.uri)

  /**
    * Runs a validation flow and checks whether the order of processed elements
    * has changed. This indicates parallel processing. (As this is not
    * deterministic, multiple test runs are made.)
    *
    * @param command         the command
    * @param files           the original list with media files (per medium)
    * @param orderedUris     the ordered list of resulting uris
    * @param expChangedOrder flag whether the order should be changed
    */
  private def checkProcessingOrder(command: OpenValidationWindowCommand, files: List[List[MediaFile]],
                                   orderedUris: List[String], expChangedOrder: Boolean): Unit = {
    val MaxAttempts = 128

    @scala.annotation.tailrec
    def checkProcessingOrderChanged(attempt: Int): Boolean =
      if (attempt >= MaxAttempts) false
      else {
        val items = checkValidationFlow(command, files: _*)
        val itemUris = items.map(_.uri)
        if (itemUris != orderedUris) true
        else checkProcessingOrderChanged(attempt + 1)
      }

    checkProcessingOrderChanged(1) shouldBe expChangedOrder
  }

  "An OpenFileValidationWindowCommand" should "pass the script locator" in {
    val locator = mock[Locator]

    val command = new OpenFileValidationWindowCommand(locator, createClientApp())
    command.getLocator should be(locator)
  }

  it should "provide a correct validation flow" in {
    val file1 = ValidationTestHelper.file(1)
    val incompleteData = ValidationTestHelper.metaData(2).copy(album = None)
    val file2 = MediaFile(file1.mediumID, ValidationTestHelper.fileUri(2), incompleteData)
    val command = new OpenFileValidationWindowCommand(mock[Locator], createClientApp())

    val items = checkValidationFlow(command, List(file1, file2))
    items should have size 2
    findResultFor(items, file1).isSuccess shouldBe true
    findResultFor(items, file2) match {
      case Failure(e) =>
        e.head should be(ValidationErrorCode.NoAlbum)
      case r => fail("Unexpected result: " + r)
    }
  }

  /**
    * Runs a validation flow and checks whether the order of processed elements
    * has changed. This indicates parallel processing. (As this is not
    * deterministic, multiple test runs are made.)
    *
    * @param command         the command
    * @param expChangedOrder flag whether the order should be changed
    */
  private def checkFileProcessingOrder(command: OpenValidationWindowCommand, expChangedOrder: Boolean): Unit = {
    val FileCount = 32
    val files = (1 to FileCount).map(ValidationTestHelper.file(_)).toList
    val orderedUris = files map (_.uri)
    checkProcessingOrder(command, List(files), orderedUris, expChangedOrder)
  }

  it should "validate files in parallel" in {
    val command = new OpenFileValidationWindowCommand(mock[Locator], createClientApp())
    command.parallelism should be(OpenValidationWindowCommand.DefaultFileValidationParallelism)

    checkFileProcessingOrder(command, expChangedOrder = true)
  }

  it should "read the parallelism factor from the configuration" in {
    val app = createClientAppWithParallelismDisabled()
    val command = new OpenFileValidationWindowCommand(mock[Locator], app)

    checkFileProcessingOrder(command, expChangedOrder = false)
  }

  it should "return the title in the display function if available" in {
    val file = ValidationTestHelper.file(1)
    val ExpResult = ValidationTestHelper.albumName(1) + "/" + file.metaData.title.get
    val command = new OpenFileValidationWindowCommand(mock[Locator], createClientApp())
    val valItem = checkValidationFlow(command, List(file)).head

    valItem.displayFunc(valItem.uri) should be(ExpResult)
  }

  /**
    * Checks the display function for file validation if there is no title in
    * the meta data.
    *
    * @param uri       the URI of the file
    * @param expResult the expected result
    */
  private def checkFileValidationDisplayFuncNoTitle(uri: String, expResult: String): Unit = {
    val data = ValidationTestHelper.metaData(1).copy(title = None)
    val file = MediaFile(ValidationTestHelper.testMedium(1), uri, data)
    val command = new OpenFileValidationWindowCommand(mock[Locator], createClientApp())
    val valItem = checkValidationFlow(command, List(file)).head

    valItem.displayFunc(valItem.uri) should be(expResult)
  }

  it should "return the album and title components in the display function if there is no title" in {
    val AlbumComp = "my_album"
    val LastUriComp = "mySong.mp3"
    val Uri = s"https://testmusic.org/my_medium/$AlbumComp/$LastUriComp"

    checkFileValidationDisplayFuncNoTitle(Uri, AlbumComp + "/" + LastUriComp)
  }

  it should "return the full URI in the display function if there are no components" in {
    val Uri = "anUriWithoutComponents"

    checkFileValidationDisplayFuncNoTitle(Uri, Uri)
  }

  it should "handle a trailing slash in the URI in the display function" in {
    val Uri = "foo/"

    checkFileValidationDisplayFuncNoTitle(Uri, Uri)
  }

  it should "handle URIs with backslash in the display function" in {
    val AlbumComp = "album"
    val LastUriComp = "theSong.ogg"
    val Uri = s"C:\\data\\music\\medium\\$AlbumComp\\$LastUriComp"

    checkFileValidationDisplayFuncNoTitle(Uri, AlbumComp + "/" + LastUriComp)
  }

  "An OpenAlbumValidationWindowCommand" should "pass the script locator" in {
    val locator = mock[Locator]

    val command = new OpenAlbumValidationWindowCommand(locator, createClientApp())
    command.getLocator should be(locator)
  }

  it should "provide a correct validation flow" in {
    val SongCounts = List(8, 12)
    val validFiles = SongCounts.zipWithIndex.flatMap(t => ValidationTestHelper.albumFiles(t._2, t._1))
    val inconsistentData = ValidationTestHelper.metaData(songIdx = 32).copy(inceptionYear = Some(1960))
    val inconsistentFile = ValidationTestHelper.file(32).copy(metaData = inconsistentData)
    val allFiles = inconsistentFile :: validFiles
    val command = new OpenAlbumValidationWindowCommand(mock[Locator], createClientApp())

    val items = checkValidationFlow(command, allFiles)
    items should have size 2
    findResultFor(items, ValidationTestHelper.Medium, ValidationTestHelper.albumUri(0)).isSuccess shouldBe true
    findResultFor(items, ValidationTestHelper.Medium, ValidationTestHelper.albumUri(1)) match {
      case Failure(e) =>
        val codes = e.head :: e.tail.toList
        codes should contain only(ValidationErrorCode.InconsistentTrackNumber,
          ValidationErrorCode.InconsistentInceptionYear)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle an empty medium" in {
    val command = new OpenAlbumValidationWindowCommand(mock[Locator], createClientApp())

    val items = checkValidationFlow(command, List())
    items should have size 0
  }

  /**
    * Helper method for testing the parallelism used by album validation.
    *
    * @param app             the client application
    * @param expChangedOrder the expected change order flag
    */
  private def checkAlbumValidationParallelism(app: ClientApplication, expChangedOrder: Boolean): Unit = {
    val AlbumCount = 32
    val files = (1 to AlbumCount).map(i => ValidationTestHelper.albumFiles(i, i + 1, mediumIdx = i)).toList
    val orderedUris = (1 to AlbumCount).map(i => ValidationTestHelper.albumUri(i, mediumIdx = i)).toList
    val command = new OpenAlbumValidationWindowCommand(mock[Locator], app)

    checkProcessingOrder(command, files, orderedUris, expChangedOrder)
  }

  it should "validate albums in parallel" in {
    checkAlbumValidationParallelism(createClientApp(), expChangedOrder = true)
  }

  it should "read the parallelism factor from the configuration" in {
    checkAlbumValidationParallelism(createClientAppWithParallelismDisabled(), expChangedOrder = false)
  }

  it should "handle file URIs using a backslash" in {
    val files = ValidationTestHelper.albumFiles(1, 4)
      .map(f => f.copy(orgUri = f.uri.replace('/', '\\')))
    val command = new OpenAlbumValidationWindowCommand(mock[Locator], createClientApp())

    val items = checkValidationFlow(command, files)
    val item = items.head
    item.uri should be(ValidationTestHelper.albumUri(1))
    item.displayFunc(item.uri) should not include "\\"
  }

  it should "handle file URIs with only a single component" in {
    val file = MediaFile(ValidationTestHelper.Medium, "theSong.mp3", ValidationTestHelper.metaData(1))
    val command = new OpenAlbumValidationWindowCommand(mock[Locator], createClientApp())

    val items = checkValidationFlow(command, List(file))
    items.head.uri should be("")
  }

  /**
    * Checks whether the display function for albums returns the expected URI.
    *
    * @param albumUri the URI of the album
    */
  private def checkAlbumValidationDisplayFunc(albumUri: String, expResult: String): Unit = {
    val files = (1 to 8).map { i =>
      MediaFile(ValidationTestHelper.Medium, albumUri + "/file" + i, ValidationTestHelper.metaData(i))
    }.toList
    val command = new OpenAlbumValidationWindowCommand(mock[Locator], createClientApp())

    val items = checkValidationFlow(command, files)
    items should have size 1
    items.head.displayFunc(items.head.uri) should be(expResult)
  }

  it should "return the correct album URI by the display function" in {
    val AlbumUri = "/music/test/artist/album"
    val ExpUri = "artist/album"

    checkAlbumValidationDisplayFunc(AlbumUri, ExpUri)
  }

  it should "handle a URI in the display function with only a single component" in {
    val AlbumUri = "theAlbum"

    checkAlbumValidationDisplayFunc(AlbumUri, AlbumUri)
  }

  it should "URL-decode URIs in the display function" in {
    val AlbumUri = "/music/test/Artist%201%2FArtist%202/The%20Album"
    val ExpUri = "Artist 1/Artist 2/The Album"

    checkAlbumValidationDisplayFunc(AlbumUri, ExpUri)
  }
}
