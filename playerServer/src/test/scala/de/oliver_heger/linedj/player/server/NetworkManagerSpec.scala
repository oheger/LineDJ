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

import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.net.{InetAddress, NetworkInterface}
import java.util
import java.util.Collections

class NetworkManagerSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  /**
    * Creates a mock for a [[NetworkInterface]] based on the given parameters.
    *
    * @param isUp       flag whether the interface is up
    * @param isLoopBack flag whether this is the loopback interface
    * @param addresses  a list of internet addresses for the interface
    * @return the mock interface with these parameters
    */
  private def createMockInterface(isUp: Boolean = true,
                                  isLoopBack: Boolean = false,
                                  addresses: List[String] = List("192.168.0.1")): NetworkInterface =
    val ifc = mock[NetworkInterface]
    when(ifc.isUp).thenReturn(isUp)
    when(ifc.isLoopback).thenReturn(isLoopBack)
    val addressList = new util.ArrayList[InetAddress]
    addresses.foreach { adr =>
      addressList.add(InetAddress.getByName(adr))
    }
    when(ifc.getInetAddresses).thenReturn(Collections.enumeration(addressList))
    ifc

  "canBindToInterface" should "return true for a usable interface" in :
    val ifc = createMockInterface()

    NetworkManager.canBindToInterface(ifc) shouldBe true

  it should "return false if the interface is not up" in :
    val ifc = createMockInterface(isUp = false)

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return false if the interface is the loopback interface" in :
    val ifc = createMockInterface(isLoopBack = true)

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return false if the interface has no addresses" in :
    val ifc = createMockInterface(addresses = Nil)

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return false if the interface has only IPv6 addresses" in :
    val ifc = createMockInterface(addresses = List("1080:0:0:0:8:800:200C:417A"))

    NetworkManager.canBindToInterface(ifc) shouldBe false

  it should "return true if there is at least one IPv4 address" in :
    val ifc = createMockInterface(addresses = List("1080:0:0:0:8:800:200C:417A", "192.168.0.1"))

    NetworkManager.canBindToInterface(ifc) shouldBe true

  "filterSupportedInterfaces" should "filter out the correct network interfaces" in :
    val supportedIfc = createMockInterface()
    val interfaces = List(
      createMockInterface(isUp = false),
      createMockInterface(isLoopBack = true),
      supportedIfc,
      createMockInterface(addresses = List("1080:0:0:0:8:800:200C:417A"))
    )

    val supportedInterfaces = NetworkManager.filterSupportedNetworkInterfaces(interfaces.iterator)

    supportedInterfaces should contain only supportedIfc
