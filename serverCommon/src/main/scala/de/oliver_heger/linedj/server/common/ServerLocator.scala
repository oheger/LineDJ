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

package de.oliver_heger.linedj.server.common

import de.oliver_heger.linedj.server.common.ServerLocator.NetworkManager.localAddress
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.pekko.io.Inet.SocketOptionV2
import org.apache.pekko.io.{IO, Udp}
import org.apache.pekko.util.ByteString

import java.net.*
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
  * A module providing functionality for discovering HTTP servers in a network
  * via UDP multicast requests.
  *
  * The purpose of this module is to allow devices in the local network to
  * determine the URL of a specific service. To achieve this, devices send a
  * UDP multicast request to a specific group with a specific content. This
  * module installs a listener for incoming requests on this group for all
  * available network interfaces and answers them. Both, the request content
  * and the answer can be configured when setting up an instance.
  *
  * The most simple request handled by this listener just consists of the
  * configured request code. In this case, the answer is sent to the address of
  * the caller. It is also possible to specify an alternative target port by
  * appending the desired port to the request code separated by a colon (':').
  *
  * The answer is actually a template that can contain the placeholder
  * ''$address''. This placeholder is replaced by a local IP address of one of
  * the interfaces the listener was bound to. This module assumes that the
  * associated service uses the same local address; so the URL to its endpoint
  * can be generated this way.
  *
  * For this mechanism to work, at least one network interface must be
  * available. During the initialization of a listener, the module checks
  * whether this is the case. If not, it retries the bind operation at a later
  * point in time until it succeeds.
  */
