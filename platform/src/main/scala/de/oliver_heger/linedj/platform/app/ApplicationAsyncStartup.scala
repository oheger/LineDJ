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

package de.oliver_heger.linedj.platform.app

import net.sf.jguiraffe.gui.app.Application

/**
  * A trait that implements an asynchronous startup of an application.
  *
  * This trait simply fires up a thread which invokes the regular startup
  * procedure.
  */
trait ApplicationAsyncStartup extends ApplicationStartup {
  /**
    * @inheritdoc This implementation creates a new thread for starting up the
    *             application.
    */
  override def startApplication(app: Application, configName: String): Unit = {
    new Thread() {
      override def run(): Unit = {
        doStartApplication(app, configName)
      }
    }.start()
  }
}
