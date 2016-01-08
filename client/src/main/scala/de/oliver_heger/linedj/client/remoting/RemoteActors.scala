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

package de.oliver_heger.linedj.client.remoting

/**
 * An object defining an enumeration for the remote actors available.
 *
 * This enumeration is used when communicating with the remote actor system.
 * The actors which are tracked and which can be accessed from the client are
 * identified by specific constants. Via these constants, they can be uniquely
 * identified, e.g. when sending messages to them.
 *
 * Each constant is assigned the name of the represented remote actor. This is
 * used internally to construct paths to the remote actor system.
 */
object RemoteActors {

  /**
   * Base class for all constants representing remote actors.
   * @param name the name of the remote actor
   */
  sealed abstract class RemoteActor(val name: String)

  /**
   * Constant for the ''MediaManager'' remote actor.
   */
  case object MediaManager extends RemoteActor("mediaManager")

  /**
   * Constant for the ''MetaDataManager'' remote actor.
   */
  case object MetaDataManager extends RemoteActor("metaDataManager")

  /**
   * A set with all constants defined by the ''RemoteActors''
   * enumeration.
   */
  val values = Set(MediaManager, MetaDataManager)
}
