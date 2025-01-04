/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.player.engine.radio.RadioSource

/**
  * A module defining a number of internal messages exchanged by the actors in
  * the ''control'' package that are not owned by an actor implementation.
  *
  * The message classes contained in this module are typically used by the
  * child actors of [[RadioControlActor]] to propagate internal state changes.
  * They are handled by the main controller actor, but are not part of its main
  * protocol. Instead, the controller actor has multiple message adapters that
  * allow child actors to interact with dedicated interfaces customized to
  * their specific use cases.
  *
  * Having those messages in a dedicated module avoids direct cyclic
  * dependencies between [[RadioControlActor]] and its children: The child
  * actors do not need to know their parent actor and its protocol.
  */
private object RadioControlProtocol:
  /**
    * A message class indicating that playback should switch to another source.
    * This message is sent by internal services when they determine that the
    * currently played source needs to be changed according to the defined
    * constraints. It does not represent a source selection on behalf of the
    * user.
    *
    * @param source the source to be played
    */
  case class SwitchToSource(source: RadioSource)

  /**
    * The base trait for commands that update the enabled state of specific
    * radio sources. The subcommands allow disabling or enabling a source.
    */
  sealed trait SourceEnabledStateCommand

  /**
    * A command class that indicates that the given radio source should be
    * disabled.
    *
    * @param source the radio source to disable
    */
  case class DisableSource(source: RadioSource) extends SourceEnabledStateCommand

  /**
    * A command class that indicates that the given radio source should be
    * enabled.
    *
    * @param source the radio source to enable
    */
  case class EnableSource(source: RadioSource) extends SourceEnabledStateCommand
