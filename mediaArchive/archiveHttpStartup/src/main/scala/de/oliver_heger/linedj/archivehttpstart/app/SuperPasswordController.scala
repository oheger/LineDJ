/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.resources.Message

import java.nio.file.{Path, Paths}
import scala.util.{Failure, Success}

object SuperPasswordController {
  /** Resource ID for the title of dialogs related to the super password. */
  final val ResSuperPasswordTitle = "super_password_title"

  /**
    * Resource ID for a message that the super password file has been written
    * successfully.
    */
  final val ResSuperPasswordFileWritten = "msg_super_password_written"

  /**
    * Resource ID for a message reporting an IO exception when accessing the
    * super password file.
    */
  final val ResErrIO = "err_super_password_io"

  /**
    * Resource ID for a message reporting an exception caused by a non-readable
    * super password file. This typically indicates that the file is corrupt,
    * or the password is incorrect.
    */
  final val ResErrFormat = "err_super_password_format"

  /**
    * The default name of the super password file. This is used if no path is
    * provided in the configuration.
    */
  final val DefaultSuperPasswordFileName = "archive.credentials"

  /**
    * Obtains the path where to store the super password file from the main
    * application. If no path is explicitly configured, a default path in the
    * user's home directory is used.
    *
    * @param application the main application
    * @return the path to the super password file
    */
  private def superPasswordPath(application: HttpArchiveStartupApplication): Path =
    Option(application.clientApplicationContext.managementConfiguration
      .getString(HttpArchiveStartupApplication.PropSuperPasswordFilePath))
      .map(sPath => Paths get sPath)
      .getOrElse(Paths.get(System.getProperty("user.home"), DefaultSuperPasswordFileName))
}

/**
  * A controller class responsible for actions related to the ''super
  * password''.
  *
  * This class implements the logic for saving a file with all the credentials
  * entered so far and for reading this file to open up the archives
  * referenced. While most of the steps necessary are implemented by the
  * application and the [[SuperPasswordStorageService]], this class takes care
  * of the coordination of these steps and notifies the user about the outcome.
  *
  * @param application          the application instance
  * @param superPasswordService the service for handling the password file
  */
class SuperPasswordController(val application: HttpArchiveStartupApplication,
                              val superPasswordService: SuperPasswordStorageService) extends MessageBusListener {

  import SuperPasswordController._
  import application.toUIFuture

  /**
    * Returns the function for handling messages published on the message bus.
    *
    * @return the message handling function
    */
  override def receive: Receive = {
    case SuperPasswordEnteredForWrite(password) =>
      writeSuperPasswordFile(password)
    case SuperPasswordEnteredForRead(password) =>
      readSuperPasswordFile(password)
  }

  /**
    * Handles a request to write the super password file.
    *
    * @param superPassword the super password
    */
  private def writeSuperPasswordFile(superPassword: String): Unit = {
    val path = superPasswordPath(application)
    application.saveArchiveCredentials(superPasswordService, path, superPassword)
      .onCompleteUIThread {
        case Success(path) =>
          val message = new Message(null, ResSuperPasswordFileWritten, path.toString)
          messageBox(message, MessageOutput.MESSAGE_INFO)
        case Failure(exception) =>
          showErrorMessage(ResErrIO, exception)
      }
  }

  /**
    * Handles a request to read the super password file and open all the
    * archives whose credentials are listed.
    *
    * @param superPassword the super password
    */
  private def readSuperPasswordFile(superPassword: String): Unit = {
    val path = superPasswordPath(application)
    superPasswordService.readSuperPasswordFile(path,
      superPassword)(application.clientApplicationContext.actorSystem) onCompleteUIThread {
      case Success(stateMessages) =>
        stateMessages foreach application.clientApplicationContext.messageBus.publish
      case Failure(exception: IllegalStateException) =>
        showErrorMessage(ResErrFormat, exception)
      case Failure(exception) =>
        showErrorMessage(ResErrIO, exception)
    }
  }

  /**
    * Displays an error message with a specific resource ID for the given
    * exception.
    *
    * @param resID     the resource ID
    * @param exception the exception
    */
  private def showErrorMessage(resID: String, exception: Throwable): Unit = {
    val message = new Message(null, resID, exception)
    messageBox(message, MessageOutput.MESSAGE_ERROR)
  }

  /**
    * Convenience method to display a message box with some default settings.
    *
    * @param message     the message to be displayed
    * @param messageType the message type
    */
  private def messageBox(message: Message, messageType: Int): Unit = {
    application.getApplicationContext.messageBox(message, ResSuperPasswordTitle, messageType, MessageOutput.BTN_OK)
  }
}
