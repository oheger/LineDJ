/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.archivelocalstart

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.group.ArchiveGroupActor
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import org.apache.commons.configuration.HierarchicalConfiguration
import org.mockito.Matchers.{any, anyString}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object LocalArchiveStartupSpec {
  /** The configurations for the media archives to start. */
  private val ArchiveConfigs = MediaArchiveConfig(createArchiveConfiguration())

  /**
    * Creates a configuration object that can be used to initialize the config
    * for the media archive.
    *
    * @return the configuration
    */
  private def createArchiveConfiguration(): HierarchicalConfiguration = {
    val config = new HierarchicalConfiguration
    config.addProperty("media.localArchives.readerTimeout", 60)
    config.addProperty("media.localArchives.readerCheckInterval", 180)
    config.addProperty("media.localArchives.readerCheckInitialDelay", 240)
    config.addProperty("media.localArchives.downloadChunkSize", 16384)
    config.addProperty("media.localArchives.infoSizeLimit", 16384)
    config.addProperty("media.localArchives.rootPath", "myMusic")
    config.addProperty("media.localArchives.processorCount", 2)
    config.addProperty("media.localArchives.excludedExtensions", Array("JPG", "pdf", "tex"))
    config.addProperty("media.localArchives.metaDataExtraction.readChunkSize", 4096)
    config.addProperty("media.localArchives.metaDataExtraction.tagSizeLimit", 16384)
    config.addProperty("media.localArchives.metaDataExtraction.processingTimeout", 30)
    config.addProperty("media.localArchives.metaDataExtraction.metaDataUpdateChunkSize", 8192)
    config.addProperty("media.localArchives.metaDataExtraction.metaDataMaxMessageSize", 32768)
    config.addProperty("media.localArchives.metaDataPersistence.path", "data")
    config.addProperty("media.localArchives.metaDataPersistence.chunkSize", 1024)
    config.addProperty("media.localArchives.metaDataPersistence.parallelCount", 4)
    config.addProperty("media.localArchives.metaDataPersistence.writeBlockSize", 64)
    config.addProperty("media.localArchives.localArchive(-1).rootPath", "myMusic")
    config.addProperty("media.localArchives.localArchive.archiveName", "Archive1")
    config.addProperty("media.localArchives.localArchive(-1).rootPath", "myOtherMusic")
    config.addProperty("media.localArchives.localArchive.archiveName", "Archive2")

    config
  }
}

/**
  * Test class for ''LocalArchiveStartup''.
  */
class LocalArchiveStartupSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {

  import LocalArchiveStartupSpec._

  def this() = this(ActorSystem("LocalArchiveStartupSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A LocalArchiveStartup" should "create actors for the local archive" in {
    val helper = new LocalArchiveStartupTestHelper

    helper.activate()
      .verifyActorsCreated()
  }

  it should "stop actors of the local archive on deactivation" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate()

    helper.deactivate().verifyActorsStopped()
  }

  /**
    * Test helper class managing a test instance and required dependencies.
    */
  private class LocalArchiveStartupTestHelper {
    /** Test probe for the archive group manager. */
    private val probeGroupActor = TestProbe()

    /** Test probe for the media union actor. */
    private val probeUnionMediaManager = TestProbe()

    /** Test probe for the meta data union actor. */
    private val probeUnionMetaDataManager = TestProbe()

    /** The startup instance to be tested. */
    val startup = new LocalArchiveStartup

    /** The test message bus. */
    val messageBus = new MessageBusTestImpl

    /** The client application context. */
    private val clientContext = createClientContext()

    /** A set for the actors that have been created by the test factory. */
    private var createdActors = Set.empty[TestProbe]

    /**
      * Activates the test component.
      *
      * @return this test helper
      */
    def activate(): LocalArchiveStartupTestHelper = {
      val facadeActors = MediaFacadeActors(probeUnionMediaManager.ref,
        probeUnionMetaDataManager.ref)
      startup initClientContext clientContext
      startup initMediaFacadeActors facadeActors
      startup activate mock[ComponentContext]
      this
    }

    /**
      * Deactivates the test component.
      *
      * @return this test helper
      */
    def deactivate(): LocalArchiveStartupTestHelper = {
      startup deactivate mock[ComponentContext]
      this
    }

    /**
      * Checks whether all expected actors have been created correctly.
      *
      * @return this test helper
      */
    def verifyActorsCreated(): LocalArchiveStartupTestHelper = {
      createdActors should have size 1
      this
    }

    /**
      * Checks whether the managed actors have been stopped.
      *
      * @return this test helper
      */
    def verifyActorsStopped(): LocalArchiveStartupTestHelper = {
      watch(probeGroupActor.ref)
      expectTerminated(probeGroupActor.ref)
      this
    }

    /**
      * Creates a mock ''ClientApplicationContext''.
      *
      * @return the client context
      */
    private def createClientContext(): ClientApplicationContext = {
      val actorFactory = createActorFactory()
      val clientContext = mock[ClientApplicationContext]
      when(clientContext.managementConfiguration).thenReturn(createArchiveConfiguration())
      when(clientContext.actorFactory).thenReturn(actorFactory)
      when(clientContext.actorSystem).thenReturn(system)
      when(clientContext.messageBus).thenReturn(messageBus)
      when(clientContext.mediaFacade).thenReturn(mock[MediaFacade])
      clientContext
    }

    /**
      * Creates an actor factory mock that checks the creation of the actors
      * comprising the media archive and injects test probes.
      *
      * @return the actor factory mock
      */
    private def createActorFactory(): ActorFactory = {
      val factory = mock[ActorFactory]
      when(factory.createActor(any(classOf[Props]), anyString()))
        .thenAnswer((invocation: InvocationOnMock) => {
          val props = invocation.getArguments.head.asInstanceOf[Props]
          val name = invocation.getArguments()(1).asInstanceOf[String]
          name match {
            case "archiveGroupActor" =>
              val refProps = ArchiveGroupActor(probeUnionMediaManager.ref, probeUnionMetaDataManager.ref,
                ArchiveConfigs)
              props should be(refProps)
              actorCreation(probeGroupActor)
          }
        })
      factory
    }

    /**
      * Simulates an actor creation through the actor factory and records the
      * actor that has been created.
      *
      * @param probe the probe representing the actor
      * @return the actor reference
      */
    private def actorCreation(probe: TestProbe): ActorRef = {
      createdActors should not contain probe
      createdActors = createdActors + probe
      probe.ref
    }

  }

}
