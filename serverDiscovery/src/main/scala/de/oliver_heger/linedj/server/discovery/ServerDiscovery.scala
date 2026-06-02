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

import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.io.{IO, Udp}
import org.apache.pekko.pattern.{BackoffOpts, BackoffSupervisor, ask}
import org.apache.pekko.util.{ByteString, Timeout}

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
  * completes, so that clients can make use of the information. Internally, the
  * object makes use of a [[BackoffActor]], so that retries with a configurable
  * backoff can be achieved.
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
    * An exception class that is used to indicate a canceled discovery
    * operation. If the [[DiscoveryHandle]] is closed before a server was
    * discovered, the [[Future]] of the handle fails with this exception.
    */
  final class DiscoveryCanceledException extends RuntimeException

  /**
    * A trait to represent a handle to perform a server discovery operation.
    * The operation is done in background by sending multicast UDP requests as
    * configured until a response is received. The trait exposes a [[Future]]
    * that completes with the response.
    *
    * When the handle is no longer needed, it should be closed to free
    * consumed resources. This can be done while discovery is still in progress
    * or after the result is available. If discovery is still in progress, the
    * [[Future]] wrapped by this handle completes in failure state with a
    * [[DiscoveryCanceledException]] exception.
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
    val taskFunc = createDiscoveryTaskFunc(params, discoveryName, actorFactory, promiseResult)
    val backoffParams = BackoffActor.BackoffParameters(
      minBackoff = params.minBackoff,
      maxBackoff = params.maxBackoff,
      taskFunc = taskFunc,
      failureResult = BackoffActor.TaskResult.Backoff
    )
    val backoffHandle = BackoffActor.newInstance(backoffParams, s"$discoveryName-backoff")

    new DiscoveryHandle:
      override def futResult: Future[String] = promiseResult.future

      override def close(): Unit =
        promiseResult.tryFailure(new DiscoveryCanceledException)
        backoffHandle.close()

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
  private def createDiscoveryBackoffActor(params: DiscoveryParams,
                                          discoveryName: String,
                                          actorFactory: ActorFactory,
                                          promiseResult: Promise[String]): ActorRef =
    val props = classic.Props(new UdpRequestActor(IO(Udp)(using actorFactory.actorSystem), params))

    val supervisorProps = BackoffOpts.onFailure(
      childProps = props,
      childName = s"$discoveryName-requestActor",
      minBackoff = params.minBackoff,
      maxBackoff = params.maxBackoff,
      randomFactor = 0
    )
    actorFactory.createClassicActor(BackoffSupervisor.props(supervisorProps), discoveryName)

  /**
    * Creates the task function that is invoked by the [[BackoffActor]]. This
    * function creates an actor that sends a UDP request and waits for the
    * response from the server. If no response is received within the timeout,
    * the function fails.
    *
    * @param params        the parameters for the discovery operation
    * @param discoveryName the name of the discovery operation
    * @param actorFactory  the factory to create actors
    * @param promiseResult the [[Promise]] to complete with the result
    * @return the task function for the [[BackoffActor]]
    */
  private def createDiscoveryTaskFunc(params: DiscoveryParams,
                                      discoveryName: String,
                                      actorFactory: ActorFactory,
                                      promiseResult: Promise[String]): BackoffActor.TaskFunc =
    () =>
      val udpActorName = s"$discoveryName-udp-${System.nanoTime()}"
      val udpActor = actorFactory.createClassicActor(
        classic.Props(UdpRequestActor(IO(Udp)(using actorFactory.actorSystem), params)),
        udpActorName
      )

      given Timeout = Timeout(params.timeout)

      given ExecutionContext = actorFactory.actorSystem.dispatcher

      udpActor.ask(UdpSendRequest).mapTo[String].map: uri =>
        promiseResult.success(uri)
        BackoffActor.TaskResult.Cancel
      .andThen:
        case _ => udpActor ! UdpStop

  /**
    * A message processed by [[UdpRequestActor]] to indicate a timeout of the
    * discovery operation.
    */
  private case object DiscoveryTimeout

  /**
    * A message processed by [[UdpRequestActor]] that triggers the sending of
    * the UDP discovery request according to the configured parameters. When 
    * the response arrives, the sender of this message gets notified.
    */
  private[discovery] case object UdpSendRequest

  /**
    * A message processed by [[UdpRequestActor]] that indicates that it should
    * stop itself. If a socket is currently open, this socket should be closed
    * first.
    */
  private[discovery] case object UdpStop

  /**
    * An internal helper actor which handles the UDP communication to locate
    * the server. The actor sets up a UDP socket and expects a command to send
    * a request. Then it waits for the response of the server and answers the
    * request when it arrives. Instances need to be stopped explicitly.
    *
    * @param udp             the actor representing the UDP system
    * @param discoveryParams the parameters for discovery
    */
  private[discovery] class UdpRequestActor(udp: ActorRef,
                                           discoveryParams: DiscoveryParams)
    extends classic.Actor, classic.ActorLogging:
    /** The actor representing the UDP socket. */
    private var socketActor: ActorRef = uninitialized

    /** The client to receive the discovery result. */
    private var client: ActorRef = uninitialized

    override def postStop(): Unit =
      closeSocket()

    override def receive: Receive =
      case UdpSendRequest =>
        client = sender()
        initDiscovery()

      case UdpStop =>
        context.stop(self)

      case Udp.Bound(localAddress) =>
        log.info("Sending UDP discovery request from port {}.", localAddress.getPort)
        val discoveryRequest = Udp.Send(
          data = ByteString(discoveryParams.requestCode),
          target = InetSocketAddress(InetAddress.getByName(discoveryParams.multicastAddress), discoveryParams.port)
        )
        sender() ! discoveryRequest
        socketActor = sender()
        context.become(active())

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
        client ! response

      case UdpStop =>
        closeSocket()

      case Udp.Unbound =>
        context.stop(self)

    /**
      * Prepares a discovery operation by requesting a socket and scheduling a
      * message to indicate a timeout.
      */
    private def initDiscovery(): Unit =
      udp ! Udp.Bind(self, new InetSocketAddress(0))

    /**
      * Checks whether there is a socket actor and - if so - sends it a message
      * to unbind itself.
      */
    private def closeSocket(): Unit =
      if socketActor != null then
        socketActor ! Udp.Unbind
        socketActor = null
  end UdpRequestActor
