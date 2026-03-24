/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.cloud.{ArchiveCryptConfig, CloudArchiveConfig}
import de.oliver_heger.linedj.archive.cloud.auth.{BasicAuthMethod, OAuthMethod}
import de.oliver_heger.linedj.archive.server.cloud.CloudArchiveServerConfigSpec.loadTestConfig
import org.apache.commons.configuration2.Configuration
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.apache.commons.configuration2.ex.ConfigurationException
import org.apache.pekko.util.Timeout
import org.scalatest.Inspectors.forEvery
import org.scalatest.TryValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt

object CloudArchiveServerConfigSpec:
  /** The name of the test configuration file. */
  private val TestConfigName = "cloud-archive-server-config.xml"

  /** The helper object for loading configuration files. */
  private val configs = new Configurations

  /**
    * Loads the test configuration file from the test resources.
    *
    * @return the loaded configuration
    */
  private def loadTestConfig(): Configuration = configs.xml(TestConfigName)
end CloudArchiveServerConfigSpec

/**
  * Test class for [[CloudArchiveServerConfig]].
  */
class CloudArchiveServerConfigSpec extends AnyFlatSpec, Matchers, TryValues:
  /**
    * Parses the test configuration and checks whether this is successful.
    *
    * @return the extracted [[CloudArchiveServerConfig]]
    */
  private def parseTestConfig(): CloudArchiveServerConfig =
    CloudArchiveServerConfig.parseConfig(loadTestConfig()).success.value

  "parseConfig()" should "parse the mandatory path properties" in :
    val config = parseTestConfig()

    config.credentialsDirectory should be(Paths.get("/data/credentials"))
    config.cacheDirectory should be(Paths.get("/tmp/cloud-archives/cache"))

  it should "return a failure if the credential path is not set" in :
    val config = loadTestConfig()
    config.clearProperty("media.cloudArchives.credentialsDirectory")

    val result = CloudArchiveServerConfig.parseConfig(config)

    result.failure.exception shouldBe a[ConfigurationException]
    result.failure.exception.getMessage should include("credentialsDirectory")

  it should "return a failure if the cache path is not set" in :
    val config = loadTestConfig()
    config.clearProperty("media.cloudArchives.cacheDirectory")

    val result = CloudArchiveServerConfig.parseConfig(config)

    result.failure.exception shouldBe a[ConfigurationException]
    result.failure.exception.getMessage should include("cacheDirectory")

  it should "parse the mandatory properties of cloud archives" in :
    val config = parseTestConfig()

    config.archives should have size 4

    val rockArchive = config.archives.head
    import rockArchive.*
    archiveBaseUri should be("https://music.example.com/rock")
    archiveName should be("Rock in the cloud")
    authMethod should be(BasicAuthMethod("rock-realm"))
    fileSystem should be("OneDrive")

  it should "handle undefined optional properties" in :
    val config = parseTestConfig()

    val defaultArchive = config.archives(1)
    import defaultArchive.*
    optContentPath shouldBe empty
    optCryptConfig shouldBe empty
    optMaxContentSize shouldBe empty
    optMediaPath shouldBe empty
    optMetadataPath shouldBe empty
    optParallelism shouldBe empty
    optRequestQueueSize shouldBe empty
    optTimeout shouldBe empty

  it should "handle defined optional properties" in :
    val config = parseTestConfig()

    val rockArchive = config.archives.head
    import rockArchive.*
    optContentPath should be(Some("meta/toc.json"))
    optMaxContentSize should be(Some(128000))
    optMediaPath should be(Some("meta/media"))
    optMetadataPath should be(Some("meta/mdt"))
    optParallelism should be(Some(3))
    optRequestQueueSize should be(Some(19))
    optTimeout should be(Some(Timeout(2.minutes)))

  it should "parse auth methods correctly" in :
    val config = parseTestConfig()

    config.archives(1).authMethod should be(OAuthMethod("default-realm"))
    config.archives(2).authMethod should be(BasicAuthMethod("test"))

  it should "return a failure if a mandatory property of an archive is missing" in :
    val properties = List("name", "baseUri", "authMethod", "authRealm", "fileSystem")
    forEvery(properties): property =>
      val config = loadTestConfig()
      config.clearProperty("media.cloudArchives.cloudArchive(1)." + property)

      val result = CloudArchiveServerConfig.parseConfig(config)

      result.failure.exception shouldBe a[ConfigurationException]
      result.failure.exception.getMessage should include(property)

  it should "return a failure for an invalid auth method" in :
    val unsupportedAuthMethod = "unsupportedAuthMethod"
    val config = loadTestConfig()
    config.setProperty("media.cloudArchives.cloudArchive(0).authMethod", unsupportedAuthMethod)

    val result = CloudArchiveServerConfig.parseConfig(config)

    result.failure.exception shouldBe a[ConfigurationException]
    result.failure.exception.getMessage should include(unsupportedAuthMethod)

  it should "return a failure for an invalid file system" in :
    val unknownFileSystem = "nonExistingFileSystem"
    val config = loadTestConfig()
    config.setProperty("media.cloudArchives.cloudArchive(0).fileSystem", unknownFileSystem)

    val result = CloudArchiveServerConfig.parseConfig(config)

    result.failure.exception shouldBe a[ConfigurationException]
    result.failure.exception.getMessage should include(unknownFileSystem)

  it should "provide a crypt config if the corresponding options are set" in :
    val config = parseTestConfig()

    config.archives.head.optCryptConfig should be(Some(ArchiveCryptConfig(cryptCacheSize = 111, cryptChunkSize = 127)))

  it should "provide a crypt config with default options if the encrypted flag is set" in :
    val config = parseTestConfig()

    config.archives(2).optCryptConfig should be(Some(CloudArchiveConfig.DefaultCryptConfig))

  it should "ignore crypt settings if the encrypted flag is false" in :
    val config = parseTestConfig()

    config.archives(3).optCryptConfig shouldBe empty
