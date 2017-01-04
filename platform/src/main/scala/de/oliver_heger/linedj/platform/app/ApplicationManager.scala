/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.platform.app

object ApplicationManager {

  /**
    * A notification message indicating that a [[ClientApplication]] has been
    * registered. A message of this type is published on the UI message bus
    * by the application manager when its ''registerApplication()'' method has
    * been invoked.
    *
    * @param application the application that has been registered
    */
  case class ApplicationRegistered(application: ClientApplication)

  /**
    * A notification message indicating that the window title of a
    * [[ClientApplication]] has changed. A message of this type is published
    * on the UI message bus by the application manager when its
    * ''applicationTitleUpdated()'' method has been invoked.
    *
    * @param application the affected application
    * @param newTitle    the new title of this application
    */
  case class ApplicationTitleUpdated(application: ClientApplication,
                                     newTitle: String)

  /**
    * A notification message indicating that the specified
    * [[ClientApplication]] has been uninstalled. This can be used by
    * interested components, to update a list of active applications.
    *
    * @param application the affected application
    */
  case class ApplicationRemoved(application: ClientApplication)

}

/**
  * Trait defining the ''ApplicationManager'' service interface.
  *
  * A running LineDJ platform contains one ''ApplicationManager'' service.
  * This service is responsible for keeping track on all installed
  * applications. It offers functionality to register a new application and
  * handles the shutdown of the platform.
  *
  * [[ClientApplication]] has a dependency on the application manager
  * service. It uses this to register itself as application running on the
  * platform. Note that some methods offered by this service interface are
  * related to UI components and therefore can only be invoked in the event
  * dispatch thread. This is explicitly mentioned in the methods'
  * documentation.
  *
  * Some of the functionality offered by a concrete application manager
  * implementation is not directly exposed via this interface, because it is
  * not triggered by a method call. Rather, an application manager monitors the
  * current platform and the installed services and can thus decide when it has
  * to become active. This is an application of the whiteboard pattern.
  */
trait ApplicationManager {
  /**
    * Registers the specified application at this application manager. This
    * method is called by client applications after they have been fully
    * initialized. (Note that it would be possible to monitor the application
    * services installed on the platform. However, an application service is
    * typically registered before it has been fully initialized and its main
    * window has been setup. Therefore, an explicit registration is required.)
    *
    * @param app the new client application to be registered
    */
  def registerApplication(app: ClientApplication): Unit

  /**
    * Notifies this application manager that the window title of a registered
    * application has been changed. Some services may keep a list of currently
    * running applications to be displayed to the user. With this method a
    * corresponding notification can be produced allowing such services to
    * update their internal data.
    *
    * @param app   the affected application
    * @param title the new title of this application
    */
  def applicationTitleUpdated(app: ClientApplication, title: String): Unit

  /**
    * Returns a collection with all applications that are currently registered
    * on the platform. '''Note:''' This method must be called from the event
    * dispatch thread!
    *
    * @return a collection with the currently registered applications
    */
  def getApplications: Iterable[ClientApplication]

  /**
    * Returns a collection with all applications that are currently registered
    * on the platform together with their window titles. This method could be
    * used for instance by visual components displaying a list of all existing
    * applications. '''Note:''' This method must be called from the event
    * dispatch thread!
    *
    * @return a collection with the currently registered applications and their
    *         window titles
    */
  def getApplicationsWithTitles: Iterable[(ClientApplication, String)]
}
