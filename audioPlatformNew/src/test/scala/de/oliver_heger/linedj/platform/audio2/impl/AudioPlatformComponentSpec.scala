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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.impl.AudioPlayerControllerActor.AudioPlayerControllerCommand
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, UnregisterService}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.player.engine.{AsyncAudioStreamFactory, AudioStreamFactory}
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.osgi.service.component.ComponentContext
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

/**
  * Test class for [[AudioPlatformComponent]].
  */
class AudioPlatformComponentSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, 
  BeforeAndAfterAll, Matchers, MockitoSugar, OptionValues:
  def this() = this(ActorSystem("AudioPlatformComponentSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)  
    super.afterAll()

  "An AudioPlatformComponent" should "handle a deactivation without an activation" in:
    val componentContext = mock[ComponentContext]
    val helper = new ComponentTestHelper

    helper.component.deactivate(componentContext)

    verifyNoInteractions(componentContext)
    helper.messageBus.expectNoMessage(100.millis)

  it should "pass a stream factory to add to the audio stream factory manager" in:
    val factory = mock[AudioStreamFactory]
    val helper = new ComponentTestHelper

    helper.component.addAudioStreamFactory(factory)

    // The factory is converted to an async factory.
    verify(helper.audioStreamFactoryManager).addFactory(any())

  it should "pass a stream factory to remove to the audio stream factory manager" in:
    val factory = mock[AudioStreamFactory]
    val helper = new ComponentTestHelper

    helper.component.removeAudioStreamFactory(factory)

    verify(helper.audioStreamFactoryManager).removeFactory(any())

  it should "install an audio player controller actor" in:
    val helper = new ComponentTestHelper
    helper.activate()

    helper.optPromiseReady.value.success(())

    val registerMsg = helper.messageBus.expectMessageType[RegisterService]
    registerMsg.service should be(AudioPlatformComponent.audioPlayerControllerDependency)

  it should "not publish the controller service dependency before the promise is fulfilled" in:
    val helper = new ComponentTestHelper
    helper.activate()

    helper.messageBus.expectNoMessage(200.millis)

  it should "stop the controller actor on deactivate" in:
    val helper = new ComponentTestHelper
    helper.activate()

    helper.deactivate()

    helper.probeControllerActor.expectMessage(AudioPlayerControllerActor.Stop)

  it should "unregister the service dependency for the controller on deactivate" in:
    val helper = new ComponentTestHelper
    helper.activate()

    helper.deactivate()

    val unregisterMsg = helper.messageBus.expectMessageType[UnregisterService]
    unregisterMsg.service should be(AudioPlatformComponent.audioPlayerControllerDependency)

  it should "shutdown the audio stream factory manager on deactivate" in:
    val helper = new ComponentTestHelper
    helper.activate()
    
    helper.deactivate()
    
    verify(helper.audioStreamFactoryManager).shutdown()
  
  /**
    * A test helper class managing the object under test and its dependencies.
    */
  private class ComponentTestHelper:
    /** The message bus. */
    val messageBus = new MessageBusTestImpl
    
    /** Mock for the archive service. */
    private val archiveService: ArchiveService = mock[ArchiveService]
    
    /** Mock for the config service. */
    private val configService: ConfigService = mock[ConfigService]
    
    /** Mock for the actor factory. */
    private val actorFactory: ActorFactory = createActorFactory()

    /** Mock for the managed audio stream factory. */
    private val managedAudioStreamFactory: AsyncAudioStreamFactory = mock[AsyncAudioStreamFactory]

    /** The test probe for the controller actor. */
    val probeControllerActor: TestProbe[AudioPlayerControllerCommand] =
      typedTestKit.createTestProbe[AudioPlayerControllerActor.AudioPlayerControllerCommand]()

    /** Mock for the audio stream factory manager. */
    val audioStreamFactoryManager: AudioStreamFactoryManager = createAudioStreamFactoryManager()

    /** Stores the promise passed to the controller factory. */
    var optPromiseReady: Option[Promise[Unit]] = None

    /** The component to be tested. */
    val component: AudioPlatformComponent = createComponent()

    /**
      * Passes the dependencies to the test component and activates it.
      */
    def activate(): Unit =
      component.initArchiveService(archiveService)
      component.initConfigService(configService)
      component.initClientContext(createClientApplicationContext())
      component.activate(mock)

    /**
      * Deactivates the test component.
       */
    def deactivate(): Unit =
      val componentContext = mock[ComponentContext]
      component.deactivate(componentContext)
      verifyNoInteractions(componentContext)

    /**
      * Creates a [[ClientApplicationContext]] object that returns the mock and
      * helper objects managed by this object.
      * @return the [[ClientApplicationContext]]
      */
    private def createClientApplicationContext(): ClientApplicationContext =
      val context = mock[ClientApplicationContext]
      when(context.actorFactory).thenReturn(actorFactory)
      when(context.messageBus).thenReturn(messageBus)
      when(context.actorSystem).thenReturn(system)
      context

    /**
      * Creates a mock for the [[AudioStreamFactoryManager]] that is prepared
      * to return the mock for the managed audio stream factory.
      * @return the mock audio stream factory manager
      */
    private def createAudioStreamFactoryManager(): AudioStreamFactoryManager =
      val manager = mock[AudioStreamFactoryManager]
      when(manager.createManagedFactory(actorFactory)).thenReturn(managedAudioStreamFactory)
      manager

    /**
      * Creates an [[ActorFactory]] to be used by the tests. This factory
      * spawns only anonymous actors to prevent non-unique-actor-name
      * exceptions.
      * @return the actor factory
      */
    private def createActorFactory(): ActorFactory =
      val delegateFactory: ActorFactory = implicitly
      new ActorFactory:
        export delegateFactory.{createTypedActor => _, *}

        override def createTypedActor[T](behavior: Behavior[T],
                                         name: String,
                                         props: Props,
                                         optStopCommand: Option[T]): ActorRef[T] =
          name should be(AudioPlatformComponent.AudioPlayerControllerActorName)
          optStopCommand shouldBe empty
          typedTestKit.spawn(behavior)

    /**
      * Creates the test component instance.
       * @return the component to be tested
      */
    private def createComponent(): AudioPlatformComponent =
      val controllerFactory = new AudioPlayerControllerActor.Factory:
        override def apply(config: AudioPlayerControllerActor.Config): Behavior[AudioPlayerControllerCommand] =
          config.archiveService should be(archiveService)
          config.configService should be(configService)
          config.messageBus should be(messageBus)
          config.audioPlayerFactory should be(AudioPlayerActor.newInstance)
          config.playlistService should be(PlaylistServiceImpl)
          config.audioStreamFactory should be(managedAudioStreamFactory)
          optPromiseReady = Option(config.promiseReady)
          Behaviors.monitor(probeControllerActor.ref, Behaviors.ignore)

      new AudioPlatformComponent(controllerFactory, audioStreamFactoryManager)
