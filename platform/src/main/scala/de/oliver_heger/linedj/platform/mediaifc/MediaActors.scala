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

package de.oliver_heger.linedj.platform.mediaifc

/**
 * An object defining an enumeration for the actors used by the interface to
 * the media archive.
 *
 * This enumeration is used when communicating with the media archive.
 * The actors which are tracked and which can be accessed from the client are
 * identified by specific constants. Via these constants, they can be uniquely
 * identified, e.g. when sending messages to them.
 *
 * Each constant is assigned the name of the represented media actor. This is
 * used internally to construct paths to the actors in the actor system.
 */
object MediaActors {

  /**
   * Base class for all constants representing media actors.
   * @param name the name of the media actor
   */
  sealed abstract class MediaActor(val name: String)

  /**
   * Constant for the ''MediaManager'' remote actor.
   */
  case object MediaManager extends MediaActor("mediaManager")

  /**
   * Constant for the ''MetaDataManager'' remote actor.
   */
  case object MetaDataManager extends MediaActor("metaDataManager")

  /**
   * A set with all constants defined by the ''RemoteActors''
   * enumeration.
   */
  val values = Set(MediaManager, MetaDataManager)
}
