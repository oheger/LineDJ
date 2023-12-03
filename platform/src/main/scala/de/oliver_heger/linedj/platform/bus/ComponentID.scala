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

package de.oliver_heger.linedj.platform.bus

import scala.util.Random

object ComponentID:
  /** An object for generating unique numeric IDs. */
  private val random = new Random

  /**
    * Creates a new instance of ''ComponentID''. This factory method is the
    * only way to create new instances of this class. The caller has no
    * influence on the internal structure of the generated object.
    *
    * @return the new ''ComponentID'' instance
    */
  def apply(): ComponentID = new ComponentID(random.nextInt())

/**
  * A class representing an ID of a component.
  *
  * When applications use the UI message bus it is sometimes necessary that
  * they can be uniquely identified; so some sort of ID is required. This class
  * is used as a component ID.
  *
  * In principle, it would be possible to use the object references of
  * component instances directly as IDs; they fulfill the uniqueness criterion.
  * Using a separate class for this purpose, however, makes it more explicit
  * that the objects involved are IDs, and that they therefore have to have
  * specific properties.
  *
  * The internal structure of a ''ComponentID'' object is not visible to the
  * outside. New instances are created via the companion object and cannot be
  * modified afterwards. It is also not possible to create a ''ComponentID''
  * object that matches another instance.
  *
  * @param numID a numeric ID used for generating a unuqie string representation
  */
final class ComponentID private(numID: Int):
  /**
    * @inheritdoc This implementation returns a string that contains a numeric
    *             ID. This ID is only used to support debug output, to have an
    *             assignment between an object and a readable ID. It is not
    *             used for other purposes like equals calculation.
    */
  override def toString: String = s"ComponentID [$numID]"
