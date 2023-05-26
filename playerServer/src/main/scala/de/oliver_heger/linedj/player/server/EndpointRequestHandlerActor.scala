/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.actor.typed
import akka.io.{IO, Udp}
import akka.io.Inet.SocketOptionV2
import akka.util.ByteString
import de.oliver_heger.linedj.player.server.EndpointRequestHandlerActor.{HandlerReady, MulticastConfig}

import java.net.{DatagramSocket, InetAddress, InetSocketAddress, NetworkInterface}
import scala.jdk.CollectionConverters._

private object EndpointRequestHandlerActor:
  /**
    * Returns a ''Props'' object for creating a new instance of this actor.
    * @param groupAddress the address of the multicast group
    * @param port the port the actor should listen on
    * @param requestCode the expected request code
    * @param response the response to send
    * @param readyListener an optional listener that is notified when the actor
    *                      is ready to serve requests
    * @return a ''Props'' object for creating a new instance
    */
  def props(groupAddress: String,
            port: Int,
            requestCode: String,
            response: String,
            readyListener: Option[typed.ActorRef[HandlerReady]] = None): Props =
    val interfaces = fetchRelevantNetworkInterfaces()
    val multicastConfig = MulticastConfig(groupAddress, interfaces)
    Props(new EndpointRequestHandlerActor(multicastConfig, port, requestCode, response, readyListener))

  /**
    * Returns a list with the active network interfaces that are relevant for
    * this actor. The actor joins the multicast group for each of these
    * interfaces.
    * @return the list with relevant network interfaces
    */
  private [server] def fetchRelevantNetworkInterfaces(): List[NetworkInterface] =
    NetworkInterface.getNetworkInterfaces.asScala
    .filterNot(ifc => ifc.isLoopback || !ifc.isUp)
      .toList


  /**
    * A message that is sent to the ready listener when the handler actor is
    * ready to handle requests.
    * @param interfaces the network interfaces the actor supports
    */
  case class HandlerReady(interfaces: List[NetworkInterface])

  /**
    * An internal helper class that configures the [[DatagramSocket]] used by
    * the actor for UDP multicast.
    * @param multicastAddress the address of the multicast group to join
    * @param interfaces the list of network interfaces to support
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
  * @param groups the object with the multicast configuration
  * @param port the port the actor should listen on
  * @param requestCode the expected request code
  * @param response the response to send
  * @param readyListener an optional listener that is notified when the actor
  *                      is ready to serve requests
  */
private class EndpointRequestHandlerActor(groups: MulticastConfig,
                                          port: Int,
                                          requestCode: String,
                                          response: String,
                                          readyListener: Option[typed.ActorRef[HandlerReady]]) extends Actor
  with ActorLogging:

  import context.system

  override def preStart(): Unit =
    IO(Udp) ! Udp.Bind(self, new InetSocketAddress(port), List(groups))

  override def receive: Receive =
    case Udp.Bound(_) =>
      log.info("ListenerActor active for interfaces {} on port {}.",
        groups.interfaces, port)
      context.become(active(sender()))
      readyListener foreach(_ ! HandlerReady(groups.interfaces.toList))

  private def active(socket: ActorRef): Receive =
    case Udp.Received(data, remote) =>
      val request = data.utf8String
      log.info("Received request '{}' from {}.", request, remote)
      if request == requestCode then
        socket ! Udp.Send(ByteString(response), remote)

    case Udp.Unbind => socket ! Udp.Unbind

    case Udp.Unbound => context.stop(self)
