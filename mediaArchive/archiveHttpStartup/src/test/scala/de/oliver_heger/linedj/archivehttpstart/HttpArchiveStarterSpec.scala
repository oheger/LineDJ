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

import java.nio.file.{Files, Paths}
import java.security.Key
import java.time.Instant

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig.AuthConfigureFunc
import de.oliver_heger.linedj.archivehttp.{HttpArchiveManagementActor, HttpAuthFactory}
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.crypt.{AESKeyGenerator, Secret}
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.archivehttp.temp.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object HttpArchiveStarterSpec {
  /** Test user name. */
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
  private val CryptKey = new AESKeyGenerator().generateKey("keyForMyArchive")

  /**
    * Creates a configuration object with the properties defining the test
    * HTTP archive.
    *
    * @return the configuration
    */
  private def createArchiveSourceConfig(): Configuration = {
    val props = StartupConfigTestHelper.addArchiveToConfig(new HierarchicalConfiguration(), 1)
    props.addProperty(HttpArchiveStarter.PropTempDirectory, PathTempDir.toString)
    props
  }

  /**
    * Checks that no message has been sent to the specified test probe.
    *
    * @param probe the test probe
    */
  private def expectNoMessageToProbe(probe: TestProbe): Unit = {
    val Ping = new Object
    probe.ref ! Ping
    probe.expectMsg(Ping)
  }
}

/**
  * Test class for ''HttpArchiveStarter''.
  */
class HttpArchiveStarterSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper {
  def this() = this(ActorSystem("HttpArchiveStarterSpec"))

  import HttpArchiveStarterSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Starts the HTTP archive and obtains the temp path generator from the
    * arguments passed to the manager actor.
    *
    * @param helper the test helper
    * @return the ''TempPathGenerator''
    */
  private def fetchPathGenerator(helper: StarterTestHelper): TempPathGenerator = {
    val creationProps = helper.startArchiveAndCheckActors().actorCreationProps
    creationProps(helper.actorName(HttpArchiveStarter.ManagementActorName))
      .args(2).asInstanceOf[TempPathGenerator]
  }

  "A HttpArchiveStarter" should "use a correct default HttpAuthFactory" in {
    val starter = new HttpArchiveStarter

    starter.authFactory should be(HttpArchiveManagementActor)
  }

  it should "create correct actors for the archive" in {
    val helper = new StarterTestHelper

    helper.startArchiveAndCheckActors()
      .checkActorProps()
  }

  it should "create correct actors for an encrypted archive" in {
    val helper = new StarterTestHelper(encryptedArchive = true)

    helper.startArchiveAndCheckActors()
      .checkActorProps()
  }

  it should "generate unique actor names based on the numeric index" in {
    val OtherIndex = ArcIndex + 1
    val helper = new StarterTestHelper(OtherIndex)

    val actors = helper.startArchive(createArchiveSourceConfig())
    actors.contains(helper.actorName(HttpArchiveStarter.ManagementActorName,
      OtherIndex)) shouldBe true
  }

  it should "create a correct temp path generator" in {
    val helper = new StarterTestHelper
    val pathGen = fetchPathGenerator(helper)

    pathGen.rootPath should be(PathTempDir)
    java.time.Duration.between(pathGen.time, Instant.now()).toMillis should be < 3000L
  }

  it should "use the default temp dir for the path generator if not specified" in {
    val config = createArchiveSourceConfig()
    config clearProperty HttpArchiveStarter.PropTempDirectory
    val helper = new StarterTestHelper

    val creationProps = helper.startArchiveAndCheckActors(config).actorCreationProps
    val pathGen = creationProps(helper.actorName(HttpArchiveStarter.ManagementActorName))
      .args(2).asInstanceOf[TempPathGenerator]
    val tempFile = Files.createTempFile("Test", ".tmp")
    try {
      pathGen.rootPath should be(tempFile.getParent)
    } finally {
      if (Files exists tempFile) {
        Files delete tempFile
      }
    }
  }

  it should "start a scan after creating the archive actors" in {
    val helper = new StarterTestHelper

    helper.startArchiveAndCheckActors()
      .expectScanStarted()
  }

  it should "start a clear temp files operation" in {
    val helper = new StarterTestHelper
    val pathGen = fetchPathGenerator(helper)

    helper.expectClearTempDirectory(pathGen)
  }

