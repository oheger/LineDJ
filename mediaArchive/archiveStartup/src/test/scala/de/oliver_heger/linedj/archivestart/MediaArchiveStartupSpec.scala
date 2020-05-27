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

package de.oliver_heger.linedj.archivestart

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.archiveunion.{MediaArchiveConfig, MediaUnionActor, MetaDataUnionActor}
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.comm.ActorFactory
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.osgi.service.component.ComponentContext
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

object MediaArchiveStartupSpec {
  /** The reference configuration for the media archive. */
  private val ArchiveConfig = MediaArchiveConfig(createArchiveConfiguration())

  /**
    * Creates a configuration object that can be used to initialize the config
    * for the media archive.
    *
    * @return the configuration
    */
  private def createArchiveConfiguration(): Configuration = {
    val config = new PropertiesConfiguration
    config.addProperty("media.mediaArchive.metaDataUpdateChunkSize", 8192)
    config.addProperty("media.mediaArchive.metaDataMaxMessageSize", 32768)

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

  it should "register the newly created actors" in {
    val helper = new MediaArchiveStartupTestHelper
    val startup = helper.activateStartup(helper.createStartup())

    startup getActor "metaDataManager" should be(helper.probeMetaDataManager.ref)
    startup getActor "mediaManager" should be(helper.probeMediaManager.ref)
  }

  /**
    * A helper class that manages dependencies used by the class under test.
    */
  private class MediaArchiveStartupTestHelper {
    /** Test probe for the media manager actor. */
    val probeMediaManager: TestProbe = TestProbe()

    /** Test probe for the meta data manager actor. */
    val probeMetaDataManager: TestProbe = TestProbe()

    /** A set for the actors that have been created by the test factory. */
    private var createdActors = Set.empty[TestProbe]

    /**
      * Creates a ''MediaArchiveStartup'' object and initializes it.
      *
      * @return the ''MediaArchiveStartup''
      */
    def createStartup(): MediaArchiveStartup = {
      val startup = new MediaArchiveStartup
      startup initClientContext createClientContext
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
      createdActors should have size 2
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
        .thenAnswer((invocation: InvocationOnMock) => {
          val props = invocation.getArguments.head.asInstanceOf[Props]
          val name = invocation.getArguments()(1).asInstanceOf[String]
          name match {
            case "metaDataManager" =>
              props should be(Props(classOf[MetaDataUnionActor], ArchiveConfig))
              actorCreation(probeMetaDataManager)

            case "mediaManager" =>
              props should be(MediaUnionActor(probeMetaDataManager.ref))
              actorCreation(probeMediaManager)
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
