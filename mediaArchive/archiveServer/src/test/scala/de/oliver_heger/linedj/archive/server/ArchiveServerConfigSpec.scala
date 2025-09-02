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

package de.oliver_heger.linedj.archive.server

import org.apache.commons.configuration.{ConfigurationException, XMLConfiguration}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[ArchiveServerConfig]].
  */
class ArchiveServerConfigSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("ArchiveServerConfigSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "ArchiveServerConfig" should "successfully load the configuration" in :
    ArchiveServerConfig("test-archive-server-config.xml") map : config =>
      config.archiveConfigs.map(_.archiveName) should contain theSameElementsInOrderAs List("rock", "classic")
      config.archiveConfigs.map(_.rootPath.toString) should contain theSameElementsInOrderAs List(
        "/data/music/rock/media",
        "/data/music/classic/media"
      )
      config.serverPort should be(8085)

  it should "return a failed Future for a non-existing configuration file" in :
    val configFileName = "non-existing-config.xml"
    val futEx = recoverToExceptionIf[ConfigurationException]:
      ArchiveServerConfig(configFileName)

    futEx map : ex =>
      ex.getMessage should include(configFileName)

  it should "use default values for unspecified configuration properties" in :
    val config = new XMLConfiguration("test-archive-server-config.xml")
    config.clearProperty(ArchiveServerConfig.PropServerPort)

    val serverConfig = ArchiveServerConfig(config)

    serverConfig.serverPort should be(ArchiveServerConfig.DefaultServerPort)
