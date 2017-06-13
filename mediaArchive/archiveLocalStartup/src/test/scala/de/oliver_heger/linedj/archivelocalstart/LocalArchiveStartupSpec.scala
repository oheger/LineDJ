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
import akka.util.Timeout
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.MediaManagerActor
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.apache.commons.configuration.HierarchicalConfiguration
import org.mockito.Matchers.{any, anyString, eq => eqArg}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}

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

  "A LocalArchiveStartup" should "register itself for archive availability" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate()

    helper.registration.id should be(helper.startup.componentID)
  }

  it should "remove the registration when it is deactivated" in {
    val helper = new LocalArchiveStartupTestHelper

    helper.activate().deactivate().expectDeRegistration()
  }

  it should "create local archive actors when the archive is available" in {
    val helper = new LocalArchiveStartupTestHelper

    helper.activate()
      .prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectNoStartOfArchive()
      .processUIFuture()
      .verifyActorsCreated()
  }

  it should "support a timeout in the configuration" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate()
    helper.startup.clientApplicationContext.managementConfiguration.addProperty(
      "media.initTimeout", 5)

    helper.prepareArchiveActorsRequest(timeout = Timeout(5.seconds))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyActorsCreated()
  }

  it should "start a media scan after creating the local archive" in {
    val helper = new LocalArchiveStartupTestHelper

    helper.activate()
      .prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .probeMediaManager.expectMsg(ScanAllMedia)
  }

  it should "stop actors of the local archive on deactivation" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyActorsCreated()

    helper.deactivate().verifyActorsStopped()
  }

  it should "not start the archive again on a 2nd archive available message" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyActorsCreated()
      .probeMediaManager.expectMsg(ScanAllMedia)

    helper.sendAvailability(MediaFacade.MediaArchiveAvailable)
      .messageBus.expectNoMessage()
  }

  it should "stop the archive when the union archive is unavailable" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyActorsCreated()

    helper.sendAvailability(MediaFacade.MediaArchiveUnavailable)
      .verifyActorsStopped()
  }

  it should "restart the archive anew when the union archive is available again" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate().prepareArchiveActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyActorsCreated()
      .sendAvailability(MediaFacade.MediaArchiveUnavailable)
      .verifyActorsStopped()

    helper.sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .verifyActorsCreated()
  }

  it should "not start the archive if fetching union actors fails" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate()
      .prepareArchiveActorsRequest(actors = Promise.failed(new Exception("BOOM")))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
    .processUIFuture()

    helper.messageBus.expectNoMessage()
  }

  it should "not start the archive if it becomes unavailable while fetching actors" in {
    val helper = new LocalArchiveStartupTestHelper
    helper.activate()
    .prepareArchiveActorsRequest()
    .sendAvailability(MediaFacade.MediaArchiveAvailable)
    .sendAvailability(MediaFacade.MediaArchiveUnavailable)
    .processUIFuture()

    helper.expectNoStartOfArchive()
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

    /** The registration for the archive availability extension. */
    var registration: ArchiveAvailabilityRegistration = _

    /**
      * Activates the test component.
      *
      * @return this test helper
      */
    def activate(): LocalArchiveStartupTestHelper = {
      startup initClientContext clientContext
      startup activate mock[ComponentContext]
      registration = fetchRegistration()
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
      * Checks that a de-registration message was sent on the message bus.
      *
      * @return this test helper
      */
    def expectDeRegistration(): LocalArchiveStartupTestHelper = {
      val unReg = messageBus.expectMessageType[ArchiveAvailabilityUnregistration]
      unReg.id should be(startup.componentID)
      this
    }

    /**
      * Prepares a request for the actors of the union media archive. The mock
      * is prepared to return a ''Future'' object for the actors based on the
      * provided promise.
      *
      * @param actors promise for the archive actors
      * @param timeout a timeout for the request
      * @return this test helper
      */
    def prepareArchiveActorsRequest(actors: Promise[MediaFacadeActors] =
                                    Promise.successful(MediaFacadeActors(
                                      probeUnionMediaManager.ref,
                                    probeUnionMetaDataManager.ref)),
                                    timeout: Timeout = Timeout(10.seconds))
    : LocalArchiveStartupTestHelper = {
      when(clientContext.mediaFacade.requestFacadeActors()(eqArg(timeout),
        any(classOf[ExecutionContext])))
        .thenReturn(actors.future)
      this
    }

    /**
      * Expects a message to be sent on the UI bus for completing a future in
      * the UI thread. This message is delivered.
      *
      * @return this test helper
      */
    def processUIFuture(): LocalArchiveStartupTestHelper = {
      messageBus.processNextMessage[Any]()
      this
    }

    /**
      * Sends a message to the consumer function registered by the startup.
      *
      * @param event the event to be sent
      * @return this test helper
      */
    def sendAvailability(event: MediaFacade.MediaArchiveAvailabilityEvent):
    LocalArchiveStartupTestHelper = {
      registration.callback(event)
      this
    }

    /**
      * Checks whether all expected actors have been created correctly.
      *
      * @return this test helper
      */
    def verifyActorsCreated(): LocalArchiveStartupTestHelper = {
      awaitCond(createdActors.size == 3)
      createdActors = Set.empty
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
      * Tests whether the archive has not been started. Unfortunately, for this
      * test it is necessary to wait for a while because we cannot be sure when
      * the asynchronous archive creation is through.
      *
      * @return this test helper
      */
    def expectNoStartOfArchive(): LocalArchiveStartupTestHelper = {
      val testMsg = new Object
      probeMetaDataManager.ref ! testMsg
      probeMetaDataManager.expectMsg(testMsg)
      createdActors should have size 0
      this
    }

    /**
      * Obtains the registration for the archive availability extension on the
      * message bus.
      *
      * @return the registration object
      */
    private def fetchRegistration(): ArchiveAvailabilityRegistration =
      messageBus.expectMessageType[ArchiveAvailabilityRegistration]

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
