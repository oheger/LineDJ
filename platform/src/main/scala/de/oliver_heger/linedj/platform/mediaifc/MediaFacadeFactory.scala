/*
 * Copyright 2015-2021 The Developers Team.
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

import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}

/**
  * A trait defining a factory for a [[MediaFacade]] implementation.
  *
  * In different deployment scenarios the access to the media archive and how
  * it is contacted from a ''MediaFacade'' vary. This trait defines a method
  * how the communication with the archive can be set up.
  *
  * The LineDJ platform expects that a bundle implementing this trait is
  * deployed in the current OSGi container. It fetches the
  * ''MediaFacadeFactory'' service from the OSGi registry and uses it to create
  * a ''MediaFacade'' object. So the set of bundles available in the container
  * determine the way the archive is used.
  */
trait MediaFacadeFactory {
  /**
    * Creates a new ''MediaFacade'' instance that uses the specified
    * ''MessageBus''. As typical implementations make use of actors for the
    * communication with the archive, an ''ActorFactory'' is provided. This
    * method is called once during initialization of the LineDJ platform.
    *
    * @param actorFactory a factory for creating actors
    * @param bus          the ''MessageBus''
    * @return the newly created ''MediaFacade''
    */
  def createMediaFacade(actorFactory: ActorFactory, bus: MessageBus): MediaFacade
}
