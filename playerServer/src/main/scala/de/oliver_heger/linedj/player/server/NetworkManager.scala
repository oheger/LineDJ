/*
 * Copyright 2015-2025 The Developers Team.
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

import java.net.{Inet4Address, InterfaceAddress, NetworkInterface}
import scala.jdk.CollectionConverters.*

/**
  * A module providing some functionality related to querying network 
  * interfaces.
  */
object NetworkManager:
  /**
    * Type alias for a function that looks up the currently available network
    * interfaces. Abstracting this functionality allows replacing it, for 
    * instance in tests.
    */
  type NetworkInterfaceLookupFunc = () => List[NetworkInterface]

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
  private[server] def canBindToInterface(ifc: NetworkInterface): Boolean =
    ifc.isUp && !ifc.isLoopback && isIpv4(ifc)

  /**
    * Filters the given collection for network interfaces for those that can be
    * used by the auto-discovery mechanism of the player server.
    *
    * @param interfaces a collection of network interface
    * @return a list with supported network interfaces
    */
  private[server] def filterSupportedNetworkInterfaces(interfaces: Iterator[NetworkInterface]):
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
