/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.matchers.should.Matchers

import java.time.LocalDateTime
import scala.reflect.ClassTag

/**
  * A helper trait for testing whether actors of the player engine generate
  * correct events.
  *
  * This trait offers functionality for expecting events to be passed to a test
  * probe. It is checked whether the event time lies in a reasonable range.
  *
  * @tparam EVENT the event type to be handled
  */
trait EventTestSupport[EVENT]:
  this: Matchers =>

  /**
    * Expects that an event is received and checks the event time.
    *
    * @param probe the probe for the typed event actor
    * @param t     the class tag
    * @tparam T the expected event type
    * @return the received event
    */
  def expectEvent[T <: EVENT](probe: TestProbe[EVENT])
                             (implicit t: ClassTag[T]): T =
    val event = probe.expectMessageType[T]
    assertCurrentTime(eventTimeExtractor(event))
    event

  /**
    * Checks whether the given time is close to the current system time. This
    * function can be used to check invocations with time values that should be
    * the current time.
    *
    * @param time        the time to check
    * @param deltaMillis the accepted difference to the system time (in millis)
    */
  def assertCurrentTime(time: LocalDateTime, deltaMillis: Long = 250): Unit =
    val diff = java.time.Duration.between(time, LocalDateTime.now())
    diff.toMillis should be < 250L

  /**
    * Returns a function to extract the time of an event.
    *
    * @return the function to extract the event time
    */
  protected def eventTimeExtractor: EVENT => LocalDateTime
