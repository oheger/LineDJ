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

package de.oliver_heger.linedj.server.discovery

import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.io.{IO, Udp}
import org.apache.pekko.pattern.{BackoffOpts, BackoffSupervisor}
import org.apache.pekko.util.ByteString

import java.net.{InetAddress, InetSocketAddress}
import scala.compiletime.uninitialized
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * A module providing discovery functionality for HTTP servers that are using
  * the ''ServerLocator'' mechanism from the ''serverCommon'' project.
  *
  * This object implements a mechanism that periodically sends multicast UDP
  * requests and waits for responses from a server locator. When such a
  * response with the main URL of the server is received, a [[Future]]
  * completes, so that clients can make use of the information.
  */
object ServerDiscovery:
  /**
    * The default timeout when waiting for the response of a discovery request.
    */
  final val DefaultTimeout = 10.seconds

  /** The default value for the minimum backoff. */
  final val DefaultMinBackoff = 5.seconds

  /** The default value for the maximum backoff. */
  final val DefaultMaxBackoff = 2.minutes

  /**
    * The default name for a discovery operation. This is used if the user does
    * not provide a name when starting an operation.
    */
  private val DefaultDiscoveryName = "serverDiscovery"

  /**
    * A data class to hold the parameters for a server discovery operation.
    *
    * An instance defines the multicast address and the port where the server
    * locator is listening for requests. Also, the expected request code needs
    * to be specified.
    *
    * Further parameters define how to deal with failed discovery attempts: If
    * no response is received after a given timeout, the discovery operation is
    * retried after a delay. The delay is increased using an exponential
    * backoff until a maximum delay is reached.
    *
    * @param multicastAddress the multicast address to send the request to
    * @param port             the port the server is listening on
    * @param requestCode      the request code to send to the server
    * @param timeout          the timeout when waiting for the response
    * @param minBackoff       the minimum delay for resending the request
    * @param maxBackoff       the maximum delay for resending the request
    */
  final case class DiscoveryParams(multicastAddress: String,
                                   port: Int,
                                   requestCode: String,
                                   timeout: FiniteDuration = DefaultTimeout,
                                   minBackoff: FiniteDuration = DefaultMinBackoff,
                                   maxBackoff: FiniteDuration = DefaultMaxBackoff)

  /**
    * A trait to represent a handle to perform a server discovery operation.
    * The operation is done in background by sending multicast UDP requests as
    * configured until a response is received. The trait exposes a [[Future]]
    * that completes with the response.
    *
    * When the handle is no longer needed, it should be closed to free
    * consumed resources. This can be done while discovery is still in progress
    * or after the result is available.
    */
  trait DiscoveryHandle extends AutoCloseable:
    /**
      * Returns a [[Future]] with the response that was received from the
      * server. Until this future completes, the discovery operation is still
      * in process.
      *
      * @return a [[Future]] with the response from the server
      */
    def futResult: Future[String]

  /**
    * A factory trait to start a discovery operation and obtain the associated
    * [[DiscoveryHandle]].
    */
  trait Factory:
    /**
      * Returns a [[DiscoveryHandle]] for a new discovery operation using the
      * given parameters. The new operation is started in background. The handle
      * can be used to obtain the result once it becomes available, or to stop
      * the operation early by closing the handle. Callers can provide a name for
      * the operation. This is used to generate names for internal actors. If
      * multiple operations are run concurrently, they should have distinct
      * names.
      *
      * @param params        the parameters for the discovery operation
      * @param discoveryName a name for the operation
      * @param actorFactory  the object to create actors
      * @return a [[DiscoveryHandle]] to control the operation
      */
    def apply(params: DiscoveryParams, discoveryName: String = DefaultDiscoveryName)
             (using actorFactory: ActorFactory): DiscoveryHandle

  /**
    * A default [[Factory]] instance to trigger discovery operations.
    */
  final val discover: Factory = new Factory:
    override def apply(params: DiscoveryParams, discoveryName: String)
                      (using actorFactory: ActorFactory): DiscoveryHandle =
      runDiscovery(params, discoveryName)

  /**
    * Triggers a discovery operation and returns the handle for it.
    *
    * @param params        the parameters for the discovery operation
    * @param discoveryName a name for the operation
    * @param actorFactory  the object to create actors
    * @return a [[DiscoveryHandle]] to control the operation
    */
  private def runDiscovery(params: DiscoveryParams, discoveryName: String = DefaultDiscoveryName)
                          (using actorFactory: ActorFactory): DiscoveryHandle =
    val promiseResult = Promise[String]()
    val discoveryActor = createDiscoveryActor(params, discoveryName, actorFactory, promiseResult)

    given ExecutionContext = actorFactory.actorSystem.dispatcher

    promiseResult.future.foreach: _ =>
      discoveryActor ! classic.PoisonPill

    new DiscoveryHandle:
      override def futResult: Future[String] = promiseResult.future

      override def close(): Unit =
        promiseResult.trySuccess("")

  /**
    * Creates the actor to handle the discovery operation. The actual work is 
    * done by [[UdpRequestActor]]. This function wraps this actor inside a 
    * supervisor that applies the backoff logic when discovery requests are not
    * answered within the configured timeout.
    *
    * @param params        the parameters for the discovery operation
    * @param discoveryName the name of the operation to derive actor names
    * @param actorFactory  the factory to create actors
    * @param promiseResult the [[Promise]] to pass the discovery result
    * @return the actor that performs the discovery
    */
  private def createDiscoveryActor(params: DiscoveryParams,
                                   discoveryName: String,
                                   actorFactory: ActorFactory,
                                   promiseResult: Promise[String]): ActorRef =
    val props = classic.Props(new UdpRequestActor(IO(Udp)(using actorFactory.actorSystem), params, promiseResult))

    val supervisorProps = BackoffOpts.onFailure(
      childProps = props,
      childName = s"$discoveryName-requestActor",
      minBackoff = params.minBackoff,
      maxBackoff = params.maxBackoff,
      randomFactor = 0
    )
    actorFactory.createClassicActor(BackoffSupervisor.props(supervisorProps), discoveryName)

  /**
    * A message processed by [[UdpRequestActor]] to indicate a timeout of the
    * discovery operation.
    */
  private case object DiscoveryTimeout

  /**
    * An internal helper actor which handles the UDP communication to locate
    * the server. The actor sets up a UDP socket and sends a request. Then it
    * waits for the response of the server. If no response is received within
    * the configured timeout, the actor terminates throws an exception, which
    * causes a restart. The supervisor strategy makes sure that the timing of
    * the restart conforms to the discovery parameters.
    *
    * @param udp             the actor representing the UDP system
    * @param discoveryParams the parameters for discovery
    * @param promiseResult   a [[Promise]] to complete with the result
    */
  private[discovery] class UdpRequestActor(udp: ActorRef,
                                           discoveryParams: DiscoveryParams,
                                           promiseResult: Promise[String]) extends classic.Actor, classic.ActorLogging:
    /** The actor representing the UDP socket. */
    private var socketActor: ActorRef = uninitialized

    override def preStart(): Unit =
      initDiscovery()

    override def postStop(): Unit =
      closeSocket()

    override def receive: Receive =
      case Udp.Bound(localAddress) =>
        log.info("Sending UDP discovery request from port {}.", localAddress.getPort)
        val discoveryRequest = Udp.Send(
          data = ByteString(discoveryParams.requestCode),
          target = InetSocketAddress(InetAddress.getByName(discoveryParams.multicastAddress), discoveryParams.port)
        )
        sender() ! discoveryRequest
        socketActor = sender()
        context.become(active())

      case DiscoveryTimeout =>
        handleTimeout()

    /**
      * A special [[Receive]] function that is enabled when the socket is open
      * and a response from the server is expected.
      *
      * @return the handler function
      */
    private def active(): Receive =
      case Udp.Received(data, remote) =>
        val response = data.utf8String
        log.info("Received response '{}' from {}.", response, remote)
        promiseResult.success(response)
        context.stop(self)

      case DiscoveryTimeout =>
        handleTimeout()

    /**
      * Handles a timeout by throwing an exception which causes a restart of
      * the actor.
      */
    private def handleTimeout(): Unit =
      log.info("DiscoveryTimeout received.")
      throw new IllegalStateException("Discovery timeout.")

    /**
      * Prepares a discovery operation by requesting a socket and scheduling a
      * message to indicate a timeout.
      */
    private def initDiscovery(): Unit =
      import context.dispatcher
      udp ! Udp.Bind(self, new InetSocketAddress(0))
      context.system.scheduler.scheduleOnce(discoveryParams.timeout, self, DiscoveryTimeout)

    /**
      * Checks whether there is a socket actor and - if so - sends it a message
      * to unbind itself.
      */
    private def closeSocket(): Unit =
      if socketActor != null then
        socketActor ! Udp.Unbind
        socketActor = null
  end UdpRequestActor
