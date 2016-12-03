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

import akka.actor.{Actor, ActorLogging}
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ClientManagementApplication}
import de.oliver_heger.linedj.platform.app.shutdown.ShutdownManagementActor.{AddApplication, Process, ProcessData, RemoveApplication}
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.{Application, ApplicationShutdownListener}

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

  /**
    * A message processed by ''ShutdownManagementActor'' telling it to process
    * the applications currently available. The passed in shutdown listener is
    * the one that has been registered by the shutdown management component to
    * detect a shutdown in progress. It has to be removed first before an
    * application can actually be shutdown (it would otherwise veto the
    * shutdown).
    *
    * @param shutdownListener the special listener to be removed
    */
  case class Process(shutdownListener: ApplicationShutdownListener)

  /**
    * A message sent on the message bus to trigger an iteration over the
    * services currently available. This message is processed by a temporary
    * listener on the message bus, so that services are guaranteed to be
    * invoked on the event dispatch thread.
    *
    * @param process        the original ''Process'' message
    * @param services       a collection with the services to be processed
    * @param registrationID the registration ID for the message bus listener
    */
  private case class ProcessData(process: Process, services: Iterable[Application],
                                 registrationID: Int)

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
                              appContext: ClientApplicationContext) extends Actor
  with ActorLogging {
  /** A list with the currently available applications. */
  private var applications = List.empty[Application]

  override def receive: Receive = {
    case AddApplication(app) =>
      applications = app :: applications

    case RemoveApplication(app) =>
      applications = applications filterNot (_ == app)

    case p: Process =>
      val regId = messageBus.registerListener {
        case ProcessData(process, services, registrationID) =>
          messageBus removeListener registrationID
          processServices(services, process.shutdownListener)
          messageBus publish ClientManagementApplication.Shutdown(appContext)
      }
      messageBus publish ProcessData(p, applications, regId)
  }

  /**
    * Processes the given collection of services.
    *
    * @param services the services to be processed
    * @param listener the shutdown listener to be removed
    */
  private def processServices(services: Iterable[Application],
                              listener: ApplicationShutdownListener): Unit = {
    services foreach { s =>
      try {
        s removeShutdownListener listener
        s.shutdown()
      } catch {
        case ex: Exception =>
          log.error(ex, "Exception when processing " + s)
      }
    }
  }
}
