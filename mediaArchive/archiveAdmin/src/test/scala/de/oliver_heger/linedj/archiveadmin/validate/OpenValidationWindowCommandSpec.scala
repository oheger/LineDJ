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

package de.oliver_heger.linedj.archiveadmin.validate

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.testkit.TestKit
import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.{MediaFile, ValidationErrorCode}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{ValidatedItem, ValidationResult}
import de.oliver_heger.linedj.platform.app.{ClientApplication, ClientApplicationContext}
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.Locator
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{eq => eqArg}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import scalaz.Failure

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for the command classes to open the validation dialog.
  */
class OpenValidationWindowCommandSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
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
    * Runs a stream with the validation flow returned by the given command.
    * The passed in files are sent to the stream; the resulting validated items
    * are returned.
    *
    * @param command the command
    * @param files   the files to be used as input
    * @return the validated items
    */
  private def checkValidationFlow(command: OpenValidationWindowCommand, files: List[MediaFile]):
  List[ValidatedItem] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val builderData = mock[ApplicationBuilderData]
    command.prepareBuilderData(builderData)
    val captor = ArgumentCaptor.forClass(classOf[Flow[MediaFile, ValidatedItem, Any]])
    verify(builderData).addProperty(eqArg(OpenValidationWindowCommand.PropFlow), captor.capture())

    val source = Source(files)
    val sink = Sink.fold[List[ValidatedItem], ValidatedItem](List.empty)((lst, item) => item :: lst)
    val flow = captor.getValue
    val futResult = source.via(flow).runWith(sink)
    Await.result(futResult, 3.seconds).reverse
  }

  /**
    * Returns the validation result of the item from the given list that
    * corresponds to the specified media file.
    *
    * @param items the sequence of validated items
    * @param file  the file
    * @return the validation result for this file
    */
  private def findResultFor(items: Seq[ValidatedItem], file: MediaFile): ValidationResult =
    items.find(item => item.medium == file.mediumID && item.uri == file.uri).get.result

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
  private def checkProcessingOrder(command: OpenValidationWindowCommand, expChangedOrder: Boolean): Unit = {
    val FileCount = 32
    val MaxAttempts = 16
    val files = (1 to FileCount).map(ValidationTestHelper.file(_)).toList
    val orderedUris = files map (_.uri)

    def checkProcessingOrderChanged(attempt: Int): Boolean =
      if (attempt >= MaxAttempts) false
      else {
        val items = checkValidationFlow(command, files)
        val itemUris = items.map(_.uri)
        if (itemUris != orderedUris) true
        else checkProcessingOrderChanged(attempt + 1)
      }

    checkProcessingOrderChanged(1) shouldBe expChangedOrder
  }

  it should "validate files in parallel" in {
    val command = new OpenFileValidationWindowCommand(mock[Locator], createClientApp())
    command.parallelism should be(OpenValidationWindowCommand.DefaultFileValidationParallelism)

    checkProcessingOrder(command, expChangedOrder = true)
  }

  it should "read the parallelism factor from the configuration" in {
    val app = createClientApp()
    app.clientApplicationContext.managementConfiguration
      .addProperty(OpenValidationWindowCommand.PropFileValidationParallelism, 1)
    val command = new OpenFileValidationWindowCommand(mock[Locator], app)

    checkProcessingOrder(command, expChangedOrder = false)
  }

  it should "return the title in the display function if available" in {
    val file = ValidationTestHelper.file(1)
    val command = new OpenFileValidationWindowCommand(mock[Locator], createClientApp())
    val valItem = checkValidationFlow(command, List(file)).head

    valItem.displayFunc("some uri") should be(file.metaData.title.get)
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
    val file = MediaFile(ValidationTestHelper.testMedium(1), ValidationTestHelper.fileUri(1), data)
    val command = new OpenFileValidationWindowCommand(mock[Locator], createClientApp())
    val valItem = checkValidationFlow(command, List(file)).head

    valItem.displayFunc(uri) should be(expResult)
  }

  it should "return the last URI component in the display function if there is no title" in {
    val LastUriComp = "mySong.mp3"
    val Uri = "https://testmusic.org/my_medium/my_album/" + LastUriComp

    checkFileValidationDisplayFuncNoTitle(Uri, LastUriComp)
  }

  it should "return the full URI in the display function if there are no components" in {
    val Uri = "anUriWithoutComponents"

    checkFileValidationDisplayFuncNoTitle(Uri, Uri)
  }

  it should "handle a trailing slash in the URI in the display function" in {
    val Uri = "foo/"

    checkFileValidationDisplayFuncNoTitle(Uri + "/", "")
  }

  it should "handle URIs with backslash in the display function" in {
    val LastUriComp = "theSong.ogg"
    val Uri = "C:\\data\\music\\medium\\album\\" + LastUriComp

    checkFileValidationDisplayFuncNoTitle(Uri, LastUriComp)
  }
}
