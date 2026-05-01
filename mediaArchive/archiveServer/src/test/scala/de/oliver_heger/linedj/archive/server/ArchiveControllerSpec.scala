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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.server.MediaFileResolver.FileResolverFunc
import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor
import de.oliver_heger.linedj.server.common.{ConfigSupport, ServerConfig, ServerController}
import de.oliver_heger.linedj.shared.actors.{ActorManagement, ManagingActorFactory}
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
  * Test class for [[ArchiveController]].
  */
class ArchiveControllerSpec(testSystem: classics.ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(classics.ActorSystem("ArchiveControllerSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Returns a [[ManagingActorFactory]] that is prepared to create the content
    * actor for the archive server.
    *
    * @param contentActorFactory the factory for creating the content actor
    * @param contentActor        the actor managing the archive content
    * @return the actor factory
    */
  private def createActorFactory(contentActorFactory: ArchiveContentActor.Factory,
                                 contentActor: ActorRef[ArchiveContentActor.ArchiveContentCommand]):
  ManagingActorFactory =
    val contentBehavior = mock[Behavior[ArchiveContentActor.ArchiveContentCommand]]
    doReturn(contentBehavior).when(contentActorFactory).apply(null)

    new ManagingActorFactory:
      override val management: ActorManagement = new ActorManagement {}

      override def actorSystem: classics.ActorSystem = system

      override def createClassicActor(props: classics.Props,
                                      name: String,
                                      optStopCommand: Option[Any]): classics.ActorRef =
        throw new UnsupportedOperationException("Unexpected call.")

      override def createTypedActor[T](behavior: Behavior[T],
                                       name: String,
                                       props: Props,
                                       optStopCommand: Option[T]): ActorRef[T] =
        name match
          case "contentActor" =>
            behavior should be(contentBehavior)
            optStopCommand shouldBe empty
            contentActor.ref.asInstanceOf[ActorRef[T]]

  "ArchiveController" should "create a correct custom context" in :
    val helper = new ControllerTestHelper
    val archiveServerConfig = ArchiveServerConfig(10.seconds, 42)
    val context = ConfigSupport.ConfigSupportContext(mock[ServerConfig], archiveServerConfig, ())
      .asInstanceOf[ConfigSupport.ConfigSupportContext[helper.controller.CustomConfig, Unit]]

    helper.controller.createCustomContext(context)(using helper.services) map : context =>
      context.contentActor should be(helper.contentActor)
      context.archiveContext should be("testCustomContext")
  
  /**
    * A test helper class that manages the dependencies of the controller to
    * be tested.
    */
  private class ControllerTestHelper(propertyAccess: SystemPropertyAccess = new SystemPropertyAccess {}):
    /** The factory to create a content actor. */
    private val mockContentActorFactory = mock[ArchiveContentActor.Factory]

    /** Test probe for the content actor. */
    private val probeContentActor = typedTestKit.createTestProbe[ArchiveContentActor.ArchiveContentCommand]()

    /** The object with services to be consumed by the server. */
    val services: ServerController.ServerServices =
      ServerController.ServerServices(system, createActorFactory(mockContentActorFactory, probeContentActor.ref))

    /** The controller to be tested. */
    val controller: ArchiveController = createController()

    /**
      * Returns a reference to the content actor managed by this helper.
      *
      * @return the content actor
      */
    def contentActor: ActorRef[ArchiveContentActor.ArchiveContentCommand] = probeContentActor.ref

    /**
      * Creates the controller to be tested.
      *
      * @return the test controller
      */
    private def createController(): ArchiveController =
      new ArchiveController with SystemPropertyAccess:
        override protected val contentActorFactory: ArchiveContentActor.Factory = mockContentActorFactory

        override type ArchiveConfig = Int

        override type ArchiveContext = String

        override val defaultConfigFileName: String = "archive-server-config.xml"

        /**
          * @inheritdoc This implementation simply returns a value from the
          *             test configuration.
          */
        override def archiveConfigLoader: ConfigSupport.ConfigLoader[ArchiveConfig] = config =>
          Try(config.getInt("test.value"))
        
        override def createArchiveContext(context: ArchiveController.ArchiveServerContext[ArchiveConfig, Unit])
                                         (using services: ServerController.ServerServices): Future[ArchiveContext] = 
          context.contentActor should not be null
          context.config.archiveConfig should be(42)
          Future.successful("testCustomContext")

        override def fileResolverFunc(context: Context)
                                     (using services: ServerController.ServerServices): FileResolverFunc =
          throw new UnsupportedOperationException("Unexpected call.")

        override def getSystemProperty(key: String): Option[String] = propertyAccess.getSystemProperty(key)