object ServerLocator:
  /**
    * Constant for a placeholder in the template for the response to be sent
    * for valid requests that is to be replaced by the current IP address.
    */
  final val PlaceHolderAddress = "$address"

  /** The maximum delay for retrying a bind operation. */
  final val MaxBindRetryDelay = 5.minutes

  /**
    * Type alias for a function that looks up the currently available network
    * interfaces. Abstracting this functionality allows replacing it, for
    * instance in tests.
    */
  type NetworkInterfaceLookupFunc = () => List[NetworkInterface]

  /**
    * A data class to represent a request to be handled by the locator. It
    * contains information about the client and the address where the server
    * can be accessed. Based on this information, it can be decided whether the
    * request is valid and should be answered and where to send the response
    * to.
    *
    * @param requestCode   the request code sent by the client
    * @param locatorCode   the code configured for the locator
    * @param remote        the address of the client
    * @param serverAddress the address to access the server
    */
  final case class LocatorRequest(requestCode: String,
                                  locatorCode: String,
                                  remote: InetSocketAddress,
                                  serverAddress: String)

  /**
    * A data class that contains the information required to send a response
    * from the locator. This is the actual payload of the response and the
    * address of the client which receives the response.
    *
    * @param payload the payload of the response
    * @param target  the target address to send the response to
    */
  final case class LocatorResponse(payload: String,
                                   target: InetSocketAddress)

  /**
    * Type alias of a function that can handle requests for a locator. The
    * function is passed an object with all information relevant for processing
    * the request. It then decides whether the request is valid and a response
    * has to be sent. In this case, it returns a defined [[Option]] with a 
    * [[LocatorResponse]] object. For a request that cannot be answered, result
    * should be ''None''.
    */
  type HandlerFunc = LocatorRequest => Option[LocatorResponse]

  /**
    * A data class defining the parameters required to set up a server locator
    * instance. An instance defines the multicast UDP settings and how locator
    * requests should be handled.
    *
    * @param multicastAddress the multicast address to listen for
    * @param port             the port the actor should listen on
    * @param requestCode      the expected request code
    * @param responseTemplate the template to generate the response to send
    * @param lookupFunc       the function for looking up network interfaces
    * @param handlerFunc      the function to handle requests
    */
  final case class LocatorParams(multicastAddress: String,
                                 port: Int,
                                 requestCode: String,
                                 responseTemplate: String,
                                 lookupFunc: NetworkInterfaceLookupFunc =
                                 NetworkManager.DefaultNetworkInterfaceLookupFunc,
                                 handlerFunc: HandlerFunc = defaultHandler)

  /**
    * A trait representing a handle to a locator instance created by this
    * module. Via the handle, the locator can be stopped when it is no longer
    * needed.
    */
  trait LocatorHandle:
    /**
      * Stops the locator associated with this handle. The return value
      * indicates whether this was successful. All invocations except for the
      * first one will return '''false'''.
      *
      * @return a flag whether the locator was stopped successfully
      */
    def stop(): Boolean

  /**
    * A trait that allows creating new server locator instances. The trait
    * defines a single factory method that expects the parameters for the new
    * locator instance.
    */
  trait LocatorFactory:
    /**
      * Creates a new server locator instance based on the provided parameters
      * and returns a [[LocatorHandle]] to it. The handle can be used to stop
      * the instance. Note that it is not mandatory to explicitly stop
      * locators; when the provided [[ManagingActorFactory]] is closed, all
      * resources consumed by the locator will be freed automatically.
      *
      * @param name         a name for the locator, so that multiple instances
      *                     can be distinguished
      * @param params       the parameters for the new instance
      * @param actorFactory a factory to create actors
      * @return
      */
    def apply(name: String, params: LocatorParams)(using actorFactory: ManagingActorFactory): LocatorHandle

  /** A default factory for creating new server locators. */
  final val newLocator: LocatorFactory = new LocatorFactory:
    override def apply(name: String, params: LocatorParams)(using actorFactory: ManagingActorFactory): LocatorHandle =
      val props = Props(new ServerLocatorActor(params))
      val locatorActor = actorFactory.createClassicActor(props, name, Some(Udp.Unbind))

      () => actorFactory.management.unregisterAndStopActor(name)

  /**
    * An internal object providing some functionality related to querying
    * network interfaces.
    */
  private[common] object NetworkManager:
    /** The default function for looking up network interfaces. */
    final val DefaultNetworkInterfaceLookupFunc = fetchSupportedNetworkInterfaces _

    /**
      * Checks whether the given [[NetworkInterface]] can be used by
      * [[EndpointRequestHandlerActor]] for the discovery mechanism. The function
      * checks for multiple criteria.
      *
      * @param ifc the interface to check
      * @return a flag whether this interface can be used for binding
      */
    private[common] def canBindToInterface(ifc: NetworkInterface): Boolean =
      ifc.isUp && !ifc.isLoopback && isIpv4(ifc)

    /**
      * Filters the given collection for network interfaces for those that can be
      * used by the auto-discovery mechanism of the player server.
      *
      * @param interfaces a collection of network interface
      * @return a list with supported network interfaces
      */
    private[common] def filterSupportedNetworkInterfaces(interfaces: Iterator[NetworkInterface]):
    List[NetworkInterface] =
      interfaces.filter(canBindToInterface).toList

    /**
      * Returns a list with the network interfaces that can be used by the
      * auto-discovery mechanism to listen for requests from clients.
      *
      * @return the list with supported network interfaces
      */
    private def fetchSupportedNetworkInterfaces(): List[NetworkInterface] =
      filterSupportedNetworkInterfaces(NetworkInterface.getNetworkInterfaces.asScala)

    /**
      * Checks whether the given interface can be accessed using IPv4.
      *
      * @param interface the interface in question
      * @return a flag whether this interface supports IPv4
      */
    private def isIpv4(interface: NetworkInterface): Boolean =
      interface.getInetAddresses.asScala.exists(_.isInstanceOf[Inet4Address])

    extension (ifc: NetworkInterface)
      /**
        * Returns an [[Option]] with the local address of this
        * [[NetworkInterface]]. This function filters the list of interface
        * addresses for a local one.
        *
        * @return a local interface address if it could be found
        */
      def localAddress: Option[InterfaceAddress] =
        ifc.getInterfaceAddresses.asScala.find(_.getAddress.isSiteLocalAddress)
  end NetworkManager

  /**
    * An internal helper class that configures the [[DatagramSocket]] used by
    * the locator actor for UDP multicast.
    *
    * @param multicastAddress the address of the multicast group to join
    * @param interfaces       the list of network interfaces to support
    */
  private case class MulticastConfig(multicastAddress: String,
                                     interfaces: Iterable[NetworkInterface]) extends SocketOptionV2:
    override def afterBind(s: DatagramSocket): Unit =
      val group = InetAddress.getByName(multicastAddress)
      interfaces foreach : interface =>
        s.getChannel.join(group, interface)

  /**
    * An internal message the locator actor sends to itself to trigger the
    * binding against a network interface. Before this is possible, network
    * interfaces must be available. If this is not the case, another attempt
    * is made after a delay.
    *
    * @param nextAttempt the delay for the next attempt if no network
    *                    interfaces are available yet
    */
  private[common] case class InitBinding(nextAttempt: FiniteDuration)

  private[common] class ServerLocatorActor(params: LocatorParams) extends Actor with ActorLogging:

    import context.system

    /** The configuration for network binding. */
    private var multicastConfig: MulticastConfig = _

    override def preStart(): Unit =
      bindAttempt(InitBinding(1.second))

    override def receive: Receive =
      case ib: InitBinding =>
        bindAttempt(ib)

      case Udp.Bound(_) =>
        log.info("Server locator active for interfaces {} on port {}.",
          multicastConfig.interfaces, params.port)
        context.become(active(sender(), generateResponse(multicastConfig.interfaces)))

      case Udp.Unbind =>
        log.info("Received Unbind request before locator was bound.")
        context.stop(self)

    /**
      * A special message handler function that becomes active when all
      * prerequisites have been met to actually handle client requests. This is
      * the case when the UDP bind operation to at least one network interface
      * was successful.
      *
      * @param socket   the actor to represent the UDP socket
      * @param response the response to send to requesting clients
      * @return the message handler function
      */
    private def active(socket: ActorRef, response: String): Receive =
      case Udp.Received(data, remote) =>
        val request = data.utf8String
        log.info("Received request '{}' from {}.", request, remote)
        val locatorRequest = LocatorRequest(
          requestCode = request,
          locatorCode = params.requestCode,
          remote = remote,
          serverAddress = response
        )
        params.handlerFunc(locatorRequest) foreach : locatorResponse =>
          socket ! Udp.Send(ByteString(locatorResponse.payload), locatorResponse.target)

      case Udp.Unbind =>
        socket ! Udp.Unbind
        log.info("Received Unbind request.")

      case Udp.Unbound =>
        log.info("Stopping Server locator.")
        context.stop(self)

    /**
      * Generates the response to be sent for valid templates based on the
      * specified template. If the template contains the placeholder for the
      * address, the local bind address is determined, and the placeholder is
      * replaced by this address.
      *
      * @param interfaces the list of active network interfaces
      * @return the response string
      */
    private def generateResponse(interfaces: Iterable[NetworkInterface]): String =
      if params.responseTemplate.contains(PlaceHolderAddress) then
        interfaces.map(_.localAddress.map(_.getAddress))
          .find(_.isDefined).flatten
          .map(_.getHostAddress)
          .map(address => params.responseTemplate.replace(PlaceHolderAddress, address))
          .getOrElse(params.responseTemplate)
      else
        params.responseTemplate

    /**
      * Schedules a message to itself to trigger another bind operation. The
      * function is called with increasing delays until network interfaces are
      * available to which the actor can bind.
      *
      * @param message the message to send
      * @param delay   the delay after which to send the message
      */
    private[common] def scheduleBind(message: InitBinding, delay: FiniteDuration): Unit =
      log.info("Scheduling another bind operation after {}.", delay)

      given ec: ExecutionContext = context.dispatcher

      context.system.scheduler.scheduleOnce(delay, self, message)

    /**
      * Tries to bind this actor to a UDP port and open a UDP socket for incoming
      * requests. If no network interface is available yet, another attempt is
      * scheduled after a delay.
      *
      * @param initBinding the message that triggered this operation
      */
    private def bindAttempt(initBinding: InitBinding): Unit =
      params.lookupFunc() match
        case Nil =>
          val nextIncrement = initBinding.nextAttempt * 2
          val nextDelay = if nextIncrement < MaxBindRetryDelay then nextIncrement else MaxBindRetryDelay
          scheduleBind(InitBinding(nextDelay), initBinding.nextAttempt)
        case list =>
          logInterfacesForBinding(list)
          multicastConfig = MulticastConfig(params.multicastAddress, list)
          IO(Udp) ! Udp.Bind(self, new InetSocketAddress(params.port), List(multicastConfig))

    /**
      * Print a log message about the local network addresses the server will be
      * bound to. This is helpful to access it from the same machine.
      *
      * @param interfaces the list of supported network interfaces
      */
    private def logInterfacesForBinding(interfaces: List[NetworkInterface]): Unit =
      val addresses = interfaces.flatMap(_.localAddress).mkString
      log.info("Binding server to these addresses: {}.", addresses)
  end ServerLocatorActor

  /**
    * A regular expression to detect requests with an alternative response
    * port.
    */
  private val regExRequestWithPort = "(.+):(\\d{4,5})".r

  /**
    * The default handler function for locator requests. This function checks 
    * whether the request code in the request matches the code from the locator
    * configuration. If this is the case, it returns a response object with the
    * client address and the server address.
    *
    * The request code can contain a port, separated by a ':'. In this case,
    * this port is used for the client address.
    *
    * @param request the object with information about the request
    * @return an [[Option]] with the response to send
    */
  def defaultHandler(request: LocatorRequest): Option[LocatorResponse] =
    val responseAddress = request.requestCode match
      case request.locatorCode =>
        Some(request.remote)
      case regExRequestWithPort(code, port) =>
        Some(new InetSocketAddress(request.remote.getAddress, port.toInt))
      case _ => None
    responseAddress map : remoteAddress =>
      LocatorResponse(payload = request.serverAddress, target = remoteAddress)