  it should "not start a clear temp files operation if disabled" in {
    val helper = new StarterTestHelper(clearTemp = false)

    helper.startArchiveAndCheckActors()
      .expectNoClearTempDirectory()
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    *
    * @param index            the numeric index to be passed to the starter
    * @param clearTemp        flag whether the temp directory is to be cleared
    * @param encryptedArchive flag whether the archive should be encrypted
    */
  private class StarterTestHelper(index: Int = ArcIndex, clearTemp: Boolean = true,
                                  encryptedArchive: Boolean = false) {
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

    /** Mock for the protocol to be used. */
    private val protocol = mock[HttpArchiveProtocol]

    /** The data object for the archive to be started. */
    private val archiveData = createArchiveData()

    /** The factory for creating child actors. */
    private val actorFactory = createActorFactory()

    /** Mock for the function to configure authentication. */
    private val authConfigureFunc = mock[AuthConfigureFunc]

    /** Mock for the factory to create authentication functions. */
    private val authFactory = createAuthFactory()

    /** The object to be tested. */
    private val starter = new HttpArchiveStarter(authFactory)

    /** A map that stores information about actor creations. */
    private var creationProps = Map.empty[String, Props]

    /** An object to materialize streams in implicit scope. */
    private implicit val mat: ActorMaterializer = ActorMaterializer()

    import system.dispatcher

    /**
      * Invokes the test instance with the passed in configuration and
      * returns the result.
      *
      * @param c the configuration to be used
      * @return the result of the starter
      */
    def startArchive(c: Configuration): Map[String, ActorRef] =
      futureResult(starter.startup(unionArchiveActors, archiveData, c, protocol, ArchiveCredentials,
        cryptKeyParam, actorFactory, index, clearTemp))

    /**
      * Invokes the test instance to start the archive and checks whether the
      * expected actors have been created.
      *
      * @param c the configuration to be used
      * @return this test helper
      */
    def startArchiveAndCheckActors(c: Configuration = sourceConfig): StarterTestHelper = {
      val actors = startArchive(c)
      actors should have size 3
      actors(actorName(HttpArchiveStarter.ManagementActorName)) should be(probeManagerActor.ref)
      actors(actorName(HttpArchiveStarter.
        DownloadMonitoringActorName)) should be(probeMonitoringActor.ref)
      actors(actorName(HttpArchiveStarter.RemoveFileActorName)) should be(probeRemoveActor.ref)
      this
    }

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
    def checkActorProps(): StarterTestHelper = {
      val expConfig = expectedArchiveConfig()
      val propsManager = actorCreationProps(actorName(HttpArchiveStarter.ManagementActorName))
      classOf[HttpArchiveManagementActor].isAssignableFrom(propsManager.actorClass()) shouldBe true
      classOf[ChildActorFactory].isAssignableFrom(propsManager.actorClass()) shouldBe true
      propsManager.args should have size 8
      propsManager.args(1) should be(expConfig)
      propsManager.args(3) should be(unionArchiveActors.mediaManager)
      propsManager.args(4) should be(unionArchiveActors.metaDataManager)
      propsManager.args(5) should be(probeMonitoringActor.ref)
      propsManager.args(6) should be(probeRemoveActor.ref)
      propsManager.args(7) should be(cryptKeyParam)

      val propsMonitor = actorCreationProps(actorName(
        HttpArchiveStarter.DownloadMonitoringActorName))
      classOf[SchedulerSupport].isAssignableFrom(propsMonitor.actorClass()) shouldBe true
      propsMonitor.args.head should be(archiveData.config.downloadConfig)

      val propsRemove = actorCreationProps(actorName(HttpArchiveStarter.RemoveFileActorName))
      propsRemove should be(RemoveTempFilesActor(ClientApplication.BlockingDispatcherName))

      this
    }

    /**
      * Expects that the archive management actor was sent a message to start a
      * scan for media files.
      *
      * @return this test helper
      */
    def expectScanStarted(): StarterTestHelper = {
      probeManagerActor.expectMsg(ScanAllMedia)
      this
    }

    /**
      * Expects that the remove actor was sent a message to clear the temp
      * directory.
      *
      * @param pathGenerator the expected path generator
      * @return this test helper
      */
    def expectClearTempDirectory(pathGenerator: TempPathGenerator): StarterTestHelper = {
      probeRemoveActor.expectMsg(RemoveTempFilesActor.ClearTempDirectory(PathTempDir,
        pathGenerator))
      this
    }

    /**
      * Checks that no message to clear the temp directory has been sent to the
      * remove actor.
      *
      * @return this test helper
      */
    def expectNoClearTempDirectory(): StarterTestHelper = {
      expectNoMessageToProbe(probeRemoveActor)
      this
    }

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
      * object.
      *
      * @return the test archive data
      */
    private def createArchiveData(): HttpArchiveData = {
      val manager = HttpArchiveConfigManager(sourceConfig)
      manager.archives(StartupConfigTestHelper.archiveName(1))
    }

    /**
      * Returns the expected final configuration that should be passed to the
      * actors created for the archive.
      *
      * @return the expected archive configuration
      */
    private def expectedArchiveConfig(): HttpArchiveConfig =
      archiveData.config.copy(protocol = protocol, authFunc = authConfigureFunc)

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
    private def cryptKeyParam: Option[Key] =
      if (encryptedArchive) Some(CryptKey) else None

    /**
      * Creates an actor factory object that returns the predefined test
      * probes.
      *
      * @return the actor factory
      */
    private def createActorFactory(): ActorFactory = {
      val probes = Map(
        actorName(HttpArchiveStarter.ManagementActorName, index) -> probeManagerActor.ref,
        actorName(HttpArchiveStarter.RemoveFileActorName, index) -> probeRemoveActor.ref,
        actorName(HttpArchiveStarter.DownloadMonitoringActorName, index)
          -> probeMonitoringActor.ref)
      new ActorFactory(system) {
        override def createActor(props: Props, name: String): ActorRef = {
          creationProps += name -> props
          probes(name)
        }
      }
    }

    /**
      * Creates the mock for the auth factory. It is prepared for a basic auth
      * invocation.
      *
      * @return the mock for the auth factory
      */
    private def createAuthFactory(): HttpAuthFactory = {
      val factory = mock[HttpAuthFactory]
      when(factory.basicAuthConfigureFunc(ArchiveCredentials)).thenReturn(Future.successful(authConfigureFunc))
      factory
    }
  }

}
