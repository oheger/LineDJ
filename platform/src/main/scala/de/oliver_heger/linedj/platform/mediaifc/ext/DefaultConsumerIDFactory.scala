/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.ext

import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.{ConsumerID,
ConsumerIDFactory}

import scala.util.Random

/**
  * A default implementation of the ''ConsumerIDFactory'' trait.
  *
  * This class produces IDs that consist of two parts: the owner object passed
  * to the ''createID()'' method, and a (pseudo) random number making the ID
  * unique and more difficult to guess.
  */
class DefaultConsumerIDFactory extends ConsumerIDFactory {
  /** An object for producing random numbers to make IDs unique. */
  private val random = new Random

  override def createID(owner: AnyRef): ConsumerID =
    ConsumerIDImpl(owner, random.nextInt())
}

/**
  * An internal class representing a ''ConsumerID''.
  *
  * @param owner    the object reference pointing to the owner
  * @param identity a numeric value to make the ID unique
  */
private case class ConsumerIDImpl(owner: AnyRef, identity: Int) extends ConsumerID

