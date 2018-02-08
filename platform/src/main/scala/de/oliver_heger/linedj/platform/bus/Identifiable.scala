/*
 * Copyright 2015-2018 The Developers Team.
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

/**
  * A trait that can be extended by classes that need to have a unique
  * [[ComponentID]].
  *
  * All that this trait does is defining and initializing a component ID
  * property. By mixing in this trait, the affected class is automatically
  * assigned this ID.
  */
trait Identifiable {
  /**
    * The ''ComponentID'' of this object.
    */
  final val componentID: ComponentID = ComponentID()
}
