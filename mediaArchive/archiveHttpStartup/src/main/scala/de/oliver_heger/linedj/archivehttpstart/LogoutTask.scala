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

package de.oliver_heger.linedj.archivehttpstart

/**
  * A task implementation for the logout action.
  *
  * This implementation just delegates to the
  * [[HttpArchiveOverviewController]]. The controller is then responsible to
  * logout the currently selected realm.
  *
  * @param controller the controller
  */
class LogoutTask(controller: HttpArchiveOverviewController) extends Runnable {
  override def run(): Unit = {
    controller.logoutCurrentRealm()
  }
}
