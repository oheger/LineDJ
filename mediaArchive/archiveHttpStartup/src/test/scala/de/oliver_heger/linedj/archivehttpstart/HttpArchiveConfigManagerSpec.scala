/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart

import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''HttpArchiveConfigManager''.
  */
class HttpArchiveConfigManagerSpec extends FlatSpec with Matchers {
  "An HttpArchiveConfigManager" should "handle an empty configuration" in {
    val config = new HierarchicalConfiguration

    val manager = HttpArchiveConfigManager(config)
    manager.archives should have size 0
  }

  it should "provide basic data about all managed archives" in {
    val Count = 8
    val config = StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, Count)
    val manager = HttpArchiveConfigManager(config)

    manager.archives should have size Count
    val names = (1 to Count) map StartupConfigTestHelper.archiveName
    manager.archives.keySet should contain theSameElementsInOrderAs names
    names.zipWithIndex.foreach { t =>
      val expUri = StartupConfigTestHelper.archiveUri(t._2 + 1)
      val archiveData = manager.archives(t._1)
      archiveData.config.archiveURI.toString() should be(expUri)
      archiveData.encrypted shouldBe false
      archiveData.protocol should be(HttpArchiveConfigManager.DefaultProtocolName)
    }
  }

  it should "filter out archives with an invalid configuration" in {
    val Count = 2
    val config = StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, Count)
    config.clearProperty(StartupConfigTestHelper.KeyArchives + "(0).archiveUri")
    val manager = HttpArchiveConfigManager(config)

    manager.archives.keySet should contain only StartupConfigTestHelper.archiveName(2)
  }

  it should "extract the realm from the configuration" in {
    val Count = 2
    val config = StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, Count)
    val manager = HttpArchiveConfigManager(config)

    (1 to Count) foreach { i =>
      val data = manager.archives(StartupConfigTestHelper.archiveName(i))
      data.realm should be(StartupConfigTestHelper.realmName(i))
    }
  }

  it should "filter out archives without a realm" in {
    val config = StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, 2)
    config.clearProperty(StartupConfigTestHelper.KeyArchives + "(1).realm")
    val manager = HttpArchiveConfigManager(config)

    manager.archives.keySet should contain only StartupConfigTestHelper.archiveName(1)
  }

  it should "generate unique short names for archives" in {
    val Count = 4
    val config = StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, Count)
    val expShortNames = (1 to Count) map StartupConfigTestHelper.shortName
    val manager = HttpArchiveConfigManager(config)

    manager.archives.values.map(_.shortName) should contain theSameElementsAs expShortNames
  }

  it should "handle an archive name shorter than the limit for short names" in {
    val ShortName = "Arc"
    val config = StartupConfigTestHelper.addArchiveToConfig(new HierarchicalConfiguration, 1)
    config.setProperty(StartupConfigTestHelper.KeyArchives + "." +
      HttpArchiveConfig.PropArchiveName, ShortName)
    val manager = HttpArchiveConfigManager(config)

    manager.archives(ShortName).shortName should be(ShortName)
  }

  it should "set a correct download config" in {
    val config = StartupConfigTestHelper.addArchiveToConfig(new HierarchicalConfiguration, 1)
    val manager = HttpArchiveConfigManager(config)

    val data = manager.archives(StartupConfigTestHelper.archiveName(1))
    data.config.downloadConfig
      .downloadChunkSize should be(StartupConfigTestHelper.DownloadChunkSize)
  }

  it should "allow selecting all archives for a specific realm" in {
    val Realm = StartupConfigTestHelper.realmName(1)
    val config = StartupConfigTestHelper.addArchiveToConfig(
      StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, 4), 42, Some(Realm))
    val expNames = List(StartupConfigTestHelper.archiveName(1),
      StartupConfigTestHelper.archiveName(42))
    val manager = HttpArchiveConfigManager(config)

    manager.archivesForRealm(Realm)
      .map(_.config.archiveName) should contain theSameElementsAs expNames
  }

  it should "evaluate the encrypted flag for archives" in {
    val Count = 4
    val config = StartupConfigTestHelper.addArchiveToConfig(
      StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, Count - 1),
      Count, Some("someRealm"), encrypted = true)
    val archiveName = StartupConfigTestHelper.archiveName(Count)
    val manager = HttpArchiveConfigManager(config)

    manager.archives(archiveName).encrypted shouldBe true
  }

  it should "evaluate the protocol property for archives" in {
    val Index = 42
    val TestProtocol = "test"
    val config = StartupConfigTestHelper.addArchiveToConfig(
      StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 1, 1),
      Index, Some("someRealm"), protocol = Some(TestProtocol))
    val archiveName = StartupConfigTestHelper.archiveName(Index)
    val manager = HttpArchiveConfigManager(config)

    manager.archives(archiveName).protocol should be(TestProtocol)
  }
}
