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

import de.oliver_heger.linedj.platform.app.{ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.impl.AudioPlatformComponent.log
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency, UnregisterService}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.typed.ActorRef
import org.osgi.service.component.ComponentContext

import scala.compiletime.uninitialized
import scala.concurrent.{ExecutionContext, Promise}

object AudioPlatformComponent:
  private val log = LogManager.getLogger(classOf[AudioPlatformComponent])

  /** The name for audio player controller actor. */
  final val AudioPlayerControllerActorName = "audioPlayerControllerActor"

  /** The name of the dependency for the audio player controller. */
  private val AudioPlayerControllerDependencyName = "lineDJ.audioPlayerController"

  /**
    * A [[ServiceDependency]] to represent the audio player controller. The
    * component registers this dependency when the controller is registered at
    * the system message bus and is ready to process commands for the audio
    * player.
    */
  final val audioPlayerControllerDependency = ServiceDependency(AudioPlayerControllerDependencyName)
end AudioPlatformComponent

/**
  * A declarative services component representing the audio platform.
  *
  * This component is started automatically by the declarative services
  * runtime when all dependencies are satisfied. It is responsible for
  * creating and registering controller objects (and corresponding OSGi
  * services) that control the playback of audio based on commands sent to the
  * central message bus. Of course, correct cleanup needs to be done when the
  * component is deactivated.
  *
  * @param controllerFactory         the factory to create the controller actor
  * @param audioStreamFactoryManager the manager for stream factories
  */
class AudioPlatformComponent(controllerFactory: AudioPlayerControllerActor.Factory,
                             audioStreamFactoryManager: AudioStreamFactoryManager) extends PlatformComponent,
  ClientContextSupport:
  def this() = this(AudioPlayerControllerActor.newInstance, new AudioStreamFactoryManager)

  /** The archive service. */
  private var archiveService: ArchiveService = uninitialized

  /** The config service. */
  private var configService: ConfigService = uninitialized

  /** Stores the controller actor. */
  private var optControllerActor: Option[ActorRef[AudioPlayerControllerActor.AudioPlayerControllerCommand]] = None

  import AudioPlatformComponent.*

  /**
    * Initializes the dependency to the [[ArchiveService]]. This method is 
    * called by the declarative services runtime.
    *
    * @param archiveService the [[ArchiveService]]
    */
  def initArchiveService(archiveService: ArchiveService): Unit =
    log.info("ArchiveService is set.")
    this.archiveService = archiveService

  /**
    * Initializes the dependency to the [[ConfigService]]. This method is 
    * called by the declarative services runtime.
    *
    * @param configService the [[ConfigService]]
    */
  def initConfigService(configService: ConfigService): Unit =
    log.info("ConfigService is set.")
    this.configService = configService

  /**
    * Adds an [[AudioStreamFactory]] service to this component. Multiple 
    * factories are supported, at least one must be present for this component
    * to start. This function is called by the declarative services runtime.
    *
    * @param factory the [[AudioStreamFactory]] to add
    */
  def addAudioStreamFactory(factory: AudioStreamFactory): Unit =
    audioStreamFactoryManager.addFactory(factory)

  /**
    * Removes the given [[AudioStreamFactory]] service from this
    * component. This function is called by the declarative services runtime.
    *
    * @param factory the [[AudioStreamFactory]] to remove
    */
  def removeAudioStreamFactory(factory: AudioStreamFactory): Unit =
    audioStreamFactoryManager.removeFactory(factory)

  override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)
    log.info("Activating AudioPlatformComponent.")

    val audioStreamFactory = audioStreamFactoryManager.createManagedFactory(clientApplicationContext.actorFactory)
    val controllerConfig = AudioPlayerControllerActor.Config(
      messageBus = clientApplicationContext.messageBus,
      archiveService = archiveService,
      configService = configService,
      audioStreamFactory = audioStreamFactory,
      promiseReady = promiseForControllerDependencyRegistration(clientApplicationContext.messageBus)
    )
    optControllerActor = Some(
      clientApplicationContext.actorFactory.createTypedActor(
        controllerFactory(controllerConfig), AudioPlayerControllerActorName
      )
    )

  override def deactivate(componentContext: ComponentContext): Unit =
    log.info("Deactivating AudioPlatformComponent.")
    super.deactivate(componentContext)

    optControllerActor foreach : actor =>
      actor ! AudioPlayerControllerActor.Stop
      clientApplicationContext.messageBus.publish(UnregisterService(audioPlayerControllerDependency))
    audioStreamFactoryManager.shutdown()

  /**
    * Returns a [[Promise]] to be passed to the audio player controller to 
    * receive a notification when the controller is ready. This is the signal
    * to publish the corresponding service dependency.
    *
    * @param bus the message bus
    * @return the ready promise for the controller
    */
  private def promiseForControllerDependencyRegistration(bus: MessageBus): Promise[Unit] =
    given ExecutionContext = clientApplicationContext.actorSystem.dispatcher

    val promise = Promise[Unit]()
    promise.future.foreach: _ =>
      log.info("AudioPlayerControllerActor is ready to process commands.")
      bus.publish(RegisterService(audioPlayerControllerDependency))

    promise  
  