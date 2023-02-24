/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.control

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.{actor => classic}
import de.oliver_heger.linedj.io.CloseSupportTyped
import de.oliver_heger.linedj.player.engine.actors.{LineWriterActor, PlaybackActor, PlaybackContextFactoryActor}
import de.oliver_heger.linedj.player.engine.radio.stream.RadioDataSourceActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioPlaybackContextCreationFailedEvent, RadioPlaybackErrorEvent, RadioSource, RadioSourceErrorEvent}
import de.oliver_heger.linedj.player.engine._

import scala.concurrent.duration._

/**
  * An actor implementation to manage radio sources whose playback caused an
  * error.
  *
  * In case of a playback error, a radio source is marked as excluded. Then it
  * is checked in configurable intervals whether playback is possible again.
  * That way temporary interruptions can be handled.
  *
  * In addition to the main actor controlling the error checks, this module
  * contains a number of helper actors and classes. For each failed radio
  * source, a dedicated actor is created to perform the periodic checks. The
  * checks themselves are rather complex, since every time all relevant actors
  * for audio playback need to be created to test whether a playback context
  * can be created, and audio data is actually processed.
  */
object ErrorStateActor {
  /**
    * An internal data class to hold the actors required for playing audio
    * data.
    *
    * @param sourceActor the actor providing the source to be played
    * @param playActor   the playback actor
    */
  private[control] case class PlaybackActorsFactoryResult(sourceActor: classic.ActorRef,
                                                          playActor: classic.ActorRef)

  /**
    * An internal trait that supports the creation of the actors required for
    * playing audio data. An instance is used to setup the infrastructure to
    * test a radio source.
    */
  private[control] trait PlaybackActorsFactory {
    /**
      * Returns an object with the actors required for playing audio data. This
      * function creates a dummy line writer actor, a source actor, and a
      * playback actor and connects them. Note that for this purpose a
      * dedicated [[ActorCreator]] is used, not the one contained in the
      * [[PlayerConfig]].
      *
      * @param namePrefix       the prefix for generating actor names
      * @param playerEventActor the event actor for player events
      * @param radioEventActor  the event actor for radio events
      * @param factoryActor     the playback context factory actor
      * @param config           the configuration for the audio player
      * @return the object with the actor references
      */
    def createPlaybackActors(namePrefix: String,
                             playerEventActor: ActorRef[PlayerEvent],
                             radioEventActor: ActorRef[RadioEvent],
                             factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                             config: PlayerConfig,
                             creator: ActorCreator): PlaybackActorsFactoryResult
  }

  /**
    * A default implementation of [[PlaybackActorsFactory]]. It uses the
    * actor creator in the provided configuration to create the required actor
    * instances.
    */
  private[control] val playbackActorsFactory = new PlaybackActorsFactory {
    override def createPlaybackActors(namePrefix: String,
                                      playerEventActor: ActorRef[PlayerEvent],
                                      radioEventActor: ActorRef[RadioEvent],
                                      factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                                      config: PlayerConfig,
                                      creator: ActorCreator): PlaybackActorsFactoryResult = {
      val lineWriter = creator.createActor(dummyLineWriterActor(), namePrefix + "LineWriter", None)
      val sourceActor = creator.createActor(RadioDataSourceActor(config, radioEventActor),
        namePrefix + "SourceActor")
      val playActor = creator.createActor(PlaybackActor(config, sourceActor, lineWriter,
        playerEventActor, factoryActor), namePrefix + "PlaybackActor")
      PlaybackActorsFactoryResult(sourceActor, playActor)
    }
  }

  /**
    * A message processed by the radio source check actor that indicates that
    * the radio source in question is now functional again.
    */
  private[control] case class RadioSourceCheckSuccessful()

  /**
    * The base command trait of an internal actor that checks whether playback
    * of a specific radio source is currently possible.
    */
  private[control] sealed trait CheckPlaybackCommand

  /**
    * A command received by the check playback actor that tells it to stop the
    * ongoing test due to a timeout. The test is considered as failed.
    */
  private[control] case object CheckTimeout extends CheckPlaybackCommand

  /**
    * A command received by the check playback actor that tells it that the
    * ongoing close operation is complete.
    */
  private[control] case object CloseComplete extends CheckPlaybackCommand

  /**
    * A command received by the check playback actor informing it that one of
    * the actors for playing audio data died. This should cause the check to
    * fail.
    */
  private[control] case object PlaybackActorDied extends CheckPlaybackCommand

  /**
    * A command received by the check playback actor when an event was fired
    * during playback of the radio source to check. This event determines the
    * result of the check.
    *
    * @param event the event
    */
  private[control] case class PlaybackEventReceived(event: AnyRef) extends CheckPlaybackCommand

