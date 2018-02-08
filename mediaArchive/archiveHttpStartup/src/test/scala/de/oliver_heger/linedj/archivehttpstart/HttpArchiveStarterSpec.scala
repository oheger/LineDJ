/*
 * Copyright 2015-2018 The Developers Team.
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
import java.time.Instant

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.archivehttp.HttpArchiveManagementActor
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.impl.download.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object HttpArchiveStarterSpec {
  /** Test user name. */
  private val UserName = "scott"

  /** Test password. */
  private val Password = "tiger"

  /** Test credentials for the archive. */
  private val ArchiveCredentials = UserCredentials(UserName, Password)

  /** The test path for temporary files. */
  private val PathTempDir = Paths get "tempDir"

  /** A default numeric index to be passed to the starter. */
  private val ArcIndex = 28

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
}

/**
  * Test class for ''HttpArchiveStarter''.
  */
class HttpArchiveStarterSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("HttpArchiveStarterSpec"))

  import HttpArchiveStarterSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A HttpArchiveStarter" should "create correct actors for the archive" in {
    val helper = new StarterTestHelper

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

    val creationProps = helper.startArchiveAndCheckActors().actorCreationProps
    val pathGen = creationProps(helper.actorName(HttpArchiveStarter.ManagementActorName))
      .args(1).asInstanceOf[TempPathGenerator]

    pathGen.rootPath should be(PathTempDir)
    java.time.Duration.between(pathGen.time, Instant.now()).toMillis should be < 3000L
  }

  it should "use the default temp dir for the path generator if not specified" in {
    val config = createArchiveSourceConfig()
    config clearProperty HttpArchiveStarter.PropTempDirectory
    val helper = new StarterTestHelper

    val creationProps = helper.startArchiveAndCheckActors(config).actorCreationProps
    val pathGen = creationProps(helper.actorName(HttpArchiveStarter.ManagementActorName))
      .args(1).asInstanceOf[TempPathGenerator]
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
    val creationProps = helper.startArchiveAndCheckActors().actorCreationProps
    val pathGen = creationProps(helper.actorName(HttpArchiveStarter.ManagementActorName))
      .args(1).asInstanceOf[TempPathGenerator]

    helper.expectClearTempDirectory(pathGen)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    *
    * @param index the numeric index to be passed to the starter
    */
  private class StarterTestHelper(index: Int = ArcIndex) {
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

    /** The data object for the archive to be started. */
    private val archiveData = createArchiveData()

    /** The factory for creating child actors. */
    private val actorFactory = createActorFactory()

    /** The object to be tested. */
    private val starter = new HttpArchiveStarter

    /** A map that stores information about actor creations. */
    private var creationProps = Map.empty[String, Props]

    /**
      * Invokes the test instance with the passed in configuration and
      * returns the result.
      *
      * @param c the configuration to be used
      * @return the result of the starter
      */
    def startArchive(c: Configuration): Map[String, ActorRef] =
      starter.startup(unionArchiveActors, archiveData, c, ArchiveCredentials, actorFactory, index)

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
      val propsManager = actorCreationProps(actorName(HttpArchiveStarter.ManagementActorName))
      classOf[HttpArchiveManagementActor].isAssignableFrom(propsManager.actorClass()) shouldBe true
      classOf[ChildActorFactory].isAssignableFrom(propsManager.actorClass()) shouldBe true
      propsManager.args should have size 6
      propsManager.args.head should be(archiveData.config)
      propsManager.args(2) should be(unionArchiveActors.mediaManager)
      propsManager.args(3) should be(unionArchiveActors.metaDataManager)
      propsManager.args(4) should be(probeMonitoringActor.ref)
      propsManager.args(5) should be(probeRemoveActor.ref)

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
      val data = manager.archives(StartupConfigTestHelper.archiveName(1))
      data.copy(config = data.config.copy(credentials = ArchiveCredentials))
    }

    /**
      * Creates an object with test actors for the union archive.
      *
      * @return the union archive actors
      */
    private def createUnionArchiveActors(): MediaFacadeActors =
      MediaFacadeActors(TestProbe().ref, TestProbe().ref)

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
  }

}
