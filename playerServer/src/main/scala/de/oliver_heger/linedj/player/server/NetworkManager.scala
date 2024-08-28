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

import java.net.{InterfaceAddress, NetworkInterface}
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
  final val DefaultNetworkInterfaceLookupFunc = fetchActiveNetworkInterfaces _ 
  
  /**
    * Returns a list with the active network interfaces that are relevant for
    * binding servers.
    *
    * @return the list with relevant network interfaces
    */
  private def fetchActiveNetworkInterfaces(): List[NetworkInterface] =
    NetworkInterface.getNetworkInterfaces.asScala
      .filterNot(ifc => ifc.isLoopback || !ifc.isUp)
      .toList
  
  extension (ifc: NetworkInterface)
    def localAddress: Option[InterfaceAddress] =
      ifc.getInterfaceAddresses.asScala.find(_.getAddress.isSiteLocalAddress)
end NetworkManager
