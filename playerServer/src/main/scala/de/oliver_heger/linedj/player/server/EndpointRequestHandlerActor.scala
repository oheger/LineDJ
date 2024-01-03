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

import de.oliver_heger.linedj.player.server.EndpointRequestHandlerActor.{HandlerReady, MulticastConfig, PlaceHolderAddress}
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, typed}
import org.apache.pekko.io.Inet.SocketOptionV2
import org.apache.pekko.io.{IO, Udp}
import org.apache.pekko.util.ByteString

import java.net.{DatagramSocket, InetAddress, InetSocketAddress, NetworkInterface}
import scala.jdk.CollectionConverters.*

private object EndpointRequestHandlerActor:
  /**
    * Constant for a placeholder in the template for the response to be sent
    * for valid requests that is to be replaced by the current IP address.
    */
  final val PlaceHolderAddress = "$address"

  /**
    * Returns a ''Props'' object for creating a new instance of this actor.
    *
    * @param groupAddress     the address of the multicast group
    * @param port             the port the actor should listen on
    * @param requestCode      the expected request code
    * @param responseTemplate the template for the response to send
    * @param readyListener    an optional listener that is notified when the actor
    *                         is ready to serve requests
    * @return a ''Props'' object for creating a new instance
    */
  def props(groupAddress: String,
            port: Int,
            requestCode: String,
            responseTemplate: String,
            readyListener: Option[typed.ActorRef[HandlerReady]] = None): Props =
    val interfaces = fetchActiveNetworkInterfaces()
    val multicastConfig = MulticastConfig(groupAddress, interfaces)
    Props(new EndpointRequestHandlerActor(multicastConfig, port, requestCode, responseTemplate, readyListener))

  /**
    * A message that is sent to the ready listener when the handler actor is
    * ready to handle requests.
    *
    * @param interfaces the network interfaces the actor supports
    */
  case class HandlerReady(interfaces: List[NetworkInterface])

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
  * The answer is actually a template that can contain the placeholders
  * ''$address''. This placeholder is replaced by a local IP address of one of
  * the interfaces the actor was bound to. Since the HTTP server typically uses
  * the same local address, the URL to its endpoint can be generated this way.
  *
  * @param groups           the object with the multicast configuration
  * @param port             the port the actor should listen on
  * @param requestCode      the expected request code
  * @param responseTemplate the template to generate the response to send
  * @param readyListener    an optional listener that is notified when the actor
  *                         is ready to serve requests
  */
private class EndpointRequestHandlerActor(groups: MulticastConfig,
                                          port: Int,
                                          requestCode: String,
                                          responseTemplate: String,
                                          readyListener: Option[typed.ActorRef[HandlerReady]]) extends Actor
  with ActorLogging:

  import context.system

  override def preStart(): Unit =
    IO(Udp) ! Udp.Bind(self, new InetSocketAddress(port), List(groups))

  override def receive: Receive =
    case Udp.Bound(_) =>
      log.info("EndpointRequestHandlerActor active for interfaces {} on port {}.",
        groups.interfaces, port)

      context.become(active(sender(), generateResponse(groups.interfaces)))
      readyListener foreach (_ ! HandlerReady(groups.interfaces.toList))

  private def active(socket: ActorRef, response: String): Receive =
    case Udp.Received(data, remote) =>
      val request = data.utf8String
      log.info("Received request '{}' from {}.", request, remote)
      if request == requestCode then
        socket ! Udp.Send(ByteString(response), remote)

    case Udp.Unbind =>
      socket ! Udp.Unbind
      log.info("EndpointRequestHandlerActor: Received Unbind request.")

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
