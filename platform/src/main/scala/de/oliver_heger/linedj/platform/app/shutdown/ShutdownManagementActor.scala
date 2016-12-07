/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.shutdown

import de.oliver_heger.linedj.platform.app.shutdown.BaseShutdownActor.Process
import de.oliver_heger.linedj.platform.app.shutdown.ShutdownManagementActor.{AddApplication, RemoveApplication}
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ClientManagementApplication}
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.Application

object ShutdownManagementActor {

  /**
    * A message processed by ''ShutdownManagementActor'' telling it that a new
    * application has been installed on the platform. It is added to the
    * internal list of available applications.
    *
    * @param app the new application
    */
  case class AddApplication(app: Application)

  /**
    * A message processed by ''ShutdownManagementActor'' telling it that an
    * application has been removed from the platform. It is also removed from
    * the internal list of applications.
    *
    * @param app the application
    */
  case class RemoveApplication(app: Application)
}

/**
  * An actor responsible for the shutdown handling of applications running on a
  * LineDJ platform.
  *
  * This actor is notified about installed and removed applications, so that it
  * can keep an updated list with the applications currently available. It
  * reacts on a ''Process'' message by iterating over all applications and
  * invoking their ''shutdown()'' method. This is done in the event dispatch
  * thread.
  *
  * When all applications have been shutdown a final ''Shutdown'' message is
  * sent on the message bus which is going to trigger the shutdown of the whole
  * platform.
  *
  * @param messageBus the UI message bus
  * @param appContext the client application context
  */
class ShutdownManagementActor(messageBus: MessageBus,
                              appContext: ClientApplicationContext)
  extends BaseShutdownActor[Application](messageBus) {
  protected override def customReceive: Receive = {
    case AddApplication(app) =>
      addService(app)

    case RemoveApplication(app) =>
      removeService(app)
  }

  /**
    * @inheritdoc This implementation returns a function which removes the
    *             special shutdown listener from the application and invokes
    *             its ''shutdown()'' method.
    */
  override protected def processFunction: (Process, Application) => Boolean = {
    (process, app) => {
      app removeShutdownListener process.shutdownListener
      app.shutdown()
      true
    }
  }

  /**
    * @inheritdoc This implementation publishes the final ''Shutdown''
    *             message on the UI message bus.
    */
  override protected def afterProcessing(process: Process): Unit = {
    messageBus publish ClientManagementApplication.Shutdown(appContext)
  }
}
