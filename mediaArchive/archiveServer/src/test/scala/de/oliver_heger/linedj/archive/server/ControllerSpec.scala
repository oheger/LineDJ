/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.group.ArchiveGroupActor
import de.oliver_heger.linedj.archive.server.content.{ArchiveContentActor, ArchiveContentMetadataProcessingListener}
import de.oliver_heger.linedj.archiveunion.{MediaUnionActor, MetadataUnionActor}
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.shared.actors.{ActorManagement, ManagingActorFactory}
import de.oliver_heger.linedj.shared.archive.metadata.MetadataProcessingEvent
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.Seq

/**
  * Test class for [[Controller]].
  */
class ControllerSpec(testSystem: classics.ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(classics.ActorSystem("ControllerSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Returns a [[ManagingActorFactory]] that is prepared to create the actors
    * for the archive server. The factory checks the creation parameters passed
    * to the actors.
    *
    * @param expectedArchiveNames the archive names defined in the
    *                             configuration used by the controller
    * @param contentActorFactory  the factory for creating the content actor
    * @param contentActor         the actor managing the archive content
    * @param listenerFactory      the factory for creating the listener
    * @param contentBehavior      the behavior for the content actor
    * @param refGroupActorCreated a boolean reference to check whether the
    *                             group actor has been created
    * @return the actor factory
    */
  private def createActorFactory(expectedArchiveNames: List[String],
                                 contentActorFactory: ArchiveContentActor.Factory,
                                 contentActor: ActorRef[ArchiveContentActor.ArchiveContentCommand],
                                 listenerFactory: ArchiveContentMetadataProcessingListener.Factory,
                                 contentBehavior: Behavior[ArchiveContentActor.ArchiveContentCommand] = mock,
                                 refGroupActorCreated: AtomicBoolean = new AtomicBoolean): ManagingActorFactory =
    doReturn(contentBehavior).when(contentActorFactory).apply(null)

    val listenerBehavior = mock[Behavior[MetadataProcessingEvent]]
    doReturn(listenerBehavior).when(listenerFactory).apply(contentActor.ref)

    val probeMetadataUnionActor = TestProbe()
    val probeMediaUnionActor = TestProbe()
    val probeGroupActor = TestProbe()

    new ManagingActorFactory:
      override val management: ActorManagement = new ActorManagement {}

      override def actorSystem: classics.ActorSystem = system

      override def createClassicActor(props: classics.Props,
                                      name: String,
                                      optStopCommand: Option[Any]): classics.ActorRef =
        name match
          case "metadataUnionActor" =>
            optStopCommand shouldBe empty
            props.actorClass() should be(classOf[MetadataUnionActor])
            props.args should contain only Controller.DefaultUnionArchiveConfig
            probeMetadataUnionActor.ref

          case "mediaUnionActor" =>
            optStopCommand shouldBe empty
            classOf[MediaUnionActor].isAssignableFrom(props.actorClass()) shouldBe true
            props.args should contain only probeMetadataUnionActor.ref
            probeMediaUnionActor.ref

          case "archiveGroupActor" =>
            optStopCommand shouldBe empty
            classOf[ArchiveGroupActor].isAssignableFrom(props.actorClass()) shouldBe true
            val expectedArgs = List(probeMediaUnionActor.ref, probeMetadataUnionActor.ref, listenerBehavior)
            props.args.head should be(probeMediaUnionActor.ref)
            props.args(1) should be(probeMetadataUnionActor.ref)
            props.args(2) should be(listenerBehavior)
            val archiveConfigs = props.args(3).asInstanceOf[Seq[MediaArchiveConfig]]
            archiveConfigs.map(_.archiveName) should contain theSameElementsInOrderAs expectedArchiveNames
            refGroupActorCreated.set(true)
            probeGroupActor.ref

      override def createTypedActor[T](behavior: Behavior[T],
                                       name: String,
                                       props: Props,
                                       optStopCommand: Option[T]): ActorRef[T] =
        name match
          case "contentActor" =>
            behavior should be(contentBehavior)
            optStopCommand shouldBe empty
            contentActor.ref.asInstanceOf[ActorRef[T]]


  "Controller" should "create a correct context" in :
    val contentBehavior = mock[Behavior[ArchiveContentActor.ArchiveContentCommand]]
    val contentActorFactory = mock[ArchiveContentActor.Factory]
    val contentActor = typedTestKit.createTestProbe[ArchiveContentActor.ArchiveContentCommand]()
    val listenerFactory = mock[ArchiveContentMetadataProcessingListener.Factory]
    val refGroupActorCreated = new AtomicBoolean

    val actorFactory = createActorFactory(
      List("pop"),
      contentActorFactory,
      contentActor.ref,
      listenerFactory,
      contentBehavior,
      refGroupActorCreated
    )
    val services = ServerController.ServerServices(system, actorFactory)
    val controller = new Controller(
      contentActorFactory = contentActorFactory,
      metadataListenerFactory = listenerFactory
    ) with SystemPropertyAccess {}
    controller.createContext(using services) map : context =>
      refGroupActorCreated.get() shouldBe true
      context.contentActor should be(contentActor.ref)
      context.serverConfig.serverPort should be(8080)

  it should "load the configuration from an alternative location" in :
    val ConfigFileName = "test-archive-server-config.xml"
    val contentBehavior = mock[Behavior[ArchiveContentActor.ArchiveContentCommand]]
    val contentActorFactory = mock[ArchiveContentActor.Factory]
    val contentActor = typedTestKit.createTestProbe[ArchiveContentActor.ArchiveContentCommand]()
    val listenerFactory = mock[ArchiveContentMetadataProcessingListener.Factory]

    val actorFactory = createActorFactory(
      List("rock", "classic"),
      contentActorFactory,
      contentActor.ref,
      listenerFactory,
      contentBehavior
    )
    val services = ServerController.ServerServices(system, actorFactory)
    val controller = new Controller(
      contentActorFactory = contentActorFactory,
      metadataListenerFactory = listenerFactory
    ) with SystemPropertyAccess:
      override def getSystemProperty(key: String): Option[String] =
        key should be(Controller.PropConfigFileName)
        Some(ConfigFileName)

    controller.createContext(using services) map : context =>
      context.contentActor should be(contentActor.ref)
      context.serverConfig.serverPort should be(8085)

  it should "return correct server parameters" in :
    val config = ArchiveServerConfig(
      serverPort = 8765,
      timeout = ArchiveServerConfig.DefaultServerTimeout,
      archiveConfigs = Nil
    )
    val context = Controller.ArchiveServerContext(
      serverConfig = config,
      contentActor = typedTestKit.createTestProbe[ArchiveContentActor.ArchiveContentCommand]().ref
    )
    val services = ServerController.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)

    val controller = new Controller() with SystemPropertyAccess {}
    controller.serverParameters(context)(using services) map : params =>
      params.optLocatorParams shouldBe empty
      params.bindingParameters should be(ServerController.BindingParameters("0.0.0.0", config.serverPort))
      