  /**
    * A trait defining a factory function for creating an internal actor
    * instance to check whether playback of a specific radio source is
    * currently possible. The actor sets up the infrastructure to play this
    * source. If this is successful, it sends a corresponding success message
    * to the receiver actor. In all cases, it stops itself after the test.
    */
  private[control] trait CheckPlaybackActorFactory {
    /**
      * Returns a ''Behavior'' to create an instance of the actor to check a
      * specific radio source.
      *
      * @param radioSource           the radio source to be checked
      * @param namePrefix            the prefix to generate actor names
      * @param factoryActor          the playback context factory actor
      * @param config                the configuration for the audio player
      * @param playbackActorsFactory factory for creating playback actors
      * @return the ''Behavior'' for the check playback actor
      */
    def apply(receiver: ActorRef[RadioSourceCheckSuccessful],
              radioSource: RadioSource,
              namePrefix: String,
              factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
              config: PlayerConfig,
              playbackActorsFactory: PlaybackActorsFactory = ErrorStateActor.playbackActorsFactory):
    Behavior[CheckPlaybackCommand]
  }

  /**
    * A default [[CheckPlaybackActorFactory]] implementation that can be used
    * to create instances of the check playback actor.
    */
  private[control] val checkPlaybackBehavior: CheckPlaybackActorFactory =
    (receiver: ActorRef[RadioSourceCheckSuccessful],
     radioSource: RadioSource,
     namePrefix: String,
     factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
     config: PlayerConfig,
     playbackActorsFactory: PlaybackActorsFactory) => Behaviors.setup { context =>
      context.log.info("Checking error state of radio source {}.", radioSource)
      val playerEventAdapter = context.messageAdapter[PlayerEvent] { event => PlaybackEventReceived(event) }
      val radioEventAdapter = context.messageAdapter[RadioEvent] { event => PlaybackEventReceived(event) }

      val playbackActors = playbackActorsFactory.createPlaybackActors(namePrefix,
        playerEventAdapter,
        radioEventAdapter,
        factoryActor,
        config,
        childActorCreator(context))
      playbackActors.sourceActor ! radioSource
      playbackActors.playActor ! PlaybackActor.StartPlayback
      context.watchWith(playbackActors.sourceActor, PlaybackActorDied)
      context.watchWith(playbackActors.playActor, PlaybackActorDied)

      def checking(): Behavior[CheckPlaybackCommand] =
        Behaviors.receiveMessagePartial {
          case CheckTimeout =>
            closeAndStop()

          case PlaybackActorDied =>
            closeAndStop()

          case PlaybackEventReceived(event) =>
            val success = isSuccessEvent(event)
            val error = isErrorEvent(event)
            if (success || error) {
              if (success) receiver ! RadioSourceCheckSuccessful()
              closeAndStop()
            } else Behaviors.same
        }

      def closing(): Behavior[CheckPlaybackCommand] =
        Behaviors.receiveMessagePartial {
          case CloseComplete =>
            Behaviors.stopped

          case m =>
            context.log.info("Ignoring message {} while waiting for CloseAck.", m)
            Behaviors.same
        }

      def closeAndStop(): Behavior[CheckPlaybackCommand] = {
        CloseSupportTyped.triggerClose(context, context.self, CloseComplete,
          List(playbackActors.sourceActor, playbackActors.playActor))
        closing()
      }

      checking()
    }

  /**
    * Returns the behavior for a dummy line writer actor. This behavior just
    * simulates writing, so that playback progress events are generated.
    *
    * @return the behavior of a dummy line writer actor
    */
  private def dummyLineWriterActor(): Behavior[LineWriterActor.LineWriterCommand] =
    Behaviors.receiveMessagePartial {
      case LineWriterActor.WriteAudioData(_, _, replyTo) =>
        replyTo ! LineWriterActor.AudioDataWritten(chunkLength = 1024, duration = 1.second)
        Behaviors.same

      case LineWriterActor.DrainLine(_, replayTo) =>
        replayTo ! LineWriterActor.LineDrained
        Behaviors.same
    }

  /**
    * Creates an [[ActorCreator]] that uses the given context to create child
    * actors of the owning actor.
    *
    * @param context the context
    * @tparam M the type of the actor
    * @return the [[ActorCreator]] using this context
    */
  private def childActorCreator[M](context: ActorContext[M]): ActorCreator =
    new ActorCreator {
      override def createActor[T](behavior: Behavior[T],
                                  name: String,
                                  optStopCommand: Option[T],
                                  props: Props): ActorRef[T] = context.spawn(behavior, name, props)

      override def createActor(props: classic.Props, name: String): classic.ActorRef = context.actorOf(props, name)
    }

  /**
    * Checks whether the given event indicates a successful playback.
    *
    * @param event the event in question
    * @return a flag whether this is a success event
    */
  private def isSuccessEvent(event: AnyRef): Boolean =
    event match {
      case _: PlaybackProgressEvent => true
      case _ => false
    }

  /**
    * Checks whether the given event indicates a playback error.
    *
    * @param event the event in question
    * @return a flag whether this is an error event
    */
  private def isErrorEvent(event: AnyRef): Boolean =
    event match {
      case _: PlaybackContextCreationFailedEvent => true
      case _: PlaybackErrorEvent => true
      case _: RadioSourceErrorEvent => true
      case _: RadioPlaybackErrorEvent => true
      case _: RadioPlaybackContextCreationFailedEvent => true
      case _ => false
    }
}
