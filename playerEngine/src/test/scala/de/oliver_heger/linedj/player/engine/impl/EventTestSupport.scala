/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.time.LocalDateTime

import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.player.engine.PlayerEvent
import org.scalatest.matchers.should.Matchers

import scala.reflect.ClassTag

/**
  * A helper trait for testing whether actors of the player engine generate
  * correct events.
  *
  * This trait offers functionality for expecting events to be passed to a test
  * probe. It is checked whether the event time lies in a reasonable range.
  */
trait EventTestSupport extends TestKit with Matchers {
  /**
    * Expects that an event is received and checks the event time.
    *
    * @param probe the probe for the event actor
    * @param t     the class tag
    * @tparam T the expected event type
    * @return the received event
    */
  def expectEvent[T <: PlayerEvent](probe: TestProbe)(implicit t: ClassTag[T]): T = {
    val event = probe.expectMsgType[T]
    val diff = java.time.Duration.between(event.time, LocalDateTime.now())
    diff.toMillis should be < 250L
    event
  }
}
