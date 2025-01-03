/*
 * Copyright 2015-2024 The Developers Team.
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

import com.github.cloudfiles.core.http.factory.Spawner
import de.oliver_heger.linedj.player.engine.*
import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.radio.config.RadioPlayerConfig
import de.oliver_heger.linedj.player.engine.radio.stream.{RadioStreamHandle, RadioStreamHandleManagerActor}
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, LineWriterStage}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props, Scheduler}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}
import org.apache.pekko.{NotUsed, actor as classic}

import scala.collection.immutable.Queue
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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
object ErrorStateActor:
  /**
    * A prefix that is used to generate names for child actors. It is appended
    * by a counter that is incremented for each source in error state.
    */
  final val ActorNamePrefix = "errorSource_"

  private val SchedulerActorName = "radioErrorStateSchedulerActor"

  /**
    * The base trait for the commands supported by the error state actor.
    */
  sealed trait ErrorStateCommand

  /**
    * A command for the error state actor telling it to send information about
    * sources in error state to the given receiver.
    *
    * @param receiver the actor to receive the response
    */
  case class GetSourcesInErrorState(receiver: ActorRef[SourcesInErrorState]) extends ErrorStateCommand

  /**
    * An internal command for the error state actor telling it to process the
    * given [[RadioEvent]]. If this is an error event, another radio source may
    * have to be added to the error state.
    *
    * @param event the event
    */
  private case class HandleEvent(event: RadioEvent) extends ErrorStateCommand

  /**
    * An internal command for the error state actor that reports that the given
    * radio source has been checked successfully. It can therefore be removed
    * again from the error state.
    *
    * @param source the affected radio source
    */
  private case class SourceAvailableAgain(source: RadioSource) extends ErrorStateCommand

  /**
    * A message with information about the radio sources that are currently in
    * error state. A message of this type is sent in response of a
    * [[GetSourcesInErrorState]] command.
    *
    * @param errorSources a set with sources in error state
    */
  case class SourcesInErrorState(errorSources: Set[RadioSource])

  /**
    * A trait defining a factory function for creating new instances of the
    * error state actor.
    */
  trait Factory:
    /**
      * Returns the ''Behavior'' to create a new instance of the error state
      * actor.
      *
      * @param config                   the config for the radio player
      * @param enabledStateActor        the actor that manages the enabled
      *                                 state of radio sources
      * @param streamFactory            the factory for creating audio streams
      * @param scheduledInvocationActor the actor for scheduled invocations
      * @param eventActor               the event manager actor
      * @param handleManager            the actor managing radio stream handles
      * @param schedulerFactory         the factory to create a scheduler actor
      * @param checkSourceActorFactory  the factory to create a source check
      *                                 actor
      * @param optSpawner               an optional ''Spawner''
      * @return the ''Behavior'' for the new actor instance
      */
    def apply(config: RadioPlayerConfig,
              enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
              streamFactory: AsyncAudioStreamFactory,
              scheduledInvocationActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
              eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
              handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
              schedulerFactory: CheckSchedulerActorFactory = checkSchedulerBehavior,
              checkSourceActorFactory: CheckSourceActorFactory = checkSourceBehavior,
              optSpawner: Option[Spawner] = None): Behavior[ErrorStateCommand]

  /**
    * A default [[Factory]] implementation to create instances of the error
    * state actor.
    */
  final val errorStateBehavior: Factory = (config: RadioPlayerConfig,
                                           enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
                                           streamFactory: AsyncAudioStreamFactory,
                                           scheduledInvocationActor: ActorRef[
                                             ScheduledInvocationActor.ScheduledInvocationCommand],
                                           eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
                                           handleManager:
                                           ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
                                           schedulerFactory: CheckSchedulerActorFactory,
                                           checkSourceActorFactory: CheckSourceActorFactory,
                                           optSpawner: Option[Spawner]) => {
    val context = ErrorStateContext(config,
      enabledStateActor,
      streamFactory,
      scheduledInvocationActor,
      eventActor,
      handleManager,
      schedulerFactory,
      checkSourceActorFactory,
      optSpawner)
    handleErrorStateCommand(context)
  }
  
  /**
    * The base command trait of an internal actor that periodically checks the
    * error state of a specific radio source.
    */
  private[control] sealed trait CheckRadioSourceCommand

  /**
    * A command for the radio source check actor telling it to run a check of
    * the associated radio source now. The command includes a reference to the
    * scheduled invocation actor, which is needed to implement a proper timeout
    * handling.
    *
    * @param scheduleActor the scheduled invocation actor
    */
  private[control] case class RunRadioSourceCheck(scheduleActor:
                                                  ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand])
    extends CheckRadioSourceCommand

  /**
    * A message processed by the radio source check actor that indicates that
    * the radio source in question is now functional again.
    */
  private[control] case class RadioSourceCheckSuccessful() extends CheckRadioSourceCommand

  /**
    * A message processed by the radio source check actor that is generated
    * when the check playback actor stops. This does not necessarily mean a
    * failed test, since the actor always stops itself at the end.
    */
  private case object CheckPlaybackActorStopped extends CheckRadioSourceCommand

  /**
    * A command for the radio source check actor telling it that the timeout
    * for the current check has been reached. If the check is still ongoing, it
    * should be terminated now and considered as failed.
    *
    * @param checkPlaybackActor the actor that checks playback
    * @param count              the number of checks already done; this is used
    *                           to detect outdated timeout messages
    */
  private case class RadioSourceCheckTimeout(checkPlaybackActor: ActorRef[CheckPlaybackCommand],
                                             count: Int) extends CheckRadioSourceCommand

  /**
    * A trait defining a factory function for creating an internal actor
    * instance that checks the error state of a specific radio source
    * periodically. This is done by attempting a playback of this source using
    * a check playback actor. According to the [[RadioPlayerConfig]], the check
    * intervals are incremented if the failure persists, and another check is
    * scheduled accordingly. If a check was successful, the actor stops itself.
    * This is the signal that the radio source is valid again.
    */
  private[control] trait CheckSourceActorFactory:
    /**
      * Returns a ''Behavior'' of a new actor instance to check the error state
      * of a specific radio source.
      *
      * @param config               the radio player configuration
      * @param source               the affected radio source
      * @param namePrefix           a prefix to generate names for child actors
      * @param streamFactory        the factory for creating audio streams
      * @param scheduler            the scheduler actor
      * @param handleManager        the actor managing radio stream handles
      * @param checkPlaybackFactory the factory to create a check playback
      *                             actor
      * @param optSpawner           an optional [[Spawner]]
      * @return the ''Behavior'' for the new instance
      */
    def apply(config: RadioPlayerConfig,
              source: RadioSource,
              namePrefix: String,
              streamFactory: AsyncAudioStreamFactory,
              scheduler: ActorRef[ScheduleCheckCommand],
              handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
              checkPlaybackFactory: CheckPlaybackActorFactory = checkPlaybackBehavior,
              optSpawner: Option[Spawner] = None): Behavior[CheckRadioSourceCommand]

  /**
    * A default [[CheckSourceActorFactory]] implementation that can be used to
    * create instances of the check radio source actor.
    */
  private[control] val checkSourceBehavior = new CheckSourceActorFactory:
    override def apply(config: RadioPlayerConfig,
                       source: RadioSource,
                       namePrefix: String,
                       streamFactory: AsyncAudioStreamFactory,
                       scheduler: ActorRef[ScheduleCheckCommand],
                       handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
                       checkPlaybackFactory: CheckPlaybackActorFactory,
                       optSpawner: Option[Spawner]): Behavior[CheckRadioSourceCommand] =
      val ctx = RadioSourceCheckContext(config = config,
        source = source,
        namePrefix = namePrefix,
        streamFactory = streamFactory,
        scheduler = scheduler,
        handleManager = handleManager,
        checkPlaybackFactory = checkPlaybackFactory,
        optSpawner = optSpawner,
        retryDelay = config.retryFailedSource)
      handleRadioSourceCheckCommand(ctx)

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
    * A command received by the check playback actor that notifies it about the
    * arrival of the radio stream handle.
    *
    * @param triedHandleResult the result for the handle and the attached audio
    *                          source
    */
  private[control] case class RadioStreamHandleReceived(triedHandleResult:
                                                        Try[(RadioStreamHandle, Source[ByteString, NotUsed])])
    extends CheckPlaybackCommand

  /**
    * A command received by the check playback actor when the result of the
    * audio stream sink is available. Then it is clear whether the stream could
    * be played or not.
    *
    * @param result the result from the sink of the audio stream
    */
  private[control] case class PlaybackSinkResultReceived(result: Try[Unit]) extends CheckPlaybackCommand

  /**
    * A trait defining a factory function for creating an internal actor
    * instance to check whether playback of a specific radio source is
    * currently possible. The actor sets up the infrastructure to play this
    * source. If this is successful, it sends a corresponding success message
    * to the receiver actor. In all cases, it stops itself after the test.
    */
  private[control] trait CheckPlaybackActorFactory:
    /**
      * Returns a ''Behavior'' to create an instance of the actor to check a
      * specific radio source.
      *
      * @param radioSource   the radio source to be checked
      * @param namePrefix    the prefix to generate actor names
      * @param streamFactory the factory for creating audio streams
      * @param config        the configuration for the audio player
      * @param handleManager the actor managing radio stream handles
      * @return the ''Behavior'' for the check playback actor
      */
    def apply(receiver: ActorRef[RadioSourceCheckSuccessful],
              radioSource: RadioSource,
              namePrefix: String,
              streamFactory: AsyncAudioStreamFactory,
              config: PlayerConfig,
              handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand]):
    Behavior[CheckPlaybackCommand]

  /**
    * A default [[CheckPlaybackActorFactory]] implementation that can be used
    * to create instances of the check playback actor.
    */
  private[control] val checkPlaybackBehavior: CheckPlaybackActorFactory =
    (receiver: ActorRef[RadioSourceCheckSuccessful],
     radioSource: RadioSource,
     namePrefix: String,
     streamFactory: AsyncAudioStreamFactory,
     config: PlayerConfig,
     handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand]) =>
      Behaviors.setup { context =>
        val logPrefix = s"[ERROR CHECK '${radioSource.uri}']"
        context.log.info("{} Starting check.", logPrefix)

        given Scheduler = context.system.scheduler

        given classic.ActorSystem = context.system.toClassic

        given ExecutionContext = context.executionContext

        // Set a rather long timeout to prevent that a stream is created which is then not released.
        given Timeout(1.hour)
        val futHandle = handleManager.ask[RadioStreamHandleManagerActor.GetStreamHandleResponse] { ref =>
          val params = RadioStreamHandleManagerActor.GetStreamHandleParameters(radioSource,
            s"${namePrefix}_errorCheck")
          RadioStreamHandleManagerActor.GetStreamHandle(params, ref)
        }
        (for
          handleResponse <- futHandle
          handle <- Future.fromTry(handleResponse.triedStreamHandle)
          source <- handle.attachAudioSinkOrCancel()
        yield (handle, source)).onComplete { triedResult =>
          context.self ! RadioStreamHandleReceived(triedResult)
        }

        def init(isTimeout: Boolean): Behavior[CheckPlaybackCommand] =
          Behaviors.receiveMessagePartial:
            case CheckTimeout =>
              context.log.info("{} Timeout before stream handle was received.", logPrefix)
              init(isTimeout = true)

            case RadioStreamHandleReceived(triedHandleResult) =>
              triedHandleResult match
                case Failure(exception) =>
                  context.log.info("{} Could not obtain stream handle. Still in error state.", logPrefix, exception)
                  Behaviors.stopped
                case Success(handleResult) if isTimeout =>
                  closeAndStop(handleResult._1)
                case Success(handleResult) =>
                  context.log.info("{} Received stream handle. Trying audio playback.", logPrefix)
                  val resolverFunc: AudioStreamPlayerStage.SourceResolverFunc[Source[ByteString, NotUsed]] = src =>
                    Future.successful(AudioStreamPlayerStage.AudioStreamSource(radioSource.uriWithExtension, src))
                  val sink = Sink.head[LineWriterStage.PlayedAudioChunk]
                  val playerStageConfig = AudioStreamPlayerStage.AudioStreamPlayerConfig(
                    sourceResolverFunc = resolverFunc,
                    sinkProviderFunc = _ => sink,
                    audioStreamFactory = streamFactory,
                    optPauseActor = None,
                    inMemoryBufferSize = config.inMemoryBufferSize,
                    optStreamFactoryLimit = Some(config.playbackContextLimit),
                    optLineCreatorFunc = None
                  )
                  val playerStage = AudioStreamPlayerStage(playerStageConfig)
                  val futAudioStream = Source.single(handleResult._2).via(playerStage).runWith(sink)
                  futAudioStream.map(_ => ()).onComplete { triedResult =>
                    context.self ! PlaybackSinkResultReceived(triedResult)
                  }
                  checking(handleResult._1)

        def checking(handle: RadioStreamHandle): Behavior[CheckPlaybackCommand] =
          Behaviors.receiveMessagePartial:
            case CheckTimeout =>
              closeAndStop(handle)

            case PlaybackSinkResultReceived(result) =>
              result.foreach { _ =>
                context.log.info("{} Playback successful. Leaving error state.", logPrefix)
                receiver ! RadioSourceCheckSuccessful()
              }
              closeAndStop(handle)

        def closeAndStop(handle: RadioStreamHandle): Behavior[CheckPlaybackCommand] =
          context.log.info("{} Terminating check actor.", logPrefix)
          handle.cancelStream()
          Behaviors.stopped

        init(isTimeout = false)
      }

  /**
    * The base command trait of an internal actor that handles interaction with
    * a [[ScheduledInvocationActor]] to trigger checks for radio sources in
    * error state.
    */
  private[control] sealed trait ScheduleCheckCommand

  /**
    * A command for the check scheduler actor that tells it to schedule a check
    * for the given actor after a specific delay.
    *
    * @param checkActor the actor to execute the check
    * @param delay      the delay
    */
  private[control] case class AddScheduledCheck(checkActor: ActorRef[CheckRadioSourceCommand],
                                                delay: FiniteDuration) extends ScheduleCheckCommand

  /**
    * An internal command for the check scheduler actor that tells it to
    * trigger the given actor to run a check now.
    */
  private case class TriggerCheck(checkActor: ActorRef[CheckRadioSourceCommand]) extends ScheduleCheckCommand

  /**
    * An internal command for the check scheduler actor that indicates that the
    * actor doing the current check has died. In this case, processing can
    * continue with the next pending check if any.
    *
    * @param checkActor the actor that died
    */
  private case class CheckActorDied(checkActor: ActorRef[CheckRadioSourceCommand]) extends ScheduleCheckCommand

  /**
    * A trait defining a factory for the ''Behavior'' of an internal actor
    * that implements scheduling logic to check radio sources in error state
    * periodically. The actor uses [[ScheduledInvocationActor]] to receive
    * messages periodically. It makes sure that only a single check is ongoing
    * at a specific point in time. The actor executing the check must then
    * either schedule another check or stop itself.
    */
  private[control] trait CheckSchedulerActorFactory:
    def apply(scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand]):
    Behavior[ScheduleCheckCommand]

  /**
    * A default [[CheckSchedulerActorFactory]] implementation for creating the
    * ''Behavior'' for an instance of the check scheduler actor.
    */
  private[control] val checkSchedulerBehavior: CheckSchedulerActorFactory =
    (scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand]) =>
      handleSchedulerCommands(scheduleActor, None, Queue.empty)

  /**
    * Returns the behavior for a dummy line writer actor. This behavior just
    * simulates writing, so that playback progress events are generated.
    *
    * @return the behavior of a dummy line writer actor
    */
  private def dummyLineWriterActor(): Behavior[LineWriterActor.LineWriterCommand] =
    Behaviors.receiveMessagePartial:
      case LineWriterActor.WriteAudioData(_, _, replyTo) =>
        replyTo ! LineWriterActor.AudioDataWritten(chunkLength = 1024, duration = 1.second)
        Behaviors.same

      case LineWriterActor.DrainLine(_, replayTo) =>
        replayTo ! LineWriterActor.LineDrained
        Behaviors.same

  /**
    * Creates an [[ActorCreator]] that uses the given context to create child
    * actors of the owning actor.
    *
    * @param context the context
    * @tparam M the type of the actor
    * @return the [[ActorCreator]] using this context
    */
  private def childActorCreator[M](context: ActorContext[M]): ActorCreator =
    new ActorCreator:
      override def createActor[T](behavior: Behavior[T],
                                  name: String,
                                  optStopCommand: Option[T],
                                  props: Props): ActorRef[T] = context.spawn(behavior, name, props)

      override def createClassicActor(props: classic.Props,
                                      name: String,
                                      optStopCommand: Option[Any]): classic.ActorRef =
        context.actorOf(props, name)

  /**
    * Checks whether the given event indicates a successful playback.
    *
    * @param event the event in question
    * @return a flag whether this is a success event
    */
  private def isSuccessEvent(event: AnyRef): Boolean =
    event match
      case _: PlaybackProgressEvent => true
      case _ => false

  /**
    * Checks whether the given event indicates a playback error.
    *
    * @param event the event in question
    * @return a flag whether this is an error event
    */
  private def isErrorEvent(event: AnyRef): Boolean =
    event match
      case _: PlaybackContextCreationFailedEvent => true
      case _: PlaybackErrorEvent => true
      case _: RadioSourceErrorEvent => true
      case _: RadioPlaybackErrorEvent => true
      case _: RadioPlaybackContextCreationFailedEvent => true
      case _ => false

  /**
    * Checks whether the given event indicates an error when playing a radio
    * source. If so, the affected [[RadioSource]] is returned. Otherwise,
    * result is ''None''.
    *
    * @param event the event
    * @return an ''Option'' with the source affected by an error
    */
  private def extractErrorSource(event: RadioEvent): Option[RadioSource] =
    event match
      case RadioPlaybackErrorEvent(source, _) => Some(source)
      case RadioPlaybackContextCreationFailedEvent(source, _) => Some(source)
      case RadioSourceErrorEvent(source, _) => Some(source)
      case _ => None

  /**
    * The message handler function of the check scheduler actor.
    *
    * @param scheduleActor the actor for scheduled invocations
    * @param inProgress    an option with the check currently in progress
    * @param pending       a queue with pending checks
    * @return the behavior of this actor
    */
  private def handleSchedulerCommands(scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                                      inProgress: Option[ActorRef[CheckRadioSourceCommand]],
                                      pending: Queue[ActorRef[CheckRadioSourceCommand]]):
  Behavior[ScheduleCheckCommand] = Behaviors.receive:
    case (ctx, AddScheduledCheck(checkActor, delay)) =>
      val command = ScheduledInvocationActor.typedInvocationCommand(delay, ctx.self, TriggerCheck(checkActor))
      scheduleActor ! command
      updateForCheckCompleted(ctx, scheduleActor, inProgress, pending, checkActor)

    case (ctx, TriggerCheck(checkActor)) =>
      inProgress match
        case Some(_) =>
          handleSchedulerCommands(scheduleActor, inProgress, pending :+ checkActor)
        case None =>
          triggerSourceCheck(ctx, scheduleActor, checkActor, pending)

    case (ctx, CheckActorDied(checkActor)) =>
      updateForCheckCompleted(ctx, scheduleActor, inProgress, pending, checkActor)

  /**
    * Invokes the given receiver actor to trigger a check on its associated
    * radio source. The state of the check scheduler actor is updated, so this
    * becomes the current check.
    *
    * @param context       the actor context
    * @param scheduleActor the scheduled invocation actor
    * @param receiver      the actor to do the next check
    * @param pending       the queue of pending checks
    * @return the updated behavior
    */
  private def triggerSourceCheck(context: ActorContext[ScheduleCheckCommand],
                                 scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                                 receiver: ActorRef[CheckRadioSourceCommand],
                                 pending: Queue[ActorRef[CheckRadioSourceCommand]]):
  Behavior[ScheduleCheckCommand] =
    receiver ! RunRadioSourceCheck(scheduleActor)
    context.watchWith(receiver, CheckActorDied(receiver))
    handleSchedulerCommands(scheduleActor, Some(receiver), pending)

  /**
    * Updates the state of the check scheduler actor for a potentially
    * completed check. If the given actor is the current one, the check is
    * considered done and the next one can start if available.
    *
    * @param context       the actor context
    * @param scheduleActor the scheduled invocation actor
    * @param inProgress    an option with the check currently in progress
    * @param checkActor    the affected check actor
    * @param pending       the queue of pending checks
    * @return the updated behavior
    */
  private def updateForCheckCompleted(context: ActorContext[ScheduleCheckCommand],
                                      scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                                      inProgress: Option[ActorRef[CheckRadioSourceCommand]],
                                      pending: Queue[ActorRef[CheckRadioSourceCommand]],
                                      checkActor: ActorRef[CheckRadioSourceCommand]):
  Behavior[ScheduleCheckCommand] =
    if inProgress.contains(checkActor) then
      context.unwatch(checkActor)

      pending.dequeueOption match
        case Some((nextActor, nextQueue)) =>
          triggerSourceCheck(context, scheduleActor, nextActor, nextQueue)
        case None =>
          handleSchedulerCommands(scheduleActor, None, pending)
    else Behaviors.same

  /**
    * An internal data class holding all the information required for a check
    * of a specific radio source.
    *
    * @param config               the radio player configuration
    * @param source               the affected radio source
    * @param namePrefix           a prefix to generate names for child actors
    * @param streamFactory        the factory for creating audio streams
    * @param scheduler            the scheduler actor
    * @param handleManager        the actor managing radio stream handles
    * @param checkPlaybackFactory the factory to create a check playback
    *                             actor
    * @param optSpawner           an optional [[Spawner]]
    * @param retryDelay           the delay for the next retry
    * @param count                a counter for the checks
    * @param success              flag whether the current check is successful
    */
  private case class RadioSourceCheckContext(config: RadioPlayerConfig,
                                             source: RadioSource,
                                             namePrefix: String,
                                             streamFactory: AsyncAudioStreamFactory,
                                             scheduler: ActorRef[ScheduleCheckCommand],
                                             handleManager:
                                             ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
                                             checkPlaybackFactory: CheckPlaybackActorFactory,
                                             optSpawner: Option[Spawner],
                                             retryDelay: FiniteDuration,
                                             count: Int = 1,
                                             success: Boolean = false):
    /**
      * Creates and prepares an actor to check the playback of the source
      * associated with this check context.
      *
      * @param actorContext the context of the owning actor
      * @return the check playback actor
      */
    def triggerRadioSourceCheck(actorContext: ActorContext[CheckRadioSourceCommand]):
    ActorRef[CheckPlaybackCommand] =
      val spawner = getSpawner(optSpawner, actorContext)
      val playbackNamePrefix = s"${namePrefix}_${count}_"
      val checkPlaybackBehavior = checkPlaybackFactory(actorContext.self, source, playbackNamePrefix, streamFactory,
        config.playerConfig, handleManager)
      val checkPlaybackActor = spawner.spawn(checkPlaybackBehavior, Some(playbackNamePrefix + "check"))
      actorContext.watchWith(checkPlaybackActor, CheckPlaybackActorStopped)
      checkPlaybackActor

    /**
      * Returns an updated context after a check failed. Some properties are
      * updated to handle an upcoming test.
      *
      * @return the updated context
      */
    def contextForRetry(): RadioSourceCheckContext =
      val nextDelayMillis = math.min(math.round(retryDelay.toMillis * config.retryFailedSourceIncrement),
        config.maxRetryFailedSource.toMillis)
      copy(retryDelay = nextDelayMillis.millis, count = count + 1)

  /**
    * The message handler function of the check radio source actor.
    *
    * @param checkContext the context for checking a radio source
    * @return the updated behavior of this actor
    */
  private def handleRadioSourceCheckCommand(checkContext: RadioSourceCheckContext): Behavior[CheckRadioSourceCommand] =
    Behaviors.setup { context =>
      checkContext.scheduler ! AddScheduledCheck(context.self, checkContext.config.retryFailedSource)

      def handle(state: RadioSourceCheckContext): Behavior[CheckRadioSourceCommand] =
        Behaviors.receiveMessage:
          case RunRadioSourceCheck(scheduleActor) =>
            val checkActor = state.triggerRadioSourceCheck(context)
            val timeoutCmd = ScheduledInvocationActor.typedInvocationCommand(checkContext.config.sourceCheckTimeout,
              context.self, RadioSourceCheckTimeout(checkActor, state.count))
            scheduleActor ! timeoutCmd
            Behaviors.same

          case RadioSourceCheckSuccessful() =>
            context.log.info("Check for source {} was successful. Waiting for completion.", state.source)
            handle(state.copy(success = true))

          case CheckPlaybackActorStopped if !state.success =>
            val nextCheckContext = state.contextForRetry()
            state.scheduler ! AddScheduledCheck(context.self, nextCheckContext.retryDelay)
            context.log.info("Check for source {} failed. Rescheduling after {}.",
              state.source, nextCheckContext.retryDelay)
            handle(nextCheckContext)

          case CheckPlaybackActorStopped =>
            context.log.info("Check for source {} completed successfully.", state.source)
            Behaviors.stopped

          case RadioSourceCheckTimeout(checkActor, count) =>
            if !state.success && count == state.count then
              checkActor ! CheckTimeout
            Behaviors.same

      handle(checkContext)
    }

  /**
    * An internal data class collecting all the relevant information for
    * handling messages related to the error state. This includes the required
    * dependencies and the error state itself.
    *
    * @param config                   the config for the radio player
    * @param enabledStateActor        the actor that manages the enabled
    *                                 state of radio sources
    * @param streamFactory            the factory for creating audio streams
    * @param scheduledInvocationActor the actor for scheduled invocations
    * @param eventActor               the event manager actor
    * @param handleManager            the actor managing radio stream actors
    * @param schedulerFactory         the factory to create a scheduler actor
    * @param checkSourceActorFactory  the factory to create a source check
    *                                 actor
    * @param optSpawner               an optional ''Spawner''
    * @param errorSources             the current sources in error state
    * @param count                    a counter for error sources to generate
    *                                 unique actor names
    */
  private case class ErrorStateContext(config: RadioPlayerConfig,
                                       enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
                                       streamFactory: AsyncAudioStreamFactory,
                                       scheduledInvocationActor: ActorRef[
                                         ScheduledInvocationActor.ScheduledInvocationCommand],
                                       eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
                                       handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
                                       schedulerFactory: CheckSchedulerActorFactory,
                                       checkSourceActorFactory: CheckSourceActorFactory,
                                       optSpawner: Option[Spawner],
                                       errorSources: Set[RadioSource] = Set.empty,
                                       count: Int = 0):
    /**
      * Processes a radio source that caused an error and returns an updated
      * context object.
      *
      * @param errorSource  the error source
      * @param actorContext the context of the owning actor
      * @param scheduler    the scheduler actor
      * @return the updated state
      */
    def handleErrorSource(errorSource: RadioSource,
                          actorContext: ActorContext[ErrorStateCommand],
                          scheduler: ActorRef[ScheduleCheckCommand]): ErrorStateContext =
      if errorSources contains errorSource then this
      else
        actorContext.log.info("Adding {} to error state.", errorSource)
        val index = count + 1
        val childNamePrefix = ActorNamePrefix + index
        val checkBehavior = checkSourceActorFactory(config,
          errorSource,
          childNamePrefix,
          streamFactory,
          scheduler,
          handleManager)
        val checkActor = getSpawner(optSpawner, actorContext).spawn(checkBehavior, Some(childNamePrefix))

        actorContext.watchWith(checkActor, SourceAvailableAgain(errorSource))
        enabledStateActor ! RadioControlProtocol.DisableSource(errorSource)

        copy(count = index, errorSources = errorSources + errorSource)

  /**
    * The message handling function for the error state actor.
    *
    * @param initialStateContext the context for handling error state messages
    * @return the updated behavior
    */
  private def handleErrorStateCommand(initialStateContext: ErrorStateContext): Behavior[ErrorStateCommand] =
    Behaviors.setup[ErrorStateCommand] { context =>
      val eventListener = context.messageAdapter[RadioEvent](HandleEvent.apply)
      initialStateContext.eventActor ! EventManagerActor.RegisterListener(eventListener)

      val schedulerBehavior = initialStateContext.schedulerFactory(initialStateContext.scheduledInvocationActor)
      val scheduler = getSpawner(initialStateContext.optSpawner, context)
        .spawn(schedulerBehavior, Some(SchedulerActorName))

      def handle(stateContext: ErrorStateContext): Behavior[ErrorStateCommand] =
        Behaviors.receiveMessage:
          case GetSourcesInErrorState(receiver) =>
            receiver ! SourcesInErrorState(stateContext.errorSources)
            Behaviors.same

          case HandleEvent(event) =>
            extractErrorSource(event) match
              case Some(errorSource) =>
                handle(stateContext.handleErrorSource(errorSource, context, scheduler))
              case None =>
                Behaviors.same

          case SourceAvailableAgain(source) =>
            context.log.info("Removing {} from error state.", source)
            stateContext.enabledStateActor ! RadioControlProtocol.EnableSource(source)
            handle(stateContext.copy(errorSources = stateContext.errorSources - source))

      handle(initialStateContext)
    }

  /**
    * Obtain a [[Spawner]] either from the ''Option'' if it is defined or
    * create one based on the given actor context.
    *
    * @param optSpawner the ''Option'' with the [[Spawner]]
    * @param context    the actor context
    * @tparam T the type of the actor context
    * @return the [[Spawner]]
    */
  private def getSpawner[T](optSpawner: Option[Spawner], context: ActorContext[T]): Spawner =
    optSpawner getOrElse context
