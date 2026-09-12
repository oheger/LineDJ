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

import de.oliver_heger.linedj.player.engine.AudioStreamFactory.AudioStreamPlaybackData
import de.oliver_heger.linedj.player.engine.{AsyncAudioStreamFactory, AudioStreamFactory, CompositeAsyncAudioStreamFactory, DefaultAudioStreamFactory}
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.concurrent.{ExecutionContext, Promise}

object AudioStreamFactoryManager:
  /** The name to be used for the management actor. */
  private val ActorName = "audioStreamFactoryManager"

  /**
    * Constant for the default list with initial factories. This list contains
    * only the default factory that is always used as a fallback.
    */
  private val DefaultInitialFactories: List[AsyncAudioStreamFactory] = List(DefaultAudioStreamFactory)

  /**
    * An enumeration defining the commands supported by the audio stream 
    * factory management actor.
    */
  private enum FactoryManagerCommand:
    /**
      * Command to add a factory to this actor.
      *
      * @param factory the factory to be added
      */
    case AddFactory(factory: AsyncAudioStreamFactory)

    /**
      * Command to remove a factory from this actor.
      *
      * @param factory the factory to be removed
      */
    case RemoveFactory(factory: AsyncAudioStreamFactory)

    /**
      * A command to create an audio stream for a media file. This is used by 
      * the internal [[AsyncAudioStreamFactory]] implementation provided by the
      * actor. It delegates to the factories that are added dynamically.
      *
      * @param uri           the URI of the affected media file
      * @param promiseResult the promise used to deliver the result
      */
    case CreateAudioStream(uri: String,
                           promiseResult: Promise[AudioStreamPlaybackData])

    /**
      * Command to stop the actor instance.
      */
    case Stop
  end FactoryManagerCommand

  /**
    * Creates the behavior of a new actor instance that is initialized with the
    * given list of already added stream factories.
    *
    * @param initialFactories the initial list of factories
    * @return the [[Behavior]] of the new actor instance
    */
  private def createActor(initialFactories: List[AsyncAudioStreamFactory]): Behavior[FactoryManagerCommand] =
    Behaviors.setup: context =>
      context.log.info("Creating AudioStreamFactoryManager actor with {} initial factories.", initialFactories.size)

      given ExecutionContext = context.executionContext

      def handleCommand(compositeFactory: CompositeAsyncAudioStreamFactory): Behavior[FactoryManagerCommand] =
        Behaviors.receiveMessage:
          case FactoryManagerCommand.AddFactory(factory) =>
            context.log.info("Adding audio stream factory: {}.", factory)
            val nextCompositeFactory = CompositeAsyncAudioStreamFactory(factory :: compositeFactory.factories.toList)
            handleCommand(nextCompositeFactory)

          case FactoryManagerCommand.RemoveFactory(factory) =>
            context.log.info("Removing audio stream factory: {}.", factory)
            val nextFactories = compositeFactory.factories.filterNot(_ eq factory)
            val nextCompositeFactory = CompositeAsyncAudioStreamFactory(nextFactories)
            handleCommand(nextCompositeFactory)

          case FactoryManagerCommand.CreateAudioStream(uri, promiseResult) =>
            compositeFactory.playbackDataForAsync(uri) onComplete : triedResult =>
              promiseResult.complete(triedResult)
            Behaviors.same

          case FactoryManagerCommand.Stop =>
            context.log.info("Stopping AudioStreamFactoryManager actor.")
            Behaviors.stopped

      handleCommand(CompositeAsyncAudioStreamFactory(initialFactories))
end AudioStreamFactoryManager

/**
  * A helper class to manage dynamically added and removed factories for audio
  * streams.
  *
  * The audio platform declares dynamic dependencies to components providing
  * [[AsyncAudioStreamFactory]] implementations. Such services can therefore be 
  * added and removed at any time by the OSGi runtime. The class uses an actor
  * to manage the factory instances and provide an implementation backed by the
  * dynamic list of instances. There is, however, no guarantee when factories
  * arrive - this can happen before or after the actor system becomes available
  * which is needed to create the actor instance. Therefore, buffering of 
  * factories needs to be implemented.
  *
  * The methods to add or remove factory objects are exclusively called from
  * the OSGi thread. Because of this, no special synchronization is needed.
  */
class AudioStreamFactoryManager:

  import AudioStreamFactoryManager.*

  /**
    * The list of factories that have already been added before the actor 
    * system was available and the actor could be created.
    */
  private var initialFactories = DefaultInitialFactories

  /** Holds the optional reference to the management actor. */
  private var managementActor: Option[ActorRef[FactoryManagerCommand]] = None

  /**
    * Adds the given [[AudioStreamFactory]] to this manager.
    *
    * @param factory the factory to add
    */
  def addFactory(factory: AsyncAudioStreamFactory): Unit =
    managementActor match
      case Some(actor) =>
        actor ! FactoryManagerCommand.AddFactory(factory)
      case None =>
        initialFactories = factory :: initialFactories

  /**
    * Removes the given [[AudioStreamFactory]] from this manager.
    *
    * @param factory the factory to remove
    */
  def removeFactory(factory: AsyncAudioStreamFactory): Unit =
    managementActor match
      case Some(actor) =>
        actor ! FactoryManagerCommand.RemoveFactory(factory)
      case None =>
        initialFactories = initialFactories.filterNot(_ eq factory)

  /**
    * Returns an [[AsyncAudioStreamFactory]] that is backed by the dynamically 
    * added factories. This factory can be used to safely access all factories
    * provided by the other methods of this class.
    *
    * @param actorFactory the actor factory
    * @return the [[AsyncAudioStreamFactory]] managed by this instance
    */
  def createManagedFactory(actorFactory: ActorFactory): AsyncAudioStreamFactory =
    val actor = actorFactory.createTypedActor(createActor(initialFactories), ActorName)
    managementActor = Some(actor)
    createManagedFactoryForActor(actor)

  /**
    * Shuts down this instance and frees all resources. This must be called
    * when an instance is no longer needed.
    */
  def shutdown(): Unit =
    managementActor.foreach(_ ! FactoryManagerCommand.Stop)
    managementActor = None
    initialFactories = DefaultInitialFactories

  /**
    * Returns a new [[AsyncAudioStreamFactory]] object that uses the provided 
    * actor reference to obtain audio streams.
    *
    * @param actor the management actor
    * @return the [[AsyncAudioStreamFactory]] based on this actor
    */
  private def createManagedFactoryForActor(actor: ActorRef[FactoryManagerCommand]): AsyncAudioStreamFactory =
    (uri: String) =>
      val promiseResult = Promise[AudioStreamPlaybackData]()
      actor ! FactoryManagerCommand.CreateAudioStream(uri, promiseResult)
      promiseResult.future
      