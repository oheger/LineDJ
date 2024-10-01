/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.archiveadmin.validate.MetadataValidator.ValidationErrorCode.ValidationErrorCode
import de.oliver_heger.linedj.archiveadmin.validate.MetadataValidator.{MediaFile, Severity, ValidationErrorCode}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{ValidatedItem, ValidationErrorItem, ValidationFlow}
import de.oliver_heger.linedj.platform.app.{ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MetadataService
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener}
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.KillSwitch
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentMatchers.{any, anyBoolean, anyInt}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.verification.VerificationMode
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scalaz.Kleisli
import scalaz.Scalaz._

import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Future
import scala.concurrent.duration._

object ValidationControllerSpec:
  /** A timeout setting. */
  private implicit val Timeout: FiniteDuration = 3.seconds

  /** Timeout when waiting for an event that is not expected. */
  private val UnexpectedTimeout = 500.millis

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

  /** The number of validation warnings in the test validation items. */
  private val WarningCount = countValidationWarnings(ErrorItems.values)

  /** The number of validation errors in the test validation items. */
  private val ErrorCount = ErrorItems.size - WarningCount

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
  private def generateInvalidFiles(testFiles: Map[MediumID, List[MediaFile]]): Map[MediaFile, ValidationErrorCode] =
    val ErrorCodes = ValidationErrorCode.values.toArray

    def fetchErrorCode(index: Int): ValidationErrorCode =
      ErrorCodes((index / 2) % ErrorCodes.length)

    val allFiles = testFiles.values.flatten
    allFiles.zipWithIndex
      .filter(_._2 % 2 == 0)
      .map(t => (t._1, fetchErrorCode(t._2)))
      .toMap

  /**
    * Generates a map with error items to be assigned to invalid files.
    *
    * @param files the map with invalid files
    * @return a map with error items assigned to file URIs
    */
  private def generateErrorItems(files: Map[MediaFile, ValidationErrorCode]): Map[String, ValidationErrorItem] =
    files.map { e =>
      val errorItem = ValidationErrorItem(e._1.mediumID.mediumURI, e._1.uri, e._2.toString, null,
        MetadataValidator.severity(e._2))
      (e._1.uri, errorItem)
    }

  /**
    * Determines the number of validation warnings in the list of test error
    * items.
    *
    * @param items the test error items
    * @return the number of validation warnings in this list
    */
  private def countValidationWarnings(items: Iterable[ValidationErrorItem]): Int =
    items.count(_.severity == Severity.Warning)


/**
  * Test class of ''ValidationController''.
  */
class ValidationControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike with Matchers
  with BeforeAndAfterAll with MockitoSugar:
  def this() = this(ActorSystem("ValidationControllerSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  import ValidationControllerSpec._

  "A ValidationController" should "populate the table model" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .runSyncTasksToPopulateTable()
    helper.tableModel should contain theSameElementsAs ErrorItems.values

  it should "ignore the window activated event" in:
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowActivated(_))

  it should "ignore the window closed event" in:
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowClosed(_))

  it should "ignore the window deactivated event" in:
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowDeactivated(_))

  it should "ignore the window de-iconified event" in:
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowDeiconified(_))

  it should "ignore the window iconified event" in:
    val helper = new ControllerTestHelper

    helper.sendWindowEvent(_.windowIconified(_))

  it should "update the UI in configurable chunks" in:
    val ChunkSize = 2
    val helper = new ControllerTestHelper

    helper.updateConfiguration(ValidationController.PropUIUpdateChunkSize, ChunkSize)
      .openWindow()
      .runSyncTasksToPopulateTable()
    verify(helper.tableHandler).rowsInserted(0, ChunkSize - 1)
    verify(helper.tableHandler).rowsInserted(ChunkSize, 2 * ChunkSize - 1)
    verify(helper.tableHandler).rowsInserted(4 * ChunkSize, 5 * ChunkSize - 1)

  it should "use a correct default update chunk size" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .runSyncTasks(() => helper.tableModel.size > 0)
    helper.tableModel should have size ValidationController.DefaultUIUpdateChunkSize
    verify(helper.tableHandler).rowsInserted(0, ValidationController.DefaultUIUpdateChunkSize - 1)

  it should "sort the table when the validation stream is complete" in:
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

  it should "compare error items by media names ignoring case" in:
    val item1 = ValidationErrorItem("B-Medium", "foo", "error", null, null)
    val item2 = ValidationErrorItem("a-Medium", "foo", "error", null, null)

    ValidationController.ErrorItemComparator.compare(item1, item2) should be > 0

  it should "compare error items by names ignoring case" in:
    val item1 = ValidationErrorItem("medium", "a-file", "error", null, null)
    val item2 = ValidationErrorItem("medium", "B-file", "error", null, null)

    ValidationController.ErrorItemComparator.compare(item1, item2) should be < 0

  it should "order validation errors before warnings" in:
    val item1 = ValidationErrorItem("medium", "file", "a-error", null, Severity.Warning)
    val item2 = ValidationErrorItem("medium", "file", "b-error", null, Severity.Error)

    ValidationController.ErrorItemComparator.compare(item1, item2) should be > 0

  it should "compare error items by error names ignoring case" in:
    val item1 = ValidationErrorItem("medium", "file", "a-error", null, Severity.Error)
    val item2 = ValidationErrorItem("medium", "file", "B-error", null, Severity.Error)

    ValidationController.ErrorItemComparator.compare(item1, item2) should be < 0

  it should "ignore errors during stream processing" in:
    val ErrorMedia = AvailableMedia(
      (ValidationTestHelper.testMedium(42), ValidationTestHelper.testMediumInfo(42)) :: TestMedia.mediaList)
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initAvailableMedia(Future.successful(ErrorMedia))
      .runSyncTasksToPopulateTable()

  it should "close the window" in:
    val helper = new ControllerTestHelper
    helper.openWindow()

    helper.controller.closeWindow()
    helper.verifyWindowClosed()

  it should "update the status line for fetching available media" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .validateStatusHandler(_.fetchingMedia())

  it should "update the status line during stream processing" in:
    val helper = new ControllerTestHelper
    helper.openWindow()

    helper.hasValidationErrors shouldBe false
    helper.runSyncTasks(() => helper.hasValidationErrors && helper.hasValidationWarnings)

  it should "set a successful status result if no errors occurred" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .runSyncTasksToPopulateTable()
      .runNextSyncTask()
      .checkValidationResults(ErrorCount, WarningCount, successful = true)

  it should "set a failure status result if there were errors during processing" in:
    val ErrorMedia = AvailableMedia(
      (ValidationTestHelper.testMedium(42) -> ValidationTestHelper.testMediumInfo(42)) :: TestMedia.mediaList)
    val helper = new ControllerTestHelper

    helper.initAvailableMedia(Future.successful(ErrorMedia))
      .openWindow()
      .runSyncTasksToPopulateTable()
      .runNextSyncTask()
      .checkValidationResults(ErrorCount, WarningCount, successful = false)

  it should "process the retrieved available media in the UI thread" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .expectNoSourceCreation()

  it should "set a failed status result if available media could not be fetched" in:
    val helper = new ControllerTestHelper

    helper.initAvailableMedia(Future.failed(new Exception("No media")))
      .openWindow()
      .runNextSyncTask()
      .expectNoSourceCreation()
      .validateStatusHandler(_.validationResults(0, 0, successful = false))

  it should "cancel the validation stream if the window is closed" in:
    val helper = new ControllerTestHelper

    helper.delaySource()
      .openWindow()
      .runNextSyncTask()
      .closeWindow()
      .validateStreamCanceled()
      .validateStatusHandler(_.validationResults(anyInt(), anyInt(), anyBoolean()), never())

  it should "handle a closed window before metadata has been fetched" in:
    val helper = new ControllerTestHelper

    helper.withMockKillSwitch()
      .openWindow()
      .closeWindow()
      .runNextSyncTask()
      .expectNoSourceCreation()
      .validateNoStreamCancellation()

  it should "handle a closed window before metadata has been fetched with a failure" in:
    val helper = new ControllerTestHelper

    helper.initAvailableMedia(Future.failed(new Exception("No media")))
      .openWindow()
      .closeWindow()
      .runNextSyncTask()
      .validateStatusHandler(_.validationResults(anyInt(), anyInt(), anyBoolean()), never())

  it should "reset the kill switch when stream processing is complete" in:
    val helper = new ControllerTestHelper

    helper.withMockKillSwitch()
      .openWindow()
      .runSyncTasksToPopulateTable()
      .runNextSyncTask()
      .closeWindow()
      .validateNoStreamCancellation()

  /**
    * A test helper class managing a test instance and all its dependencies.
    */
  private class ControllerTestHelper:
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

    /** Mock for the status line handler. */
    private val statusHandler = createStatusHandler()

    /** The configuration for the test application. */
    private val configuration = new PropertiesConfiguration

    /** Mock for the metadata service. */
    private val metaDataService = createMetadataService()

    /** Mock for the validation item converter. */
    private val converter = createConverter()

    /**
      * A latch that is triggered when the source of the validation stream is
      * created. This is used to find out if this part of the validation
      * process is run.
      */
    private val sourceCreationLatch = new CountDownLatch(1)

    /** A flag whether the converter mock should do some extra checks. */
    private val checkMediaInConverter = new AtomicBoolean(true)

    /** A flag whether the validation source should be delayed. */
    private val delaySourceFlag = new AtomicBoolean

    /** Stores a mock kill switch to check cancellation handling. */
    private val mockKillSwitch = new AtomicReference[Option[KillSwitch]](None)

    /** The controller to be tested. */
    val controller: ValidationController[MediaFile] = createController()

    /**
      * The current number of validation errors passed to the status line
      * handler.
      */
    private var validationErrorCount = 0

    /**
      * The current number of validation warnings passed to the status line
      * handler.
      */
    private var validationWarningCount = 0

    /** The success flag passed to the status line handler. */
    private var statusResult: Option[Boolean] = None

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
    ControllerTestHelper =
      val event = new WindowEvent(this, window, WindowEvent.Type.WINDOW_OPENED)
      sender(controller, event)
      if checkNoAction then
        verifyNoInteractions(window)
      this

    /**
      * Sends a window opened event to the test controller.
      *
      * @return this test helper
      */
    def openWindow(): ControllerTestHelper =
      sendWindowEvent(_.windowOpened(_), checkNoAction = false)

    /**
      * Sends a window closing event to the test controller.
      *
      * @return this test helper
      */
    def closeWindow(): ControllerTestHelper =
      sendWindowEvent(_.windowClosing(_), checkNoAction = false)

    /**
      * Verifies that the controller's window has been closed.
      *
      * @return this test helper
      */
    def verifyWindowClosed(): ControllerTestHelper =
      verify(window).close(false)
      this

    /**
      * Obtains the next task passed to the Sync object and executes it.
      *
      * @param timeout a timeout when waiting for tasks in the queue
      * @return this test helper
      */
    def runNextSyncTask()(implicit timeout: FiniteDuration): ControllerTestHelper =
      val task = syncTaskQueue.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
      if task == null then throw new NoSuchElementException("No sync task added to queue")
      task.run()
      this

    /**
      * Executes sync tasks until the given function returns '''true'''. This
      * can be used to wait for a specific condition.
      *
      * @param until   the condition function
      * @param timeout a timeout when waiting for sync tasks
      * @return this test helper
      */
    def runSyncTasks(until: () => Boolean)(implicit timeout: FiniteDuration): ControllerTestHelper =
      runNextSyncTask()(timeout)
      if until() then this
      else runSyncTasks(until)(timeout)

    /**
      * Executes sync tasks until the table model has the expected content.
      *
      * @param timeout a timeout when waiting for sync tasks
      * @return this test helper
      */
    def runSyncTasksToPopulateTable()(implicit timeout: FiniteDuration): ControllerTestHelper =
      runSyncTasks(() => tableModel.size() == InvalidFiles.size)(timeout)

    /**
      * Checks that the validation stream is canceled. This is done by
      * waiting for the table to be populated and expecting a timeout.
      *
      * @return this test helper
      */
    def validateStreamCanceled(): ControllerTestHelper =
      intercept[NoSuchElementException]:
        runSyncTasksToPopulateTable()(UnexpectedTimeout)
      this

    /**
      * Updates the configuration of the test application. This can be used to
      * modify the behavior of the controller.
      *
      * @param key   the key to be set in the configuration
      * @param value the value
      * @return this test helper
      */
    def updateConfiguration(key: String, value: Any): ControllerTestHelper =
      configuration.setProperty(key, value)
      this

    /**
      * Returns a flag whether validation errors are present.
      *
      * @return the flag whether validation errors are present
      */
    def hasValidationErrors: Boolean = validationErrorCount > 0

    /**
      * Returns a flag whether validation warnings are present.
      *
      * @return the flag whether validation warnings are present
      */
    def hasValidationWarnings: Boolean = validationWarningCount > 0

    /**
      * Checks whether the given numbers of validation errors and warnings have
      * been detected and the operation completed in the expected way.
      *
      * @param errors     the expected number of errors
      * @param warnings   the expected number of warnings
      * @param successful the expected success flag
      * @return this test helper
      */
    def checkValidationResults(errors: Int, warnings: Int, successful: Boolean): ControllerTestHelper =
      validationErrorCount should be(errors)
      validationWarningCount should be(warnings)
      statusResult.contains(successful) shouldBe true
      this

    /**
      * Allows a custom validation on the status line handler. Invokes the
      * given validation function on the handler mock with verification
      * enabled.
      *
      * @param f    the validation function
      * @param mode the optional verification mode (defaults to once)
      * @return this test helper
      */
    def validateStatusHandler(f: StatusLineHandler => Unit, mode: VerificationMode = times(1)):
    ControllerTestHelper =
      f(verify(statusHandler, mode))
      this

    /**
      * Checks that no validation source is created.
      *
      * @return this test helper
      */
    def expectNoSourceCreation(): ControllerTestHelper =
      sourceCreationLatch.await(UnexpectedTimeout.toMillis, TimeUnit.MILLISECONDS) shouldBe false
      this

    /**
      * Overrides the result for the available media to be returned by the 
      * metadata service. This can be used for instance to set a failure 
      * result.
      *
      * @param mediaResult the result to be set for the service
      * @return this test helper
      */
    def initAvailableMedia(mediaResult: Future[AvailableMedia]): ControllerTestHelper =
      prepareMediaRequest(metaDataService, mediaResult)
      checkMediaInConverter set false // cannot check for standard media
      this

    /**
      * Sets a flag that the validation source should be delayed. This is
      * useful for testing whether the validation stream can be canceled.
      *
      * @return this test helper
      */
    def delaySource(): ControllerTestHelper =
      delaySourceFlag set true
      this

    /**
      * Initializes a mock kill switch. This one is then passed to the
      * controller instead of the real one. This is used for testing
      * cancellation handling.
      *
      * @return this test helper
      */
    def withMockKillSwitch(): ControllerTestHelper =
      mockKillSwitch set Some(mock[KillSwitch])
      this

    /**
      * Checks that the kill switch mock was never invoked.
      *
      * @return this test helper
      */
    def validateNoStreamCancellation(): ControllerTestHelper =
      verify(mockKillSwitch.get().get, never()).shutdown()
      this

    /**
      * Creates the mock for the client application.
      *
      * @return the mock application
      */
    private def createApplication(): ClientApplication =
      val app = mock[ClientApplication]
      val appCtx = mock[ClientApplicationContext]
      Mockito.when(appCtx.actorSystem).thenReturn(system)
      Mockito.when(appCtx.messageBus).thenReturn(messageBus)
      Mockito.when(appCtx.managementConfiguration).thenReturn(configuration)
      Mockito.when(app.clientApplicationContext).thenReturn(appCtx)
      app

    /**
      * Creates a mock for the table handler.
      *
      * @return the mock table handler
      */
    private def createTableHandler(): TableHandler =
      val handler = mock[TableHandler]
      Mockito.when(handler.getModel).thenReturn(tableModel)
      handler

    /**
      * Creates the mock for the object to sync with the UI thread. The mock
      * does not actually execute passed in tasks; it rather adds them to a
      * queue.
      *
      * @return the mock for the sync object
      */
    private def createSynchronizer(): GUISynchronizer =
      val sync = mock[GUISynchronizer]
      Mockito.when(sync.asyncInvoke(any(classOf[Runnable]))).thenAnswer((invocation: InvocationOnMock) => {
        val task = invocation.getArguments.head.asInstanceOf[Runnable]
        syncTaskQueue offer task
      })
      sync

    /**
      * Generates a flow that simulates file validation. For each file it is
      * checked whether it is in the list of invalid files. If so, a
      * corresponding validation failure is generated.
      *
      * @return the validation flow
      */
    private def createValidationFlow(): ValidationFlow[MediaFile] =
      Flow[List[MediaFile]].mapConcat { files =>
        files map { f =>
          val result = InvalidFiles get f match
            case Some(code) =>
              code.failureNel[MediaFile]
            case None =>
              f.successNel[ValidationErrorCode]
          ValidatedItem(f.mediumID, f.uri, identity, result)
        }
      }

    /**
      * Creates a mock item converter that returns the predefined error items
      * for the referenced files.
      *
      * @return the mock item converter
      */
    private def createConverter(): ValidationItemConverter =
      val converter = mock[ValidationItemConverter]
      prepareConverter(converter)
      converter

    /**
      * Prepares the mock for the item converter to handle conversion requests.
      *
      * @param converter the mock for the converter
      */
    private def prepareConverter(converter: ValidationItemConverter): Unit =
      Mockito.when(converter.generateTableItems(any(classOf[AvailableMedia]), any(classOf[ValidatedItem[_]])))
        .thenAnswer((invocation: InvocationOnMock) => {
          if checkMediaInConverter.get() then {
            invocation.getArguments.head should be(TestMedia)
          }
          val valItem = invocation.getArguments()(1).asInstanceOf[ValidatedItem[_]]
          List(ErrorItems(valItem.uri))
        })

    /**
      * Creates a mock for the metadata service. The service returns the
      * prepared test files and their metadata.
      *
      * @return the mock metadata service
      */
    private def createMetadataService(): MetadataService[AvailableMedia, Map[MediaFileID, MediaMetadata]] =
      val service = mock[MetadataService[AvailableMedia, Map[MediaFileID, MediaMetadata]]]
      prepareMediaRequest(service, Future.successful(TestMedia))

      Mockito.when(service.fetchMetadataOfMedium(any(classOf[MediumID])))
        .thenAnswer((invocation: InvocationOnMock) => {
          val mid = invocation.getArguments.head.asInstanceOf[MediumID]
          Kleisli[Future, MessageBus, Map[MediaFileID, MediaMetadata]] { bus =>
            bus should be(messageBus)
            val files = TestFilesPerMedium(mid)
            val data = files.map(f => (MediaFileID(mid, f.uri), f.metadata)).toMap
            Future.successful(data)
          }
        })
      service

    /**
      * Prepares the mock metadata service to expect a query for the currently
      * available media. The service is going to return the given future.
      *
      * @param service the mock metadata service
      * @param result  the available media future result to be returned
      */
    private def prepareMediaRequest(service: MetadataService[AvailableMedia, Map[MediaFileID, MediaMetadata]],
                                    result: Future[AvailableMedia]): Unit =
      val resMedia = Kleisli[Future, MessageBus, AvailableMedia] { bus =>
        bus should be(messageBus)
        result
      }
      Mockito.when(service.fetchMedia()).thenReturn(resMedia)

    /**
      * Creates a mock for the status line handler. The mock updates the
      * numbers of warnings and errors when it is invoked.
      *
      * @return the mock status line handler
      */
    private def createStatusHandler(): StatusLineHandler =
      val handler = mock[StatusLineHandler]
      doAnswer((invocation: InvocationOnMock) => {
        statusResult shouldBe empty
        validationErrorCount = invocation.getArguments.head.asInstanceOf[Int]
        validationWarningCount = invocation.getArguments()(1).asInstanceOf[Int]
      }).when(handler).updateProgress(anyInt(), anyInt())

      doAnswer((invocation: InvocationOnMock) => {
        statusResult shouldBe empty
        validationErrorCount = invocation.getArguments.head.asInstanceOf[Int]
        validationWarningCount = invocation.getArguments()(1).asInstanceOf[Int]
        statusResult = Some(invocation.getArguments()(2).asInstanceOf[Boolean])
      }).when(handler).validationResults(anyInt(), anyInt(), anyBoolean())
      handler

    /**
      * Creates the test instance.
      *
      * @return the test instance
      */
    private def createController(): ValidationController[MediaFile] =
      new ValidationController[MediaFile](app = createApplication(), metaDataService = metaDataService,
        tableHandler = tableHandler, sync = createSynchronizer(), validationFlow = createValidationFlow(),
        converter = converter, statusHandler = statusHandler):
        /**
          * @inheritdoc This implementation allows injecting a special test
          *             source. It also records the creation of the source.
          */
        override def createSource(media: AvailableMedia): Source[MediumID, NotUsed] =
          val orgSource = super.createSource(media)
          sourceCreationLatch.countDown()
          if delaySourceFlag.get() then orgSource.delay(50.millis)
          else orgSource

        /**
          * @inheritdoc This implementation allows injecting a mock kill
          *             switch. If defined, the mock kill switch is returned.
          */
        override def materializeValidationStream(media: AvailableMedia): KillSwitch =
          val orgKs = super.materializeValidationStream(media)
          mockKillSwitch.get() getOrElse orgKs

