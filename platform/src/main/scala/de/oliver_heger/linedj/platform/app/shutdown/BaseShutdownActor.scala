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
import de.oliver_heger.linedj.platform.app.shutdown.BaseShutdownActor.ProcessData
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.ApplicationShutdownListener

object BaseShutdownActor {

  /**
    * A message processed by [[BaseShutdownActor]] (and derived classes)
    * telling it to process the service objects currently available. The passed
    * in shutdown listener is the one that has been registered by the shutdown
    * management component to detect a shutdown in progress. It has to be
    * removed first before an application can actually be shutdown (it would
    * otherwise veto the shutdown). This information is processed by derived
    * classes.
    *
    * @param shutdownListener the special listener to be removed
    */
  case class Process(shutdownListener: ApplicationShutdownListener)

  /**
    * An internal message sent on the message bus to trigger an iteration over
    * the services currently available. This message is processed by a
    * temporary listener on the message bus, so that services are guaranteed to
    * be invoked on the event dispatch thread.
    *
    * @param process              the original ''Process'' message
    * @param services             a collection with the services to be processed
    * @param registrationID       the registration ID for the message bus listener
    * @param processingSuccessful flag whether the processing operation was a
    *                             success
    * @param proc                 the processing function
    *
    */
  private case class ProcessData[S](process: Process, services: Iterable[S],
                                    registrationID: Int,
                                    proc: (Process, S) => Boolean,
                                    processingSuccessful: Boolean = true)

}

/**
  * An abstract base actor class implementing common functionality for the
  * management of services related to shutdown.
  *
  * For the shutdown handling, different actors are required which have to
  * keep track on specific types of services. Such an actor usually has to add
  * and remove managed service instances instances in reaction on notifications
  * from the OSGi container. It then needs functionality to iterate over all
  * services currently available and apply a function on them; this iteration
  * may be aborted, for instance, if a shutdown listener declares a veto. Also,
  * iteration has to happen in the event dispatch thread because the managed
  * services expect to be called on this thread only.
  *
  * This base actor class implements this kind of functionality. It defines
  * some abstract methods which have to be implemented by concrete subclasses
  * in order to customize the handling of managed services, especially during
  * the iteration over all services.
  *
  * @param messageBus the UI message bus
  * @tparam S the type of services to be managed by this actor
  */
abstract class BaseShutdownActor[S](messageBus: MessageBus) extends Actor with ActorLogging {
  /** A list with the services managed by this actor instance. */
  private var services = List.empty[S]

  /** A flag whether an iteration is currently in progress. */
  private var processOngoing = false

  final override def receive: Receive =
    customReceive orElse baseReceive

  /**
    * Adds the given service to the internal list of managed services.
    *
    * @param service the service to be added
    */
  protected def addService(service: S): Unit = {
    services = service :: services
  }

  /**
    * Removes the specified service from the internal list of services.
    *
    * @param service the service to be removed
    */
  protected def removeService(service: S): Unit = {
    services = services filterNot (_ == service)
  }

  /**
    * The custom message handling function for this actor. Concrete classes
    * must define this method rather than ''receive()''; the ''receive()''
    * implementation provided by this base class ensures that the custom and
    * basic message handling is applied.
    *
    * @return the custom message handling function
    */
  protected def customReceive: Receive

  /**
    * Returns the function to be used when processing services. The function
    * gets passed the current ''Process'' message and a service instance. It
    * then processes the service and returns a flag whether iteration can
    * continue ('''true''') or should be aborted ('''false''').
    *
    * @return the processing function
    */
  protected def processFunction: (BaseShutdownActor.Process, S) => Boolean

  /**
    * A method to be invoked after successful processing of services. Here a
    * derived class can implement concluding actions after the iteration over
    * the services. The function is invoked under the control of this actor, so
    * access to all member fields is possible.
    *
    * @param process the current ''Process'' message
    */
  protected def afterProcessing(process: BaseShutdownActor.Process): Unit

  /**
    * The message handling function of this base actor.
    *
    * @return the base message handling function
    */
  private def baseReceive: Receive = {
    case p: BaseShutdownActor.Process =>
      if (!processOngoing) {
        processOngoing = true
        val regId = messageBus registerListener processDataReceive
        messageBus publish ProcessData[S](p, services, regId, processFunction)
      }

    case pd: ProcessData[S] =>
      processOngoing = false
      if (pd.processingSuccessful) {
        afterProcessing(pd.process)
      }
  }

  /**
    * Returns the receive function for the message bus listener that processes
    * the services in the UI thread.
    *
    * @return the receive function of the services processor
    */
  private def processDataReceive: Receive = {
    case processData: ProcessData[S] =>
      messageBus removeListener processData.registrationID
      self ! processData.copy(processingSuccessful = processServices(services,
        processData))
  }

  /**
    * Processes the given collection of services.
    *
    * @param services    the services to be processed
    * @param processData the current process data object
    * @return a flag whether processing was successful
    */
  private def processServices(services: Iterable[S],
                              processData: ProcessData[S]): Boolean = {
    val optVeto = services find (!processService(_, processData))
    optVeto.isEmpty
  }

  /**
    * Helper method for invoking the processing function on the specified
    * service. Exceptions are caught; then processing continues.
    *
    * @param service     the service
    * @param processData the current ''ProcessData'' object
    * @return the result of the processing function
    */
  private def processService(service: S, processData: ProcessData[S]): Boolean =
    try processData.proc(processData.process, service)
    catch {
      case ex: Exception =>
        log.error(ex, "Error when processing service " + service)
        true
    }
}
