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

package de.oliver_heger.linedj.io.stream

import org.apache.pekko.stream.KillSwitch

/**
  * A trait that supports the handling of cancelable streams.
  *
  * This trait can be extended by actors that create streams for processing
  * data, e.g. to download files from an HTTP archive. As such an operation
  * can take some time, there should be a way to cancel it. To enable this,
  * the trait can store ''KillSwitch'' objects integrated into streams. (It is
  * in the responsibility of concrete subclasses to create these objects when
  * they construct their processing streams.) There are operations to remove
  * a ''KillSwitch'' when the associated stream is done and to cancel all
  * currently open streams.
  *
  * It can happen that an actor has multiple streams open at a given time.
  * Therefore, there needs to be a way to identify such streams - when a
  * stream completes the correct ''KillSwitch'' has to be removed. This
  * trait generates unique integer numbers when a ''KillSwitch'' is
  * registered. This number has to be stored and used for further
  * interaction with this trait.
  *
  * It lies also in the responsibility of a concrete implementation to make
  * sure that the methods of this trait are invoked safely, i.e. from an
  * actor's ''receive()'' method. For instance, it is not sufficient to
  * remove a ''KillSwitch'' from a callback that is run when the ''Future'' of
  * the stream completes; rather, a corresponding message has to be sent to
  * the ''self'' actor, and on reaction of this method the ''KillSwitch'' can
  * be removed.
  */
trait CancelableStreamSupport {
  /** A mapping with the currently registered kill switches. */
  private var currentKillSwitches = Map.empty[Int, KillSwitch]

  /** A counter for generating IDs for kill switches. */
  private var idCounter = 0

  /**
    * Registers a ''KillSwitch'' object. It is stored internally, so that it is
    * possible to cancel the associated stream. When the stream has completed
    * the registration should be removed again.
    *
    * @param ks the ''KillSwitch''
    * @return a numeric identifier representing this registration
    */
  def registerKillSwitch(ks: KillSwitch): Int = {
    idCounter += 1
    currentKillSwitches += (idCounter -> ks)
    idCounter
  }

  /**
    * Removes a registration for a ''KillSwitch''. This method should be
    * invoked when the associated stream is complete.
    *
    * @param ksID the ID of the ''KillSwitch''
    */
  def unregisterKillSwitch(ksID: Int): Unit = {
    currentKillSwitches -= ksID
  }

  /**
    * Cancels all streams that are currently open. This method triggers all
    * registered ''KillSwitch'' objects. Afterwards all registrations are
    * removed.
    */
  def cancelCurrentStreams(): Unit = {
    currentKillSwitches.values foreach (_.shutdown())
    currentKillSwitches = Map.empty
  }
}
