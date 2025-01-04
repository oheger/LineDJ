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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.player.engine.AsyncAudioStreamFactory.UnsupportedUriException
import de.oliver_heger.linedj.player.engine.AudioStreamFactory.AudioStreamPlaybackData
import de.oliver_heger.linedj.player.engine.actors.DynamicAudioStreamFactory.{AddFactory, FactoryManagementCommand, GetPlaybackData, GetPlaybackDataResult, RemoveFactory, Stop}
import de.oliver_heger.linedj.player.engine.{AsyncAudioStreamFactory, AudioStreamFactory, CompositeAudioStreamFactory}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

object DynamicAudioStreamFactory:
  /**
    * A base trait for commands handled by the actor to manage audio stream
    * factories.
    */
  private sealed trait FactoryManagementCommand

  /**
    * A command handled by the stream factory manager actor that allows adding
    * a new factory.
    *
    * @param factory the factory to be added
    */
  private case class AddFactory(factory: AudioStreamFactory) extends FactoryManagementCommand

  /**
    * A command handled by the stream factory manager actor that can be used to
    * remove factories.
    *
    * @param factory the factory to be removed
    */
  private case class RemoveFactory(factory: AudioStreamFactory) extends FactoryManagementCommand

  /**
    * A command handled by the stream factory manager actor that can be used to
    * query the managed factories for the URI of an audio stream.
    *
    * @param audioUri the URI of the audio stream
    * @param replyTo  the actor to send the reply to
    */
  private case class GetPlaybackData(audioUri: String,
                                     replyTo: ActorRef[GetPlaybackDataResult]) extends FactoryManagementCommand

  /**
    * A data class to represent the response to a [[GetPlaybackData]] command.
    * An instance holds an [[Option]] with the [[AudioStreamPlaybackData]]
    * created by one of the managed factories.
    *
    * @param optData the optional playback data for the requested URI
    */
  private case class GetPlaybackDataResult(optData: Option[AudioStreamPlaybackData])

  /**
    * A command for stopping a stream factory manager actor.
    */
  private case object Stop extends FactoryManagementCommand

  /** The timeout for requests to the factory manager actor. */
  private given queryTimeout: Timeout = Timeout(10.seconds)

  /**
    * Creates a new instance of [[DynamicAudioStreamFactory]]. The new instance
    * does not contain any child [[AudioStreamFactory]] objects yet.
    *
    * @param system the classic actor system
    * @return the newly created instance
    */
  def apply()(using system: classic.ActorSystem): DynamicAudioStreamFactory =
    given ActorSystem[Nothing] = system.toTyped

    val managerActor = system.spawn(
      handleFactoryManagementCommand(new CompositeAudioStreamFactory(Nil)),
      "audioStreamFactoryManagerActor"
    )
    new DynamicAudioStreamFactory(managerActor)

  /**
    * The command handler function for the stream factory manager actor.
    *
    * @param compositeFactory the managed [[CompositeAudioStreamFactory]]
    * @return the behavior of the actor
    */
  private def handleFactoryManagementCommand(compositeFactory: CompositeAudioStreamFactory):
  Behavior[FactoryManagementCommand] =
    Behaviors.receive {
      case (ctx, AddFactory(factory)) =>
        ctx.log.info("Adding audio stream factory {}.", factory.getClass.getSimpleName)
        handleFactoryManagementCommand(new CompositeAudioStreamFactory(factory :: compositeFactory.factories))

      case (ctx, RemoveFactory(factory)) =>
        val nextFactories = compositeFactory.factories.filterNot(_ == factory)
        ctx.log.info("Removing audio stream factory {}. {} remain.", factory.getClass.getSimpleName,
          nextFactories.size)
        handleFactoryManagementCommand(new CompositeAudioStreamFactory(nextFactories))

      case (ctx, GetPlaybackData(audioUri, replyTo)) =>
        ctx.log.info("Request for playback data for URI '{}'.", audioUri)
        replyTo ! GetPlaybackDataResult(compositeFactory.playbackDataFor(audioUri))
        Behaviors.same

      case (ctx, Stop) =>
        ctx.log.info("Stopping stream factory manager actor.")
        Behaviors.stopped
    }
end DynamicAudioStreamFactory

/**
  * A class that provides an [[AsyncAudioStreamFactory]] implementation based
  * on an actor that manages a list of audio stream factories.
  *
  * This implementation can be used to deal with audio stream factories that
  * are added or removed dynamically. It offers methods to register new
  * factories or to remove factories that are no longer available. The
  * ''playbackDataForAsync()'' function delegates to the currently known
  * factories.
  *
  * Because this implementation is based on an actor, it is fully thread-safe.
  *
  * @param factoryManager the actor for managing the stream factories
  * @param system         the typed actor system
  */
class DynamicAudioStreamFactory private(factoryManager: ActorRef[FactoryManagementCommand])
                                       (using system: ActorSystem[_])
  extends AsyncAudioStreamFactory:
  /**
    * @inheritdoc This implementation delegates the request to an actor
    *             instance that manages the available factories.
    */
  override def playbackDataForAsync(uri: String): Future[AudioStreamFactory.AudioStreamPlaybackData] =
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
    import DynamicAudioStreamFactory.queryTimeout
    factoryManager.ask[GetPlaybackDataResult] { ref =>
      GetPlaybackData(uri, ref)
    }.map(_.optData.getOrElse(throw new UnsupportedUriException(uri)))

  /**
    * Adds the given child [[AudioStreamFactory]] to this dynamic factory. It
    * is queried for URIs passed to the
    * [[DynamicAudioStreamFactory.playbackDataForAsync]] function.
    *
    * @param factory the child factory to be added
    */
  def addAudioStreamFactory(factory: AudioStreamFactory): Unit =
    factoryManager ! AddFactory(factory)

  def removeAudioStreamFactory(factory: AudioStreamFactory): Unit =
    factoryManager ! RemoveFactory(factory)

  /**
    * Shuts down this audio stream factory and releases all resources. After
    * calling this method, this instance can no longer be used.
    */
  def shutdown(): Unit =
    factoryManager ! Stop

  /** The execution context for dealing with futures. */
  private given ec: ExecutionContext = system.executionContext
