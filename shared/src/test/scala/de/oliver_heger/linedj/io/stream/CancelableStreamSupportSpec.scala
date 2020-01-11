/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.io.stream

import akka.stream.KillSwitch
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''CancelableStreamSupport''.
  */
class CancelableStreamSupportSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a test instance.
    *
    * @return the object to be tested
    */
  private def createSupport(): CancelableStreamSupport =
    new CancelableStreamSupport {}

  "A CancelableStreamSupport" should "cancel registered KillSwitch objects" in {
    val ks1 = mock[KillSwitch]
    val ks2 = mock[KillSwitch]
    val support = createSupport()

    val id1 = support.registerKillSwitch(ks1)
    val id2 = support.registerKillSwitch(ks2)
    id1 should not be id2
    support.cancelCurrentStreams()
    verify(ks1).shutdown()
    verify(ks2).shutdown()
  }

  it should "clear registrations after they were canceled" in {
    val ks = mock[KillSwitch]
    val support = createSupport()
    support registerKillSwitch ks
    support.cancelCurrentStreams()

    support.cancelCurrentStreams()
    verify(ks).shutdown() // only once
  }

  it should "allow removing reigstrations" in {
    val ks1 = mock[KillSwitch]
    val ks2 = mock[KillSwitch]
    val support = createSupport()
    val regId = support registerKillSwitch ks1
    support registerKillSwitch ks2

    support unregisterKillSwitch regId
    support.cancelCurrentStreams()
    verify(ks2).shutdown()
    verify(ks1, never()).shutdown()
  }
}
