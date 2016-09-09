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

package de.oliver_heger.linedj.client.mediaifc

import org.apache.commons.configuration.Configuration

/**
  * Package object for the ''remote'' package.
  */
package object remote {
  /**
    * Configuration property for the host of the remote media archive.
    */
  val PropMediaArchiveHost = "media.host"

  /**
    * Configuration property for the port of the remote media archive.
    */
  val PropMediaArchivePort = "media.port"

  /**
    * Configuration property for the name of the actor system for the remote
    * media archive.
    */
  val PropMediaArchiveSystemName = "media.systemName"

  /** Constant for the default media archive address. */
  val DefaultServerAddress = "127.0.0.1"

  /** Constant for the default media archive port. */
  val DefaultServerPort = 2552

  /** Constant for the default actor system name. */
  val DefaultActorSystemName = "LineDJ-Server"

  /**
    * Reads the setting for the media archive host from the given configuration
    * object. If the property is undefined, the default host is returned.
    *
    * @param config the configuration object
    * @return the host of the media archive
    */
  def readHost(config: Configuration): String =
  config.getString(PropMediaArchiveHost, DefaultServerAddress)

  /**
    * Reads the setting for the media archive port from the given configuration
    * object. If the property is undefined, the default port is returned.
    *
    * @param config the configuration object
    * @return the port of the media archive
    */
  def readPort(config: Configuration): Int =
  config.getInt(PropMediaArchivePort, DefaultServerPort)

  /**
    * Reads the setting for the name of the remote actor system from the given
    * configuration object. If the property is undefined, the default actor
    * system name is returned.
    *
    * @param config the configuration object
    * @return the name of the remote actor system
    */
  def readActorSystemName(config: Configuration): String =
  config.getString(PropMediaArchiveSystemName, DefaultActorSystemName)
}
