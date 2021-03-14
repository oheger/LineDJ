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

package de.oliver_heger.linedj.archivehttpstart

/**
  * A task implementation for the logout all action.
  *
  * With this task a logout operation for all realms for which login
  * credentials are currently available can be performed. This implementation
  * just delegates to the [[HttpArchiveOverviewController]] which already
  * implements the required functionality.
  *
  * @param controller the controller
  */
class LogoutAllTask(controller: HttpArchiveOverviewController) extends Runnable {
  override def run(): Unit = {
    controller.logoutAllRealms()
  }
}
