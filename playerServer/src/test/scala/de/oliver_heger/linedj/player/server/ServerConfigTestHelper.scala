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
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.client.config.RadioPlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioSourceConfig}
import de.oliver_heger.linedj.utils.{ActorFactory, ActorManagement}

import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/**
  * A test helper object providing functionality related to server 
  * configurations, actor creator implementations, and handling of futures.
  */
object ServerConfigTestHelper:
  /** The timeout when waiting for a future. */
  private val FutureTimeout = 3.seconds

  /**
    * A data class defining a radio source as used in tests. This module has
    * functionality to generate a radio source configuration out of instances
    * of this class.
    *
    * @param name    the name of the radio source
    * @param ranking a ranking for this source
    */
  case class TestRadioSource(name: String,
                             ranking: Int = 0):
    /**
      * Return a URI for this radio source that is derived from the name.
      *
      * @return the URI for this radio source
      */
    def uri: String = s"https://radio.example.org/$name"

    /**
      * Generates a [[RadioSource]] from the properties of this test source.
      *
      * @return the corresponding [[RadioSource]]
      */
    def toRadioSource: RadioSource = RadioSource(uri)
  end TestRadioSource

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
    * Generates a [[RadioSourceConfig]] that contains the given test radio
    * sources. Further properties are not taken into account.
    *
    * @param testSources the test radio sources
    * @return the [[RadioSourceConfig]] with these test sources
    */
  def radioSourceConfig(testSources: Seq[TestRadioSource]): RadioSourceConfig =
    val radioSources = testSources.map(s => RadioSource(s.uri))
    new RadioSourceConfig:
      override val namedSources: Seq[(String, RadioSource)] =
        testSources.map(_.name).zip(radioSources)

      override def exclusions(source: RadioSource): Seq[IntervalQuery] = Seq.empty

      override def ranking(source: RadioSource): Int =
        testSources.find(_.uri == source.uri).map(_.ranking).getOrElse(0)

  /**
    * Creates a [[PlayerServerConfig]] object with default settings and the
    * given [[ActorCreator]].
    *
    * @param creator the [[ActorCreator]]
    * @param sources a list with test radio sources to add to the config
    * @return the initialized configuration
    */
  def defaultServerConfig(creator: ActorCreator,
                          sources: Seq[TestRadioSource] = Nil): PlayerServerConfig =
    val playerConfig = PlayerConfigLoader.defaultConfig(null, creator)
    val radioConfig = RadioPlayerConfigLoader.DefaultRadioPlayerConfig.copy(playerConfig = playerConfig)
    PlayerServerConfig(radioPlayerConfig = radioConfig,
      sourceConfig = radioSourceConfig(sources),
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

