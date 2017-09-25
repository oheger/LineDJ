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

package de.oliver_heger.linedj.archivelocalstart

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.MediaManagerActor
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.apache.commons.configuration.HierarchicalConfiguration
import org.mockito.Matchers.{any, anyString, eq => eqArg}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object LocalArchiveStartupSpec {
  /** The reference configuration for the media archive. */
  private val ArchiveConfig = MediaArchiveConfig(createArchiveConfiguration())

  /**
    * Creates a configuration object that can be used to initialize the config
    * for the media archive.
    *
    * @return the configuration
    */
  private def createArchiveConfiguration(): HierarchicalConfiguration = {
    val config = new HierarchicalConfiguration
    config.addProperty("media.readerTimeout", 60)
    config.addProperty("media.readerCheckInterval", 180)
    config.addProperty("media.readerCheckInitialDelay", 240)
    config.addProperty("media.downloadChunkSize", 16384)
    config.addProperty("media.infoSizeLimit", 16384)
    config.addProperty("media.roots.root.path", "myMusic")
    config.addProperty("media.roots.root.processorCount", 2)
    config.addProperty("media.roots.root.accessRestriction", 1)
    config.addProperty("media.excludedExtensions", Array("JPG", "pdf", "tex"))
    config.addProperty("media.metaDataExtraction.readChunkSize", 4096)
    config.addProperty("media.metaDataExtraction.tagSizeLimit", 16384)
    config.addProperty("media.metaDataExtraction.processingTimeout", 30)
    config.addProperty("media.metaDataExtraction.metaDataUpdateChunkSize", 8192)
    config.addProperty("media.metaDataExtraction.metaDataMaxMessageSize", 32768)
    config.addProperty("media.metaDataPersistence.path", "data")
    config.addProperty("media.metaDataPersistence.chunkSize", 1024)
    config.addProperty("media.metaDataPersistence.parallelCount", 4)
    config.addProperty("media.metaDataPersistence.writeBlockSize", 64)

    config
  }
}

/**
  * Test class for ''LocalArchiveStartup''.
  */
class LocalArchiveStartupSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import LocalArchiveStartupSpec._

  def this() = this(ActorSystem("LocalArchiveStartupSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A LocalArchiveStartup" should "create local archive actors" in {
    val helper = new LocalArchiveStartupTestHelper

    helper.activate()
      .verifyActorsCreated()
  }

  it should "start a media scan after creating the local archive" in {
    val helper = new LocalArchiveStartupTestHelper

    helper.activate()
      .probeMediaManager.expectMsg(ScanAllMedia)
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
    /** Test probe for the media manager actor. */
    val probeMediaManager = TestProbe()

    /** Test probe for the meta data manager actor. */
    val probeMetaDataManager = TestProbe()

    /** Test probe for the persistent meta data manager. */
    val probePersistentManager = TestProbe()

    /** Test probe for the media union actor. */
    val probeUnionMediaManager = TestProbe()

    /** Test probe for the meta data union actor. */
    val probeUnionMetaDataManager = TestProbe()

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
      createdActors should have size 3
      this
    }

    /**
      * Checks whether the managed actors have been stopped.
      *
      * @return this test helper
      */
    def verifyActorsStopped(): LocalArchiveStartupTestHelper = {
      val probe = TestProbe()
      (probePersistentManager :: probeMediaManager :: probeMetaDataManager
        :: Nil) foreach { p =>
        probe watch p.ref
        probe.expectMsgType[Terminated].actor should be(p.ref)
      }
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
      * Creates an actor factory mock that allows and checks the creation of
      * the actors comprising the media archive.
      *
      * @return the actor factory mock
      */
    private def createActorFactory(): ActorFactory = {
      val factory = mock[ActorFactory]
      when(factory.createActor(any(classOf[Props]), anyString()))
        .thenAnswer(new Answer[ActorRef] {
          override def answer(invocation: InvocationOnMock): ActorRef = {
            val props = invocation.getArguments.head.asInstanceOf[Props]
            val name = invocation.getArguments()(1).asInstanceOf[String]
            name match {
              case "persistentMetaDataManager" =>
                val refProps = PersistentMetaDataManagerActor(ArchiveConfig,
                  probeUnionMetaDataManager.ref)
                props.actorClass() should be(refProps.actorClass())
                props.args.head should be(refProps.args.head)
                props.args(1) should be(refProps.args(1))
                actorCreation(probePersistentManager)

              case "localMetaDataManager" =>
                props should be(MetaDataManagerActor(ArchiveConfig, probePersistentManager.ref,
                  probeUnionMetaDataManager.ref))
                actorCreation(probeMetaDataManager)

              case "localMediaManager" =>
                props should be(MediaManagerActor(ArchiveConfig, probeMetaDataManager.ref,
                  probeUnionMediaManager.ref))
                actorCreation(probeMediaManager)
            }
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
