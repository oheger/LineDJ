/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.media

import java.nio.file.Path

import akka.actor.Actor
import de.oliver_heger.linedj.media.MediumIDCalculatorActor.CalculateMediumID

/**
 * Companion object.
 */
object MediumIDCalculatorActor {

  /**
   * A message processed by ''MediumIDCalculatorActor'' telling it that it
   * should calculate a medium ID based on the passed in information.
   * @param mediumRoot the root path of the medium
   * @param mediumURI the medium URI
   * @param mediumContent a sequence with the media files on this medium
   */
  private[media] case class CalculateMediumID(mediumRoot: Path, mediumURI: String, mediumContent:
  Seq[MediaFile])

}

/**
 * A specialized actor implementation which wraps a [[MediumIDCalculator]].
 *
 * This actor class is instantiated with a ''MediumIDCalculator'' object to be
 * wrapped. It accepts messages for calculating the ID of a medium based on its
 * content. Such messages are handled by delegating to the associated
 * calculator. The resulting data object with the medium ID and the content
 * file URIs is then sent back to the calling actor.
 *
 * @param calculator the ''MediumIDCalculator'' to be used by this instance
 */
class MediumIDCalculatorActor(calculator: MediumIDCalculator) extends Actor {
  override def receive: Receive = {
    case CalculateMediumID(mediumRoot, mediumURI, mediumContent) =>
      sender ! calculator.calculateMediumID(mediumRoot, mediumURI, mediumContent)
  }
}
