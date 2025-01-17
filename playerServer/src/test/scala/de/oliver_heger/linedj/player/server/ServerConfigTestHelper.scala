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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.{ManagingActorCreator, PlayerConfigLoader}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.client.config.RadioPlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioSourceConfig}
import de.oliver_heger.linedj.utils.{ActorFactory, ActorManagement}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}

import java.nio.file.Paths
import scala.collection.immutable

/**
  * A test helper object providing functionality related to server 
  * configurations, actor creator implementations, and handling of futures.
  */
object ServerConfigTestHelper:
  /**
    * A data class defining a radio source as used in tests. This module has
    * functionality to generate a radio source configuration out of instances
    * of this class.
    *
    * @param name            the name of the radio source
    * @param ranking         a ranking for this source
    * @param favoriteIndex   the favorite index for favorite sources
    * @param optFavoriteName optional favorite name
    */
  case class TestRadioSource(name: String,
                             ranking: Int = 0,
                             favoriteIndex: Int = -1,
                             optFavoriteName: Option[String] = None):
    /**
      * Return a URI for this radio source that is derived from the name.
      *
      * @return the URI for this radio source
      */
    def uri: String = s"https://radio.example.org/$name"

    /**
      * Returns the ID for this radio source as it would be calculated from the
      * server.
      *
      * @return the calculated radio source ID
      */
    def id: String = Routes.calculateID(name, uri)

    /**
      * Generates a [[RadioSource]] from the properties of this test source.
      *
      * @return the corresponding [[RadioSource]]
      */
    def toRadioSource: RadioSource = RadioSource(uri)
  end TestRadioSource

  /**
    * Creates a [[ManagingActorCreator]] object that is backed by the given
    * actor system. If an [[ActorTestKit]] is provided, the creation of typed
    * actors is delegated to this object.
    *
    * @param system     the actor system
    * @param optTestKit an optional test kit for typed actors
    * @return the [[ManagingActorCreator]]
    */
  def actorCreator(system: ActorSystem, optTestKit: Option[ActorTestKit] = None): ManagingActorCreator =
    val factory = new ActorFactory(system) {
      override def createActor[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] =
        optTestKit match
          case Some(value) =>
            value.spawn(behavior, name, props)
          case None =>
            super.createActor(behavior, name, props)
    }
    val management = new ActorManagement {}
    new ManagingActorCreator(factory, management)

  /**
    * Generates a [[RadioSourceConfig]] that contains the given test radio
    * sources. Further properties are not taken into account.
    *
    * @param testSources the test radio sources
    * @return the [[RadioSourceConfig]] with these test sources
    */
  def radioSourceConfig(testSources: immutable.Seq[TestRadioSource]): RadioSourceConfig =
    val radioSources = testSources.map(s => RadioSource(s.uri))
    new RadioSourceConfig:
      override val namedSources: immutable.Seq[(String, RadioSource)] =
        testSources.map(_.name).zip(radioSources)

      override def exclusions(source: RadioSource): immutable.Seq[IntervalQuery] = Seq.empty

      override def ranking(source: RadioSource): Int =
        testSources.find(_.uri == source.uri).map(_.ranking).getOrElse(0)

      override def favorites: immutable.Seq[(String, RadioSource)] =
        testSources.filter(_.favoriteIndex >= 0)
          .sortBy(_.favoriteIndex)
          .map(source => (source.optFavoriteName getOrElse source.name, source.toRadioSource))

  /**
    * Creates a [[PlayerServerConfig]] object with default settings and the
    * given [[ActorCreator]].
    *
    * @param creator the [[ActorCreator]]
    * @param sources a list with test radio sources to add to the config
    * @return the initialized configuration
    */
  def defaultServerConfig(creator: ActorCreator,
                          sources: immutable.Seq[TestRadioSource] = Nil): PlayerServerConfig =
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
      optUiContentResource = None,
      uiPath = PlayerServerConfig.DefaultUiPath,
      optCurrentConfig = None,
      optShutdownCommand = None)

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

