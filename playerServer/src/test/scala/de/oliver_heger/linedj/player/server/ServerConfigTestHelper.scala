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

package de.oliver_heger.linedj.player.server

import akka.actor.ActorSystem
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.{ManagingActorCreator, PlayerConfigLoader}
import de.oliver_heger.linedj.player.engine.radio.client.config.RadioPlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioSourceConfig}
import de.oliver_heger.linedj.utils.{ActorFactory, ActorManagement}

import java.nio.file.Paths
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/**
  * A test helper object providing functionality related to server 
  * configurations, actor creator implementations, and handling of futures.
  */
object ServerConfigTestHelper:
  /** The timeout when waiting for a future. */
  private val FutureTimeout = 3.seconds

  /**
    * Waits for the given [[Future]] to be completed and returns its result or
    * throws an exception if the future failed or does not complete within the
    * timeout. Note that this duplicates functionality from the
    * ''AsyncTestHelper'' trait; unfortunately, due to conflicts between
    * dependencies for Scala 3 and 2.13, it is not possible to use the trait.
    *
    * @param future the [[Future]]
    * @tparam T the result type of the future
    * @return the completed value of the future
    */
  def futureResult[T](future: Future[T]): T =
    Await.result(future, FutureTimeout)

  /**
    * Creates a [[ManagingActorCreator]] object that is backed by the given
    * actor system.
    *
    * @param system the actor system
    * @return the [[ManagingActorCreator]]
    */
  def actorCreator(system: ActorSystem): ManagingActorCreator =
    val factory = new ActorFactory(system)
    val management = new ActorManagement {}
    new ManagingActorCreator(factory, management)

  /**
    * Creates a [[PlayerServerConfig]] object with default settings and the
    * given [[ActorCreator]].
    *
    * @param creator the [[ActorCreator]]
    * @return the initialized configuration
    */
  def defaultServerConfig(creator: ActorCreator): PlayerServerConfig =
    val playerConfig = PlayerConfigLoader.defaultConfig(null, creator)
    val radioConfig = RadioPlayerConfigLoader.DefaultRadioPlayerConfig.copy(playerConfig = playerConfig)
    PlayerServerConfig(radioPlayerConfig = radioConfig,
      sourceConfig = RadioSourceConfig.Empty,
      metadataConfig = MetadataConfig.Empty,
      serverPort = PlayerServerConfig.DefaultServerPort,
      lookupMulticastAddress = PlayerServerConfig.DefaultLookupMulticastAddress,
      lookupPort = PlayerServerConfig.DefaultLookupPort,
      lookupCommand = PlayerServerConfig.DefaultLookupCommand,
      uiContentFolder = Paths get PlayerServerConfig.DefaultUiContentFolder,
      uiPath = PlayerServerConfig.DefaultUiPath)

  extension (config: PlayerServerConfig)

    /**
      * Returns the [[ActorManagement]] instance referenced by this
      * configuration.
      *
      * @return the [[ActorManagement]] used by the actor creator in this
      *         configuration
      */
    def getActorManagement: ActorManagement =
      config.radioPlayerConfig.playerConfig.actorCreator match
        case managingActorCreator: ManagingActorCreator =>
          managingActorCreator.actorManagement
        case c =>
          throw new AssertionError("Unexpected ActorCreator: " + c)

