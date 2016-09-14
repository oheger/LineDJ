/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.archivestart

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.MediaManagerActor
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.comm.ActorFactory
import org.apache.commons.configuration.HierarchicalConfiguration
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object MediaArchiveStartupSpec {
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
    config.addProperty("media.roots.root.path", "myMusic")
    config.addProperty("media.roots.root.processorCount", 2)
    config.addProperty("media.roots.root.accessRestriction", 1)
    config.addProperty("media.excludedExtensions", Array("JPG", "pdf", "tex"))
    config.addProperty("media.metaDataExtraction.readChunkSize", 4096)
    config.addProperty("media.metaDataExtraction.tagSizeLimit", 16384)
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
  * Test class for ''MediaArchiveStartup''.
  */
class MediaArchiveStartupSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import MediaArchiveStartupSpec._

  def this() = this(ActorSystem("MediaArchiveStartupSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MediaArchiveStartup" should "create the expected actors" in {
    new MediaArchiveStartupTestHelper().createAndActivateStartup().verifyActorsCreated()
  }

  it should "send a scan message to the media manager" in {
    val helper = new MediaArchiveStartupTestHelper
    helper.createAndActivateStartup()

    helper.probeMediaManager.expectMsg(MediaManagerActor.ScanAllMedia)
  }

  /**
    * A helper class that manages dependencies used by the class under test.
    */
  private class MediaArchiveStartupTestHelper {
    /** Test probe for the media manager actor. */
    val probeMediaManager = TestProbe()

    /** Test probe for the meta data manager actor. */
    val probeMetaDataManager = TestProbe()

    /** Test probe for the persistent meta data manager. */
    val probePersistentManager = TestProbe()

    /** A set for the actors that have been created by the test factory. */
    private var createdActors = Set.empty[TestProbe]

    /**
      * Creates a ''MediaArchiveStartup'' object and initializes it.
      *
      * @return the ''MediaArchiveStartup''
      */
    def createStartup(): MediaArchiveStartup = {
      val startup = new MediaArchiveStartup
      startup initClientApplicationContext createClientContext
      startup
    }

    /**
      * Invokes ''activate()'' on the specified startup object.
      *
      * @param startup the object to be activated
      * @return the same startup object
      */
    def activateStartup(startup: MediaArchiveStartup): MediaArchiveStartup = {
      val compCtx = mock[ComponentContext]
      startup activate compCtx
      startup
    }

    /**
      * Creates and activates a ''MediaArchiveStartup'' object. Typically,
      * the object itself is irrelevant; this test class is only interested in
      * the effects of activating this object.
      *
      * @return this test helper
      */
    def createAndActivateStartup(): MediaArchiveStartupTestHelper = {
      activateStartup(createStartup())
      this
    }

    /**
      * Checks whether all expected actors have been created correctly.
      */
    def verifyActorsCreated(): Unit = {
      createdActors should have size 3
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
                val refProps = PersistentMetaDataManagerActor(ArchiveConfig)
                props.actorClass() should be(refProps.actorClass())
                props.args.head should be(refProps.args.head)
                actorCreation(probePersistentManager)

              case "metaDataManager" =>
                props should be(MetaDataManagerActor(ArchiveConfig, probePersistentManager.ref))
                actorCreation(probeMetaDataManager)

              case "mediaManager" =>
                props should be(MediaManagerActor(ArchiveConfig, probeMetaDataManager.ref))
                actorCreation(probeMediaManager)
            }
          }
        })
      factory
    }

    /**
      * Checks whether the expected props have been passed to the actor factory.
      *
      * @param expected expected props
      * @param actual   actual props
      */
    private def checkProps(expected: Props, actual: Props): Unit = {
      actual.actorClass() should be(expected.actorClass())
      actual.args should be(expected.args)
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
