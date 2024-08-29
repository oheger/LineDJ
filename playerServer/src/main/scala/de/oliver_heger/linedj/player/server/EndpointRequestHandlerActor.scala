/*
 * Copyright 2015-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.server.EndpointRequestHandlerActor.{InitBinding, MaxBindRetryDelay, MulticastConfig, PlaceHolderAddress}
import de.oliver_heger.linedj.player.server.NetworkManager.*
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.pekko.io.Inet.SocketOptionV2
import org.apache.pekko.io.{IO, Udp}
import org.apache.pekko.util.ByteString

import java.net.{DatagramSocket, InetAddress, InetSocketAddress, NetworkInterface}
import java.util.regex.Pattern
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

private object EndpointRequestHandlerActor:
  /**
    * Constant for a placeholder in the template for the response to be sent
    * for valid requests that is to be replaced by the current IP address.
    */
  final val PlaceHolderAddress = "$address"

  /** The maximum delay for retrying a bind operation. */
  final val MaxBindRetryDelay = 5.minutes

  /**
    * Returns a ''Props'' object for creating a new instance of this actor.
    *
    * @param groupAddress     the address of the multicast group
    * @param port             the port the actor should listen on
    * @param requestCode      the expected request code
    * @param responseTemplate the template for the response to send
    * @param lookupFunc       the function for looking up network interfaces
    * @return a ''Props'' object for creating a new instance
    */
  def props(groupAddress: String,
            port: Int,
            requestCode: String,
            responseTemplate: String,
            lookupFunc: NetworkManager.NetworkInterfaceLookupFunc = NetworkManager.DefaultNetworkInterfaceLookupFunc):
  Props =
    Props(new EndpointRequestHandlerActor(groupAddress, port, requestCode, responseTemplate, lookupFunc))

  /**
    * An internal helper class that configures the [[DatagramSocket]] used by
    * the actor for UDP multicast.
    *
    * @param multicastAddress the address of the multicast group to join
    * @param interfaces       the list of network interfaces to support
    */
  case class MulticastConfig(multicastAddress: String,
                             interfaces: Iterable[NetworkInterface]) extends SocketOptionV2:
    override def afterBind(s: DatagramSocket): Unit =
      val group = InetAddress.getByName(multicastAddress)
      interfaces foreach { interface =>
        s.getChannel.join(group, interface)
      }

  /**
    * An internal message the actor sends to itself to trigger the binding
    * against a network interface. Before this is possible, network interfaces
    * must be available. If this is not the case, another attempt is made after
    * a delay.
    *
    * @param nextAttempt the delay for the next attempt if no network
    *                    interfaces are available yet
    */
  private[server] case class InitBinding(nextAttempt: FiniteDuration)
end EndpointRequestHandlerActor

/**
  * An actor implementation that handles incoming UDP requests that ask for the
  * endpoint address of the server.
  *
  * The purpose of this actor is to allow devices in the local network to
  * determine the URL of the player service. For this purpose, devices send a
  * UDP multicast request to a specific group with a specific content. This
  * actor listens for incoming requests on this group for all available network
  * interfaces and answers them. Both, the request code and the answer can be
  * configured when creating an instance.
  *
  * The most simple request handled by this actor just consists of the request
  * code. In this case, the answer is sent to the address of the caller. It is
  * also possible to specify an alternative target port by appending the
  * desired port to the request code separated by a colon (':').
  *
  * The answer is actually a template that can contain the placeholder
  * ''$address''. This placeholder is replaced by a local IP address of one of
  * the interfaces the actor was bound to. Since the HTTP server typically uses
  * the same local address, the URL to its endpoint can be generated this way.
  *
  * For this actor to work, at least one network interface must be available.
  * During its initialization, the actor checks whether this is the case. If 
  * not, it remains inactive and checks against at a later point in time.
  *
  * @param multicastAddress the multicast address to listen for
  * @param port             the port the actor should listen on
  * @param requestCode      the expected request code
  * @param responseTemplate the template to generate the response to send
  * @param lookupFunc       the function for looking up network interfaces
  */
private class EndpointRequestHandlerActor(multicastAddress: String,
                                          port: Int,
                                          requestCode: String,
                                          responseTemplate: String,
                                          lookupFunc: NetworkInterfaceLookupFunc) extends Actor
  with ActorLogging:

  import context.system

  /**
    * A regular expression to detect requests with an alternative response
    * port.
    */
  private val regExRequestWithPort = (Pattern.quote(requestCode) + ":(\\d{4,5})").r

  /** The configuration for network binding. */
  private var multicastConfig: MulticastConfig = _

  override def preStart(): Unit =
    bindAttempt(InitBinding(1.second))

  override def receive: Receive =
    case ib: InitBinding =>
      bindAttempt(ib)

    case Udp.Bound(_) =>
      log.info("EndpointRequestHandlerActor active for interfaces {} on port {}.",
        multicastConfig.interfaces, port)
      context.become(active(sender(), generateResponse(multicastConfig.interfaces)))

    case Udp.Unbind =>
      log.info("Received Unbind request before actor was bound.")
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
      responseAddress(request, remote) foreach { address =>
        socket ! Udp.Send(ByteString(response), address)
      }

    case Udp.Unbind =>
      socket ! Udp.Unbind
      log.info("Received Unbind request.")

    case Udp.Unbound =>
      log.info("Stopping EndpointRequestHandlerActor.")
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
    if responseTemplate.contains(PlaceHolderAddress) then
      interfaces.map(_.localAddress.map(_.getAddress))
        .find(_.isDefined).flatten
        .map(_.getHostAddress)
        .map(address => responseTemplate.replace(PlaceHolderAddress, address))
        .getOrElse(responseTemplate)
    else
      responseTemplate

  /**
    * Schedules a message to itself to trigger another bind operation. The
    * function is called with increasing delays until network interfaces are
    * available to which the actor can bind.
    *
    * @param message the message to send
    * @param delay   the delay after which to send the message
    */
  private[server] def scheduleBind(message: InitBinding, delay: FiniteDuration): Unit =
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
    lookupFunc() match
      case Nil =>
        val nextIncrement = initBinding.nextAttempt * 2
        val nextDelay = if nextIncrement < MaxBindRetryDelay then nextIncrement else MaxBindRetryDelay
        scheduleBind(InitBinding(nextDelay), initBinding.nextAttempt)
      case list =>
        multicastConfig = MulticastConfig(multicastAddress, list)
        IO(Udp) ! Udp.Bind(self, new InetSocketAddress(port), List(multicastConfig))

  /**
    * Checks the given request, and for valid requests, returns the address
    * where to send the response to.
    *
    * @param request the request as string that was received
    * @param remote  the address of the caller
    * @return an ''Option'' with the response address; ''None'' means that no
    *         response should be sent, since the request was invalid
    */
  private def responseAddress(request: String, remote: InetSocketAddress): Option[InetSocketAddress] =
    request match
      case `requestCode` =>
        Some(remote)
      case regExRequestWithPort(port) =>
        Some(new InetSocketAddress(remote.getAddress, port.toInt))
      case _ => None
