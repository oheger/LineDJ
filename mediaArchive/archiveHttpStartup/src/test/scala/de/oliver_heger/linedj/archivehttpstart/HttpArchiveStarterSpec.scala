/*
 * Copyright 2015-2017 The Developers Team.
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
import akka.http.scaladsl.model.Uri
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivehttp.HttpArchiveManagementActor
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.impl.download.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.util.Try

object HttpArchiveStarterSpec {
  /** The URL to the test HTTP archive. */
  private val ArchiveURL = "https://my.test.music.archive.la/content.json"

  /** Test user name. */
  private val UserName = "scott"

  /** Test password. */
  private val Password = "tiger"

  /** Test credentials for the archive. */
  private val ArchiveCredentials = UserCredentials(UserName, Password)

  /** The test path for temporary files. */
  private val PathTempDir = Paths get "tempDir"

  /** The prefix for keys in the configuration. */
  private val Prefix = "media.http."

  /** Test value for the download actor timeout. */
  private val DownloadTimeout = 1.hour

  /**
    * Creates a configuration object with the properties defining the test
    * HTTP archive.
    *
    * @return the configuration
    */
  private def createArchiveSourceConfig(): Configuration = {
    val props = new PropertiesConfiguration
    props.addProperty(Prefix + HttpArchiveConfig.PropArchiveUri, ArchiveURL)
    props.addProperty(Prefix + HttpArchiveConfig.PropDownloadMaxInactivity, 300)
    props.addProperty(Prefix + HttpArchiveConfig.PropDownloadBufferSize, 32768)
    props.addProperty(DownloadConfig.PropDownloadActorTimeout, DownloadTimeout.toSeconds)
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

  it should "create a correct download configuration" in {
    val helper = new StarterTestHelper

    val creationProps = helper.startArchiveAndCheckActors().actorCreationProps
    val downloadConfig = creationProps(HttpArchiveStarter.DownloadMonitoringActorName)
      .args.head.asInstanceOf[DownloadConfig]
    downloadConfig.downloadTimeout should be(DownloadTimeout)
  }

  it should "create a correct archive configuration" in {
    val helper = new StarterTestHelper

    val creationProps = helper.startArchiveAndCheckActors().actorCreationProps
    val managerProps = creationProps(HttpArchiveStarter.ManagementActorName)
    val config = managerProps.args.head.asInstanceOf[HttpArchiveConfig]
    config.archiveURI should be(Uri(ArchiveURL))
    config.credentials should be(ArchiveCredentials)
    val downloadConfig = creationProps(HttpArchiveStarter.DownloadMonitoringActorName)
      .args.head.asInstanceOf[DownloadConfig]
    config.downloadConfig should be theSameInstanceAs downloadConfig
  }

  it should "fail for an invalid archive configuration" in {
    val helper = new StarterTestHelper

    helper.startArchive(new PropertiesConfiguration).isFailure shouldBe true
  }

  it should "create a correct temp path generator" in {
    val helper = new StarterTestHelper

    val creationProps = helper.startArchiveAndCheckActors().actorCreationProps
    val pathGen = creationProps(HttpArchiveStarter.ManagementActorName)
      .args(1).asInstanceOf[TempPathGenerator]

    pathGen.rootPath should be(PathTempDir)
    java.time.Duration.between(pathGen.time, Instant.now()).toMillis should be < 3000L
  }

  it should "use the default temp dir for the path generator if not specified" in {
    val config = createArchiveSourceConfig()
    config clearProperty HttpArchiveStarter.PropTempDirectory
    val helper = new StarterTestHelper

    val creationProps = helper.startArchiveAndCheckActors(config).actorCreationProps
    val pathGen = creationProps(HttpArchiveStarter.ManagementActorName)
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
    val pathGen = creationProps(HttpArchiveStarter.ManagementActorName)
      .args(1).asInstanceOf[TempPathGenerator]

    helper.expectClearTempDirectory(pathGen)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class StarterTestHelper {
    /** Test probe for the archive management actor. */
    private val probeManagerActor = TestProbe()

    /** Test probe for the download monitoring actor. */
    private val probeMonitoringActor = TestProbe()

    /** Test probe for the remove file actor. */
    private val probeRemoveActor = TestProbe()

    /** Test actors for the union archive. */
    private val unionArchiveActors = createUnionArchiveActors()

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
    def startArchive(c: Configuration): Try[Map[String, ActorRef]] =
      starter.startup(unionArchiveActors, c, Prefix, ArchiveCredentials, actorFactory)

    /**
      * Invokes the test instance to start the archive and checks whether the
      * expected actors have been created.
      *
      * @param c the configuration to be used
      * @return this test helper
      */
    def startArchiveAndCheckActors(c: Configuration = createArchiveSourceConfig()):
    StarterTestHelper = {
      val actors = startArchive(c).get
      actors should have size 3
      actors(HttpArchiveStarter.ManagementActorName) should be(probeManagerActor.ref)
      actors(HttpArchiveStarter.DownloadMonitoringActorName) should be(probeMonitoringActor.ref)
      actors(HttpArchiveStarter.RemoveFileActorName) should be(probeRemoveActor.ref)
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
      val propsManager = actorCreationProps(HttpArchiveStarter.ManagementActorName)
      classOf[HttpArchiveManagementActor].isAssignableFrom(propsManager.actorClass()) shouldBe true
      classOf[ChildActorFactory].isAssignableFrom(propsManager.actorClass()) shouldBe true
      propsManager.args should have size 6
      propsManager.args(2) should be(unionArchiveActors.mediaManager)
      propsManager.args(3) should be(unionArchiveActors.metaDataManager)
      propsManager.args(4) should be(probeMonitoringActor.ref)
      propsManager.args(5) should be(probeRemoveActor.ref)

      val propsMonitor = actorCreationProps(HttpArchiveStarter.DownloadMonitoringActorName)
      classOf[SchedulerSupport].isAssignableFrom(propsMonitor.actorClass()) shouldBe true

      val propsRemove = actorCreationProps(HttpArchiveStarter.RemoveFileActorName)
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
    private def createActorFactory(): ActorFactory =
      new ActorFactory(system) {
        override def createActor(props: Props, name: String): ActorRef = {
          creationProps += name -> props
          name match {
            case HttpArchiveStarter.ManagementActorName =>
              probeManagerActor.ref
            case HttpArchiveStarter.RemoveFileActorName =>
              probeRemoveActor.ref
            case HttpArchiveStarter.DownloadMonitoringActorName =>
              probeMonitoringActor.ref
          }
        }
      }
  }

}
