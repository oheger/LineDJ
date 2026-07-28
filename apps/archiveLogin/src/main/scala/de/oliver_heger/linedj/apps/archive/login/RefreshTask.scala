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

package de.oliver_heger.linedj.apps.archive.login

import de.oliver_heger.linedj.platform.archiveclient.LoginService

/**
  * A task to refresh the current archive login state.
  *
  * This task is injected the [[LoginService]] which monitors the archive
  * server for changes in the archive state. It notifies this service to reduce
  * the query interval.
  *
  * @param loginService the [[LoginService]]
  */
class RefreshTask(loginService: LoginService) extends Runnable:
  override def run(): Unit =
    loginService.expectChanges()
