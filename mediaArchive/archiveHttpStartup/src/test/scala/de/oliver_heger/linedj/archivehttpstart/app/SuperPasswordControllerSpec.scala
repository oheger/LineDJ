/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ClientApplicationContextImpl
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.resources.Message
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.{Path, Paths}
import java.security.Key
import scala.concurrent.Future

object SuperPasswordControllerSpec:
  /** Constant for the test super password. */
  private val SuperPassword = "TheSuperPassword!"

/**
  * Test class for ''SuperPasswordController''.
  */
class SuperPasswordControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("SuperPasswordControllerSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import SuperPasswordControllerSpec._

  "SuperPasswordController" should "write the super password file successfully" in:
    val path = Paths.get("some/test/path/password.txt")
    val pathSaved = Paths.get("successful/saved/password.file")
    val helper = new ControllerTestHelper

    helper.initSuperPasswordPathProperty(path)
      .expectSaveCredentials(path, Future.successful(pathSaved))
      .postOnMessageBus(SuperPasswordEnteredForWrite(SuperPassword))
      .processUIFuture()
      .expectWriteConfirmationMessage(pathSaved)

  it should "write to the default path if not path is configured" in:
    val expPath = Paths.get(System.getProperty("user.home"), SuperPasswordController.DefaultSuperPasswordFileName)
    val pathSaved = Paths.get("successful/saved/password.file")
    val helper = new ControllerTestHelper

    helper.expectSaveCredentials(expPath, Future.successful(pathSaved))
      .postOnMessageBus(SuperPasswordEnteredForWrite(SuperPassword))
      .processUIFuture()
      .expectWriteConfirmationMessage(pathSaved)

  it should "display an error message if the write operation fails" in:
    val path = Paths.get("error.path")
    val exception = new IOException("Could not write super password file!")
    val helper = new ControllerTestHelper

    helper.initSuperPasswordPathProperty(path)
      .expectSaveCredentials(path, Future.failed(exception))
      .postOnMessageBus(SuperPasswordEnteredForWrite(SuperPassword))
      .processUIFuture()
      .expectErrorMessage(SuperPasswordController.ResErrIO, exception)

  it should "read the super password file and open the archives referenced" in:
    val path = Paths.get("stored/credentials.txt")
    val archiveKey = mock[Key]
    val states = List(LoginStateChanged("someRealm", Some(UserCredentials("user", Secret("secret")))),
      LockStateChanged("testArchive", Some(archiveKey)))
    val helper = new ControllerTestHelper

    helper.initSuperPasswordPathProperty(path)
      .expectLoadCredentials(path, Future.successful(states))
      .postOnMessageBus(SuperPasswordEnteredForRead(SuperPassword))
      .processUIFuture()
      .expectStateMessagesOnBus(states)

  it should "handle an IOException when reading the super password file" in:
    val path = Paths.get("non/existing/credentials.file")
    val exception = new IOException("File not found: " + path)
    val helper = new ControllerTestHelper

    helper.initSuperPasswordPathProperty(path)
      .expectLoadCredentials(path, Future.failed(exception))
      .postOnMessageBus(SuperPasswordEnteredForRead(SuperPassword))
      .processUIFuture()
      .expectErrorMessage(SuperPasswordController.ResErrIO, exception)

  it should "handle an IllegalStateException when reading the super password file" in:
    val path = Paths.get("invalid/credentials.file")
    val exception = new IllegalStateException("Invalid file: " + path)
    val helper = new ControllerTestHelper

    helper.initSuperPasswordPathProperty(path)
      .expectLoadCredentials(path, Future.failed(exception))
      .postOnMessageBus(SuperPasswordEnteredForRead(SuperPassword))
      .processUIFuture()
      .expectErrorMessage(SuperPasswordController.ResErrFormat, exception)

  /**
    * A test helper class manages an instance to be tested and its
    * dependencies.
    */
  private class ControllerTestHelper:
    /** The system message bus. */
    private val messageBus = new MessageBusTestImpl()

    /** Mock for the application context. */
    private val applicationContext = mock[ApplicationContext]

    /** Mock for the main application. */
    private val application = createApplication()

    /** Mock for the storage service. */
    private val storageService = mock[SuperPasswordStorageService]

    /** The controller to be tested. */
    private val controller = createController()

    /**
      * Prepares the application mock to expect an invocation to save the
      * archive credentials.
      *
      * @param expPath the expected path
      * @param result  the result to return
      * @return this test helper
      */
    def expectSaveCredentials(expPath: Path, result: Future[Path]): ControllerTestHelper =
      doReturn(result).when(application).saveArchiveCredentials(storageService, expPath, SuperPassword)
      this

    /**
      * Prepares the mock for the super password service to expect an
      * invocation to read the credentials file from the given path. The result
      * of the operation can be specified.
      *
      * @param expPath the expected path
      * @param result  the result to return
      * @return this test helper
      */
    def expectLoadCredentials(expPath: Path, result: Future[Iterable[ArchiveStateChangedMessage]]):
    ControllerTestHelper =
      when(storageService.readSuperPasswordFile(expPath, SuperPassword)(system))
        .thenReturn(result)
      this

    /**
      * Simulates a message to the test controller propagated via the message
      * bus.
      *
      * @param message the message
      * @return this test helper
      */
    def postOnMessageBus(message: Any): ControllerTestHelper =
      controller.receive(message)
      this

    /**
      * Processes the next message on the message bus to make completion of a
      * future in the UI thread possible.
      *
      * @return this test helper
      */
    def processUIFuture(): ControllerTestHelper =
      messageBus.processNextMessage[Any]()
      this

    /**
      * Adds a property to the configuration of the main application.
      *
      * @param key   the property key
      * @param value the property value
      * @return this test helper
      */
    def addConfigProperty(key: String, value: Any): ControllerTestHelper =
      application.clientApplicationContext.managementConfiguration.addProperty(key, value)
      this

    /**
      * Sets the property for the path of the super password file in the
      * configuration of the main application.
      *
      * @param path the path to be set
      * @return this test helper
      */
    def initSuperPasswordPathProperty(path: Path): ControllerTestHelper =
      addConfigProperty(HttpArchiveStartupApplication.PropSuperPasswordFilePath, path.toString)

    /**
      * Verifies that a message box with the specified properties has been
      * displayed.
      *
      * @param msg     the message object
      * @param msgType the message type
      * @param buttons the message buttons code
      * @return this test helper
      */
    def expectMessage(msg: AnyRef, msgType: Int, buttons: Int): ControllerTestHelper =
      verify(applicationContext).messageBox(msg, SuperPasswordController.ResSuperPasswordTitle, msgType, buttons)
      this

    /**
      * Verifies that a message box with a confirmation message for a
      * successful write of the super password file is displayed.
      *
      * @param path the path of the file
      * @return this test helper
      */
    def expectWriteConfirmationMessage(path: Path): ControllerTestHelper =
      val expMessage = new Message(null, SuperPasswordController.ResSuperPasswordFileWritten, path.toString)
      expectMessage(expMessage, MessageOutput.MESSAGE_INFO, MessageOutput.BTN_OK)

    /**
      * Verifies that a message box with an error message is displayed.
      *
      * @param resID     the resource ID of the message
      * @param exception the exception causing the message
      * @return this test helper
      */
    def expectErrorMessage(resID: String, exception: Throwable): ControllerTestHelper =
      val expMessage = new Message(null, resID, exception)
      expectMessage(expMessage, MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK)

    /**
      * Verifies that the given state change messages have been published on
      * the message bus.
      *
      * @param states the expected state messages
      * @return this test helper
      */
    def expectStateMessagesOnBus(states: Iterable[ArchiveStateChangedMessage]): ControllerTestHelper =
      states foreach { state =>
        messageBus.expectMessageType[ArchiveStateChangedMessage] should be(state)
      }
      this

    /**
      * Creates the mock for the application and initializes it.
      *
      * @return the initialized application mock
      */
    private def createApplication(): HttpArchiveStartupApplication =
      val app = spy(new HttpArchiveStartupApplication)
      val clientContext = new ClientApplicationContextImpl(messageBus = messageBus, actorSystem = system)
      app.initClientContext(clientContext)
      when(app.getApplicationContext).thenReturn(applicationContext)
      app.activate(mock[ComponentContext])
      app

    /**
      * Creates the test controller instance.
      *
      * @return the test controller
      */
    private def createController(): SuperPasswordController =
      new SuperPasswordController(application, storageService)

