/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.AuthConfig
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.archive.cloud.auth.{BasicAuthMethod, OAuthMethod}
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory
import de.oliver_heger.linedj.archive.cloud.{CloudArchiveConfig, CloudFileDownloader, CloudFileDownloaderFactory}
import de.oliver_heger.linedj.archivehttp.HttpArchiveManagementActor
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.temp.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.actors.{ActorFactory, ChildActorFactory, SchedulerSupport}
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, typed}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.*
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.concurrent.Future
import scala.language.existentials

object HttpArchiveStarterSpec:
  /** Test username. */
  private val UserName = "scott"

  /** Test password. */
  private val Password = "tiger"

  /** Test credentials for the archive. */
  private val ArchiveCredentials = UserCredentials(UserName, Secret(Password))

  /** The test path for temporary files. */
  private val PathTempDir = Paths get "tempDir"

  /** A default numeric index to be passed to the starter. */
  private val ArcIndex = 28

  /** A key for testing the handling of encrypted archives. */
  private val CryptKey = Secret("keyForMyArchive")

  /**
    * Creates a configuration object with the properties defining the test
    * HTTP archive.
    *
    * @return the configuration
    */
  private def createArchiveSourceConfig(): Configuration =
    val props = StartupConfigTestHelper.addArchiveToConfig(new HierarchicalConfiguration(), 1)
    props.addProperty(HttpArchiveStarter.PropTempDirectory, PathTempDir.toString)
    props

  /**
    * Checks that no message has been sent to the specified test probe.
    *
    * @param probe the test probe
    */
  private def expectNoMessageToProbe(probe: TestProbe): Unit =
    val Ping = new Object
    probe.ref ! Ping
    probe.expectMsg(Ping)

/**
  * Test class for ''HttpArchiveStarter''.
  */
class HttpArchiveStarterSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with OptionValues with AsyncTestHelper:
  def this() = this(ActorSystem("HttpArchiveStarterSpec"))

  import HttpArchiveStarterSpec.*

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Starts the HTTP archive and obtains the temp path generator from the
    * arguments passed to the manager actor.
    *
    * @param helper the test helper
    * @return the ''TempPathGenerator''
    */
  private def fetchPathGenerator(helper: StarterTestHelper): TempPathGenerator =
    val creationProps = helper.startArchiveAndCheckActors().actorCreationProps
    creationProps(helper.actorName(HttpArchiveStarter.ManagementActorName))
      .args(2).asInstanceOf[TempPathGenerator]

  "A HttpArchiveStarter" should "create correct actors for the archive" in :
    val helper = new StarterTestHelper

    helper.startArchiveAndCheckActors()
      .checkActorProps()

  it should "create correct actors for an encrypted archive" in :
    val helper = new StarterTestHelper(encryptedArchive = true)

    helper.startArchiveAndCheckActors()
      .checkActorProps()

  it should "return a failure if the downloader cannot be constructed" in :
    val exception = new IOException("Invalid archive URI")
    val helper = new StarterTestHelper(optExDownloader = Some(exception))

    expectFailedFuture[IOException](helper.invokeStartup())

  it should "generate unique actor names based on the numeric index" in :
    val OtherIndex = ArcIndex + 1
    val helper = new StarterTestHelper(OtherIndex)

    val actors = helper.startArchive(createArchiveSourceConfig())
    actors.actors.contains(helper.actorName(HttpArchiveStarter.ManagementActorName,
      OtherIndex)) shouldBe true

  it should "create a correct downloader for an archive" in :
    val helper = new StarterTestHelper

    val archiveConfig = helper.startArchiveAndCheckActors()
      .verifyDownloaderCreation()

    archiveConfig.optCryptConfig shouldBe empty

  it should "create a correct downloader for an encrypted archive" in :
    val helper = new StarterTestHelper(encryptedArchive = true)

    val archiveConfig = helper.startArchiveAndCheckActors()
      .verifyDownloaderCreation()

    archiveConfig.optCryptConfig.value should be(CloudArchiveConfig.DefaultCryptConfig)

  it should "create a correct temp path generator" in :
    val helper = new StarterTestHelper
    val pathGen = fetchPathGenerator(helper)

    pathGen.rootPath should be(PathTempDir)
    java.time.Duration.between(pathGen.time, Instant.now()).toMillis should be < 3000L

  it should "use the default temp dir for the path generator if not specified" in :
    val config = createArchiveSourceConfig()
    config clearProperty HttpArchiveStarter.PropTempDirectory
    val helper = new StarterTestHelper

    val creationProps = helper.startArchiveAndCheckActors(config).actorCreationProps
    val pathGen = creationProps(helper.actorName(HttpArchiveStarter.ManagementActorName))
      .args(2).asInstanceOf[TempPathGenerator]
    val tempFile = Files.createTempFile("Test", ".tmp")
    try
      pathGen.rootPath should be(tempFile.getParent)
    finally
      if Files exists tempFile then
        Files delete tempFile

  it should "start a scan after creating the archive actors" in :
    val helper = new StarterTestHelper

    helper.startArchiveAndCheckActors()
      .expectScanStarted()

  it should "start a clear temp files operation" in :
    val helper = new StarterTestHelper
    val pathGen = fetchPathGenerator(helper)

    helper.expectClearTempDirectory(pathGen)

  it should "not start a clear temp files operation if disabled" in :
    val helper = new StarterTestHelper(clearTemp = false)

    helper.startArchiveAndCheckActors()
      .expectNoClearTempDirectory()

  "toAuthMethod" should "correctly convert a BasicAuthRealm" in :
    val realm = BasicAuthRealm("testBasicAuthRealm")

    val method = HttpArchiveStarter.toAuthMethod(realm)

    method should be(BasicAuthMethod(realm.name))

  it should "correctly convert an OAuthRealm" in :
    val realm = OAuthRealm("testOAuthRealm")

    val method = HttpArchiveStarter.toAuthMethod(realm)

    method should be(OAuthMethod(realm.name))

  /**
    * A test helper class managing a test instance and its dependencies.
    *
    * @param index            the numeric index to be passed to the starter
    * @param clearTemp        flag whether the temp directory is to be cleared
    * @param encryptedArchive flag whether the archive should be encrypted
    * @param optRealm         an optional realm for the archive
    * @param optExDownloader  optional exception thrown by the downloader
    *                         factory
    */
  private class StarterTestHelper(index: Int = ArcIndex, clearTemp: Boolean = true,
                                  encryptedArchive: Boolean = false,
                                  optRealm: Option[ArchiveRealm] = None,
                                  optExDownloader: Option[Throwable] = None):
    /** Test probe for the archive management actor. */
    private val probeManagerActor = TestProbe()

    /** Test probe for the download monitoring actor. */
    private val probeMonitoringActor = TestProbe()

    /** Test probe for the remove file actor. */
    private val probeRemoveActor = TestProbe()

    /** Test actors for the union archive. */
    private val unionArchiveActors = createUnionArchiveActors()

    /** The default test configuration. */
    private val sourceConfig = createArchiveSourceConfig()

    /** Mock for the file system factory to be used. */
    private val fileSystemFactory = mock[CloudArchiveFileSystemFactory]

    /** The data object for the archive to be started. */
    private val archiveData = createArchiveData()

    /** The factory for creating child actors. */
    private val actorFactory = createActorFactory()

    /** Mock for the configuration for authentication. */
    private val authConfig = mock[AuthConfig]

    /**
      * The mock for the downloader. This mock is also returned by the mock for
      * the downloader factory.
      */
    private val fileDownloader = mock[CloudFileDownloader]

    /** The mock for the factory to create a downloader. */
    private val downloaderFactory = createFileDownloaderFactory()

    /** The mock for the credential provider. */
    private val credentialsProvider = mock[CredentialsProvider]

    /** The object to be tested. */
    private val starter = new HttpArchiveStarter(downloaderFactory, credentialsProvider)

    /** A map that stores information about actor creations. */
    private var creationProps = Map.empty[String, Props]

    import system.dispatcher

    /**
      * Calls the startup method of the test instance and returns the
      * resulting ''Future''.
      *
      * @param c the configuration to be used
      * @return the ''Future'' with the result
      */
    def invokeStartup(c: Configuration = sourceConfig): Future[HttpArchiveStarter.ArchiveResources] =
      starter.startup(
        unionArchiveActors,
        archiveData,
        c,
        fileSystemFactory,
        ArchiveCredentials,
        cryptKeyParam,
        actorFactory,
        index,
        clearTemp
      )

    /**
      * Invokes the test instance with the passed in configuration and
      * returns the result.
      *
      * @param c the configuration to be used
      * @return the result of the starter
      */
    def startArchive(c: Configuration = sourceConfig): HttpArchiveStarter.ArchiveResources =
      futureResult(invokeStartup(c))

    /**
      * Invokes the test instance to start the archive and checks whether the
      * expected actors have been created.
      *
      * @param c the configuration to be used
      * @return this test helper
      */
    def startArchiveAndCheckActors(c: Configuration = sourceConfig): StarterTestHelper =
      val resources = startArchive(c)
      resources.actors should have size 3
      resources.actors(actorName(HttpArchiveStarter.ManagementActorName)) should be(probeManagerActor.ref)
      resources.actors(actorName(HttpArchiveStarter.
        DownloadMonitoringActorName)) should be(probeMonitoringActor.ref)
      resources.actors(actorName(HttpArchiveStarter.RemoveFileActorName)) should be(probeRemoveActor.ref)
      resources.httpActorName should be(actorName(HttpArchiveStarter.HttpRequestActorName))
      resources.downloader should be(fileDownloader)
      this

    /**
      * Returns a map with properties for the actors created by the starter
      * object. Keys are actor names.
      *
      * @return the map with actor creation properties
      */
    def actorCreationProps: Map[String, Props] = creationProps

    /**
      * Checks the properties of the actors created by the starter.
      *
      * @return this test helper
      */
    def checkActorProps(): StarterTestHelper =
      val expConfig = expectedArchiveConfig()
      val propsManager = actorCreationProps(actorName(HttpArchiveStarter.ManagementActorName))
      classOf[HttpArchiveManagementActor].isAssignableFrom(propsManager.actorClass()) shouldBe true
      classOf[ChildActorFactory].isAssignableFrom(propsManager.actorClass()) shouldBe true
      propsManager.args should have size 7
      propsManager.args(1) should be(expConfig)
      propsManager.args(3) should be(unionArchiveActors.mediaManager)
      propsManager.args(4) should be(unionArchiveActors.metadataManager)
      propsManager.args(5) should be(probeMonitoringActor.ref)
      propsManager.args(6) should be(probeRemoveActor.ref)

      val propsMonitor = actorCreationProps(actorName(
        HttpArchiveStarter.DownloadMonitoringActorName))
      classOf[SchedulerSupport].isAssignableFrom(propsMonitor.actorClass()) shouldBe true
      propsMonitor.args.head should be(archiveData.config.archiveConfig.downloadConfig)

      val propsRemove = actorCreationProps(actorName(HttpArchiveStarter.RemoveFileActorName))
      propsRemove should be(RemoveTempFilesActor(ClientApplication.BlockingDispatcherName))

      this

    /**
      * Expects that the archive management actor was sent a message to start a
      * scan for media files.
      *
      * @return this test helper
      */
    def expectScanStarted(): StarterTestHelper =
      probeManagerActor.expectMsg(ScanAllMedia)
      this

    /**
      * Expects that the remove actor was sent a message to clear the temp
      * directory.
      *
      * @param pathGenerator the expected path generator
      * @return this test helper
      */
    def expectClearTempDirectory(pathGenerator: TempPathGenerator): StarterTestHelper =
      probeRemoveActor.expectMsg(RemoveTempFilesActor.ClearTempDirectory(PathTempDir,
        pathGenerator))
      this

    /**
      * Checks that no message to clear the temp directory has been sent to the
      * remove actor.
      *
      * @return this test helper
      */
    def expectNoClearTempDirectory(): StarterTestHelper =
      expectNoMessageToProbe(probeRemoveActor)
      this

    /**
      * Verifies that the factory to create the downloader was called
      * correctly. Returns the archive configuration passed to this factory.
      * Basic properties of this configuration have already been checked.
      *
      * @return the archive configuration to create the downloader
      */
    def verifyDownloaderCreation(): CloudArchiveConfig =
      val inOrder = Mockito.inOrder(downloaderFactory, credentialsProvider)
      val captArchiveConfig = ArgumentCaptor.forClass(classOf[CloudArchiveConfig])
      inOrder.verify(credentialsProvider).passCredentials(archiveData.realm, ArchiveCredentials)
      if encryptedArchive then
        inOrder.verify(credentialsProvider).passEncryptionKey(archiveData.config.archiveConfig.archiveName, CryptKey)
      else
        inOrder.verify(credentialsProvider, never()).passEncryptionKey(anyString(), any())
      inOrder.verify(downloaderFactory).createDownloader(captArchiveConfig.capture())

      val archiveConfig = captArchiveConfig.getValue
      archiveConfig.archiveBaseUri should be(archiveData.config.archiveConfig.archiveBaseUri)
      archiveConfig.archiveName should be(archiveData.config.archiveConfig.archiveName)
      archiveConfig.fileSystemFactory should be(fileSystemFactory)
      archiveConfig.authMethod should be(BasicAuthMethod(archiveData.realm.name))
      archiveConfig

    /**
      * Generates a full actor name based on its suffix.
      *
      * @param n the name suffix for the actor
      * @return the full actor name
      */
    def actorName(n: String, index: Int = ArcIndex): String =
      archiveData.shortName + index + '_' + n

    /**
      * Creates the test archive data from the configuration passed to this
      * object. If a realm has been provided, it is set.
      *
      * @return the test archive data
      */
    private def createArchiveData(): HttpArchiveData =
      val manager = HttpArchiveConfigManager(sourceConfig)
      val data = manager.archives(StartupConfigTestHelper.archiveName(1))
      optRealm map (realm => data.copy(realm = realm)) getOrElse data

    /**
      * Returns the expected final configuration that should be passed to the
      * actors created for the archive.
      *
      * @return the expected archive configuration
      */
    private def expectedArchiveConfig(): HttpArchiveConfig =
      archiveData.config.archiveConfig.copy(downloader = fileDownloader)

    /**
      * Creates an object with test actors for the union archive.
      *
      * @return the union archive actors
      */
    private def createUnionArchiveActors(): MediaFacadeActors =
      MediaFacadeActors(TestProbe().ref, TestProbe().ref)

    /**
      * Determines the parameter for the decryption key to be used based on
      * the flag whether the archive should be encrypted.
      *
      * @return the parameter for the decryption key
      */
    private def cryptKeyParam: Option[Secret] =
      if encryptedArchive then Some(CryptKey) else None

    /**
      * Creates an actor factory object that returns the predefined test
      * probes.
      *
      * @return the actor factory
      */
    private def createActorFactory(): ActorFactory =
      val probes = Map(
        actorName(HttpArchiveStarter.ManagementActorName, index) -> probeManagerActor.ref,
        actorName(HttpArchiveStarter.RemoveFileActorName, index) -> probeRemoveActor.ref,
        actorName(HttpArchiveStarter.DownloadMonitoringActorName, index)
          -> probeMonitoringActor.ref)
      new ActorFactory:
        override def actorSystem: ActorSystem = system

        override def createClassicActor(props: Props, name: String, stopper: Option[Any]): ActorRef =
          creationProps += name -> props
          probes(name)

        override def createTypedActor[T](behavior: Behavior[T],
                                         name: String,
                                         props: typed.Props,
                                         optStopCommand: Option[T]): typed.ActorRef[T] =
          throw new UnsupportedOperationException("Unexpected invocation.")

    /**
      * Creates the mock for the downloader factory. The mock is prepared to
      * handle an invocation to create the mock downloader.
      *
      * @return the mock downloader factory
      */
    private def createFileDownloaderFactory(): CloudFileDownloaderFactory =
      val factory = mock[CloudFileDownloaderFactory]
      val result = optExDownloader.fold(Future.successful(fileDownloader))(ex => Future.failed(ex))
      when(factory.createDownloader(any())).thenReturn(result)
      factory
