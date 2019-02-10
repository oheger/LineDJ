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

import java.util
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.ValidationErrorCode.ValidationErrorCode
import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.{MediaFile, ValidationErrorCode}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{ValidatedItem, ValidationErrorItem, ValidationFlow}
import de.oliver_heger.linedj.platform.app.{ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MetaDataService
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener}
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Matchers.{eq => eqArg, _}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import scalaz.Kleisli
import scalaz.Scalaz._

import scala.concurrent.Future
import scala.concurrent.duration._

object ValidationControllerSpec {
  /** A timeout setting. */
  private val Timeout = 3.seconds

  /** Holds the number of files to be generated for each test medium. */
  private val NumberOfFilesPerMedium = Vector(8, 4, 6, 8, 8, 6, 8)

  /** The number of available media. */
  private val MediaCount = NumberOfFilesPerMedium.length

  /** The test media that are currently available. */
  private val TestMedia = ValidationTestHelper.createAvailableMedia(MediaCount)

  /** A map with test files per test medium. */
  private val TestFilesPerMedium = generateFilesOnTestMedia()

  /** A map with information about files that are invalid. */
  private val InvalidFiles = generateInvalidFiles(TestFilesPerMedium)

  /** A map with error items for invalid files. */
  private val ErrorItems = generateErrorItems(InvalidFiles)

  /**
    * Generates a map with test files contained on each medium.
    *
    * @return the files for each test medium
    */
  private def generateFilesOnTestMedia(): Map[MediumID, List[MediaFile]] =
    TestMedia.media.map { e =>
      val mediumIdx = ValidationTestHelper.extractMediumIndex(e._1)
      val files = (1 to NumberOfFilesPerMedium(mediumIdx - 1))
        .map(fileIdx => ValidationTestHelper.file(fileIdx, mediumIdx = mediumIdx))
      (e._1, files.toList)
    }

  /**
    * Generates a map with files that should be considered invalid by the test
    * flow. The files are assigned an error code.
    *
    * @param testFiles a map with all test files
    * @return the map with files that are invalid and their error code
    */
  private def generateInvalidFiles(testFiles: Map[MediumID, List[MediaFile]]): Map[MediaFile, ValidationErrorCode] = {
    val ErrorCodes = ValidationErrorCode.values.toArray

    def fetchErrorCode(index: Int): ValidationErrorCode =
      ErrorCodes((index / 2) % ErrorCodes.length)

    val allFiles = testFiles.values.flatten
    allFiles.zipWithIndex
      .filter(_._2 % 2 == 0)
      .map(t => (t._1, fetchErrorCode(t._2)))
      .toMap
  }

  /**
    * Generates a map with error items to be assigned to invalid files.
    *
    * @param files the map with invalid files
    * @return a map with error items assigned to file URIs
    */
  private def generateErrorItems(files: Map[MediaFile, ValidationErrorCode]): Map[String, ValidationErrorItem] =
    files.map { e =>
      val errorItem = ValidationErrorItem(e._1.mediumID.mediumURI, e._1.uri, e._2.toString, null,
        null)  //TODO set severity
      (e._1.uri, errorItem)
    }
}


/**
  * Test class of ''ValidationController''.
  */
class ValidationControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  def this() = this(ActorSystem("ValidationControllerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import ValidationControllerSpec._

  "A ValidationController" should "populate the table model" in {
    val helper = new ControllerTestHelper

    helper.openWindow()
      .runSyncTasksToPopulateTable()
    helper.tableModel should contain theSameElementsAs ErrorItems.values
  }

  it should "ignore the window activated event" in {
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowActivated(_))
  }

  it should "ignore the window closing event" in {
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowClosing(_))
  }

  it should "ignore the window closed event" in {
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowClosed(_))
  }

  it should "ignore the window deactivated event" in {
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowDeactivated(_))
  }

  it should "ignore the window de-iconified event" in {
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowDeiconified(_))
  }

  it should "ignore the window iconified event" in {
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowIconified(_))
  }

  it should "update the UI in configurable chunks" in {
    val ChunkSize = 2
    val helper = new ControllerTestHelper

    helper.updateConfiguration(ValidationController.PropUIUpdateChunkSize, ChunkSize)
      .openWindow()
      .runSyncTasksToPopulateTable()
    verify(helper.tableHandler).rowsInserted(0, ChunkSize - 1)
    verify(helper.tableHandler).rowsInserted(ChunkSize, 2 * ChunkSize - 1)
    verify(helper.tableHandler).rowsInserted(4 * ChunkSize, 5 * ChunkSize - 1)
  }

  it should "use a correct default update chunk size" in {
    val helper = new ControllerTestHelper

    helper.openWindow()
      .runSyncTasks(() => helper.tableModel.size > 0)
    helper.tableModel should have size ValidationController.DefaultUIUpdateChunkSize
    verify(helper.tableHandler).rowsInserted(0, ValidationController.DefaultUIUpdateChunkSize - 1)
  }

  it should "sort the table when the validation stream is complete" in {
    val ExpectedErrors = (1 to MediaCount).flatMap { idx =>
      TestFilesPerMedium(ValidationTestHelper.testMedium(idx))
        .filter(f => ErrorItems.contains(f.uri))
        .map(f => ErrorItems(f.uri))
    }
    val helper = new ControllerTestHelper

    helper.openWindow()
      .runSyncTasksToPopulateTable()
      .runNextSyncTask()
    helper.tableModel should contain theSameElementsInOrderAs ExpectedErrors
    verify(helper.tableHandler).tableDataChanged()
  }

  it should "compare error items by media names ignoring case" in {
    val item1 = ValidationErrorItem("B-Medium", "foo", "error", null, null)
    val item2 = ValidationErrorItem("a-Medium", "foo", "error", null, null)

    ValidationController.ErrorItemComparator.compare(item1, item2) should be > 0
  }

  it should "compare error items by names ignoring case" in {
    val item1 = ValidationErrorItem("medium", "a-file", "error", null, null)
    val item2 = ValidationErrorItem("medium", "B-file", "error", null, null)

    ValidationController.ErrorItemComparator.compare(item1, item2) should be < 0
  }

  it should "compare error items by error names ignoring case" in {
    val item1 = ValidationErrorItem("medium", "file", "a-error", null, null)
    val item2 = ValidationErrorItem("medium", "file", "B-error", null, null)

    ValidationController.ErrorItemComparator.compare(item1, item2) should be < 0
  }

  it should "ignore errors during stream processing" in {
    val ErrorMedia = AvailableMedia(TestMedia.media +
      (ValidationTestHelper.testMedium(42) -> ValidationTestHelper.testMediumInfo(42)))
    val helper = new ControllerTestHelper(allMedia = ErrorMedia)

    helper.openWindow()
      .runSyncTasksToPopulateTable()
  }

  it should "close the window" in {
    val helper = new ControllerTestHelper
    helper.openWindow()

    helper.controller.closeWindow()
    helper.verifyWindowClosed()
  }

  /**
    * A test helper class managing a test instance and all its dependencies.
    *
    * @param allMedia the media to be returned by the meta data service
    */
  private class ControllerTestHelper(allMedia: AvailableMedia = TestMedia) {
    /** The list acting as table model. */
    val tableModel: java.util.List[AnyRef] = new util.ArrayList

    /** Mock for the table handler. */
    val tableHandler: TableHandler = createTableHandler()

    /** A queue for storing the tasks passed to the synchronizer. */
    private val syncTaskQueue = new LinkedBlockingQueue[Runnable]

    /** Mock for the message bus. */
    private val messageBus = mock[MessageBus]

    /** Mock for the window associated with the controller. */
    private val window = mock[Window]

    /** The configuration for the test application. */
    private val configuration = new PropertiesConfiguration

    /** The controller to be tested. */
    val controller: ValidationController = createController()

    /**
      * Invokes the given sender function with the controller as window
      * listener and a window event. This can be used to check the event
      * handling methods of the controller.
      *
      * @param sender        the function to call the event listener
      * @param checkNoAction a flag whether to check that the window was not
      *                      modified
      * @return this test helper
      */
    def sendWindowEvent(sender: (WindowListener, WindowEvent) => Unit, checkNoAction: Boolean = true):
    ControllerTestHelper = {
      val event = new WindowEvent(this, window, WindowEvent.Type.WINDOW_OPENED)
      sender(controller, event)
      if (checkNoAction) {
        verifyZeroInteractions(window)
      }
      this
    }

    /**
      * Sends a window opened event to the test controller.
      *
      * @return this test helper
      */
    def openWindow(): ControllerTestHelper =
      sendWindowEvent(_.windowOpened(_), checkNoAction = false)

    /**
      * Verifies that the controller's window has been closed.
      *
      * @return this test helper
      */
    def verifyWindowClosed(): ControllerTestHelper = {
      verify(window).close(false)
      this
    }

    /**
      * Obtains the next task passed to the Sync object and executes it.
      *
      * @return this test helper
      */
    def runNextSyncTask(): ControllerTestHelper = {
      val task = syncTaskQueue.poll(Timeout.toMillis, TimeUnit.MILLISECONDS)
      task should not be null
      task.run()
      this
    }

    /**
      * Executes sync tasks until the given function returns '''true'''. This
      * can be used to wait for a specific condition.
      *
      * @param until the condition function
      * @return this test helper
      */
    def runSyncTasks(until: () => Boolean): ControllerTestHelper = {
      runNextSyncTask()
      if (until()) this
      else runSyncTasks(until)
    }

    /**
      * Executes sync tasks until the table model has the expected content.
      *
      * @return this test helper
      */
    def runSyncTasksToPopulateTable(): ControllerTestHelper =
      runSyncTasks(() => tableModel.size() == InvalidFiles.size)

    /**
      * Updates the configuration of the test application. This can be used to
      * modify the behavior of the controller.
      *
      * @param key   the key to be set in the configuration
      * @param value the value
      * @return this test helper
      */
    def updateConfiguration(key: String, value: Any): ControllerTestHelper = {
      configuration.setProperty(key, value)
      this
    }

    /**
      * Creates the mock for the client application.
      *
      * @return the mock application
      */
    private def createApplication(): ClientApplication = {
      val app = mock[ClientApplication]
      val appCtx = mock[ClientApplicationContext]
      Mockito.when(appCtx.actorSystem).thenReturn(system)
      Mockito.when(appCtx.messageBus).thenReturn(messageBus)
      Mockito.when(appCtx.managementConfiguration).thenReturn(configuration)
      Mockito.when(app.clientApplicationContext).thenReturn(appCtx)
      app
    }

    /**
      * Creates a mock for the table handler.
      *
      * @return the mock table handler
      */
    private def createTableHandler(): TableHandler = {
      val handler = mock[TableHandler]
      Mockito.when(handler.getModel).thenReturn(tableModel)
      handler
    }

    /**
      * Creates the mock for the object to sync with the UI thread. The mock
      * does not actually execute passed in tasks; it rather adds them to a
      * queue.
      *
      * @return the mock for the sync object
      */
    private def createSynchronizer(): GUISynchronizer = {
      val sync = mock[GUISynchronizer]
      Mockito.when(sync.asyncInvoke(any(classOf[Runnable]))).thenAnswer((invocation: InvocationOnMock) => {
        val task = invocation.getArguments.head.asInstanceOf[Runnable]
        syncTaskQueue offer task
      })
      sync
    }

    /**
      * Generates a flow that simulates file validation. For each file it is
      * checked whether it is in the list of invalid files. If so, a
      * corresponding validation failure is generated.
      *
      * @return the validation flow
      */
    private def createValidationFlow(): ValidationFlow =
      Flow[MediaFile].map { f =>
        val result = InvalidFiles get f match {
          case Some(code) =>
            code.failureNel[Any]
          case None =>
            f.successNel[ValidationErrorCode]
        }
        ValidatedItem(f.mediumID, f.uri, identity, result)
      }

    /**
      * Creates a mock item converter that returns the predefined error items
      * for the referenced files.
      *
      * @return the mock item converter
      */
    private def createConverter(): ValidationItemConverter = {
      val converter = mock[ValidationItemConverter]
      Mockito.when(converter.generateTableItems(eqArg(allMedia), any(classOf[ValidatedItem])))
        .thenAnswer((invocation: InvocationOnMock) => {
          val valItem = invocation.getArguments()(1).asInstanceOf[ValidatedItem]
          List(ErrorItems(valItem.uri))
        })
      converter
    }

    /**
      * Creates a mock for the meta data service. The service returns the
      * prepared test files and their meta data.
      *
      * @return the mock meta data service
      */
    private def createMetaDataService(): MetaDataService[AvailableMedia, Map[String, MediaMetaData]] = {
      val service = mock[MetaDataService[AvailableMedia, Map[String, MediaMetaData]]]
      val resMedia = Kleisli[Future, MessageBus, AvailableMedia] { bus =>
        bus should be(messageBus)
        Future.successful(allMedia)
      }
      Mockito.when(service.fetchMedia()).thenReturn(resMedia)

      Mockito.when(service.fetchMetaDataOfMedium(any(classOf[MediumID])))
        .thenAnswer((invocation: InvocationOnMock) => {
          val mid = invocation.getArguments.head.asInstanceOf[MediumID]
          Kleisli[Future, MessageBus, Map[String, MediaMetaData]] { bus =>
            bus should be(messageBus)
            val files = TestFilesPerMedium(mid)
            val data = files.map(f => (f.uri, f.metaData)).toMap
            Future.successful(data)
          }
        })
      service
    }

    /**
      * Creates the test instance.
      *
      * @return the test instance
      */
    private def createController(): ValidationController =
      new ValidationController(app = createApplication(), metaDataService = createMetaDataService(),
        tableHandler = tableHandler, sync = createSynchronizer(), validationFlow = createValidationFlow(),
        converter = createConverter())
  }

}
