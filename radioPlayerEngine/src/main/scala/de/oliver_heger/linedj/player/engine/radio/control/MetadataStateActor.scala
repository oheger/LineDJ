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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MetadataExclusion, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig}
import de.oliver_heger.linedj.player.engine.radio.stream.{RadioStreamHandle, RadioStreamHandleManagerActor}
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioEvent, RadioMetadataEvent, RadioSource}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Scheduler}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{KillSwitch, KillSwitches}
import org.apache.pekko.util.{ByteString, Timeout}
import org.apache.pekko.{NotUsed, actor as classic}

import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/**
  * A module providing functionality related to checking and enforcing metadata
  * exclusions.
  *
  * This module provides an actor implementation that listens on radio metadata
  * events and checks the metadata against configured metadata exclusions. When
  * an exclusion is matched, the current radio source is disabled. In addition,
  * another actor is started which checks periodically the metadata for the
  * affected source, so that it can be enabled again when there is a change.
  */
object MetadataStateActor:
  /**
    * The base trait for commands processed by the metadata exclusion state
    * actor. This actor keeps track on the radio sources in exclusion state due
    * to metadata that matches one of the configured exclusions. Such radio
    * sources are checked periodically (by other helper actors) whether they
    * can be enabled again.
    */
  sealed trait MetadataExclusionStateCommand

  /**
    * A command processed by the metadata exclusion state actor that updates
    * the global metadata configuration. The provided configuration is stored.
    * All radio sources disabled due to metadata are enabled again, so that the
    * updated configuration can become active.
    *
    * @param metaConfig the new metadata config
    */
  case class InitMetadataConfig(metaConfig: MetadataConfig) extends MetadataExclusionStateCommand

  /**
    * A command processed by the metadata exclusion state actor indicating
    * that a specific radio source has been checked successfully and can be
    * enabled again.
    *
    * @param source the radio source affected
    */
  private[control] case class SourceCheckSucceeded(source: RadioSource) extends MetadataExclusionStateCommand

  /**
    * A command processed by the metadata exclusion state actor indicating a
    * timeout of a currently running source check. If the source is still
    * marked as disabled, the check is now canceled. Note: This command mainly
    * serves the purpose to prevent dead letter warnings that could occur if
    * timeout messages would be scheduled directly to the check actors.
    *
    * @param source     the radio source affected
    * @param checkActor the actor executing the check
    */
  private[control] case class SourceCheckTimeout(source: RadioSource,
                                                 checkActor: ActorRef[SourceCheckCommand])
    extends MetadataExclusionStateCommand

  /**
    * An internal command processed by the metadata exclusion state actor that
    * tells it to handle the given radio event. If this is an event about
    * changed metadata, the data is checked against the defined exclusions.
    *
    * @param event the event to check
    */
  private case class HandleRadioEvent(event: RadioEvent) extends MetadataExclusionStateCommand

  /**
    * An internal command the metadata exclusion state actor sends to itself
    * when the response from the metadata exclusion finder service arrives.
    *
    * @param response the response received from the service
    * @param source   the affected radio source
    */
  private case class EventExclusionResponseArrived(response:
                                                   MetadataExclusionFinderService.MetadataExclusionFinderResponse,
                                                   source: RadioSource) extends MetadataExclusionStateCommand

  /** The name prefix for actors checking a specific radio source. */
  final val SourceCheckActorNamePrefix = "metadataSourceCheck"

  /**
    * A trait defining a factory function for creating new instances of the
    * metadata state actor.
    */
  trait Factory:
    /**
      * Returns a ''Behavior'' for creating a new actor instance.
      *
      * @param radioConfig        the radio player config
      * @param enabledStateActor  the actor managing the source enabled state
      * @param scheduleActor      the actor for scheduled invocations
      * @param eventActor         the event manager actor
      * @param handleManager      the actor managing radio stream handles
      * @param intervalService    the evaluate intervals service
      * @param finderService      the service for finding metadata exclusions
      * @param clock              a clock
      * @param sourceCheckFactory the factory for source check actors
      * @return the ''Behavior'' for the new actor instance
      */
    def apply(radioConfig: RadioPlayerConfig,
              enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
              scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
              eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
              handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
              intervalService: EvaluateIntervalsService,
              finderService: MetadataExclusionFinderService,
              clock: Clock = Clock.systemDefaultZone(),
              sourceCheckFactory: SourceCheckFactory = sourceCheckBehavior): Behavior[MetadataExclusionStateCommand]

  /**
    * A data class holding the internal state for metadata exclusion checks.
    *
    * @param metaConfig      the current [[MetadataConfig]]
    * @param disabledSources a map with radio sources in disabled state due to
    *                        matched metadata and their responsible check
    *                        actors
    * @param count           a counter for generating actor names
    * @param seqNo           a sequence counter to detect stale responses from
    *                        the exclusions finder service
    */
  private case class MetadataExclusionState(metaConfig: MetadataConfig,
                                            disabledSources: Map[RadioSource, ActorRef[SourceCheckCommand]],
                                            count: Int,
                                            seqNo: Int):
    /**
      * Returns an updated state that marks the given source as disabled and
      * has some related property updates.
      *
      * @param source     the source to be marked as disabled
      * @param checkActor the actor to check this source
      * @return the updated state
      */
    def disable(source: RadioSource, checkActor: ActorRef[SourceCheckCommand]): MetadataExclusionState =
      copy(disabledSources = disabledSources + (source -> checkActor), count = count + 1)

    /**
      * Tries to enable the given source and returns an option with an updated
      * state. If the source is not disabled, result is ''None''.
      *
      * @param source the source to re-enable
      * @return an ''Option'' with the updated state
      */
    def enableIfDisabled(source: RadioSource): Option[MetadataExclusionState] =
      if disabledSources.contains(source) then Some(copy(disabledSources = disabledSources - source))
      else None

  /**
    * A default [[Factory]] implementation to create instances of the metadata
    * state actor.
    */
  final val metadataStateBehavior: Factory =
    (radioConfig: RadioPlayerConfig,
     enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
     scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
     eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
     handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
     intervalService: EvaluateIntervalsService,
     finderService: MetadataExclusionFinderService,
     clock: Clock,
     sourceCheckFactory: SourceCheckFactory) => Behaviors.setup { context =>
      val listener = context.messageAdapter[RadioEvent] { event => HandleRadioEvent(event) }
      eventActor ! EventManagerActor.RegisterListener(listener)

      def handle(state: MetadataExclusionState): Behavior[MetadataExclusionStateCommand] =
        Behaviors.receiveMessage:
          case InitMetadataConfig(metaConfig) =>
            state.disabledSources foreach { e =>
              context.log.info("Stopping source check actor for {} due to configuration change.", e._1)
              enabledStateActor ! RadioControlProtocol.EnableSource(e._1)
              e._2 ! CancelSourceCheck(terminate = true)
            }
            handle(state.copy(metaConfig = metaConfig, disabledSources = Map.empty))

          case HandleRadioEvent(event) =>
            event match
              case RadioMetadataEvent(source, data@CurrentMetadata(_), time)
                if !state.disabledSources.contains(source) =>
                val nextState = state.copy(seqNo = state.seqNo + 1)
                val sourceConfig = state.metaConfig.metadataSourceConfig(source)
                implicit val ec: ExecutionContextExecutor = context.executionContext

                finderService.findMetadataExclusion(state.metaConfig, sourceConfig, data, time, nextState.seqNo)
                  .foreach { res =>
                    context.self ! EventExclusionResponseArrived(res, source)
                  }
                handle(nextState)
              case _ =>
                Behaviors.same

          case EventExclusionResponseArrived(response, source) if response.seqNo == state.seqNo =>
            response.result map { exclusion =>
              val checkName = s"$SourceCheckActorNamePrefix${state.count}"
              val checkBehavior = sourceCheckFactory(source,
                checkName,
                radioConfig,
                state.metaConfig,
                exclusion,
                context.self,
                scheduleActor,
                clock,
                handleManager,
                intervalService,
                finderService)
              val checkActor = context.spawn(checkBehavior, checkName)
              enabledStateActor ! RadioControlProtocol.DisableSource(source)
              context.log.info("Disabled radio source '{}' because of metadata exclusion '{}'.", source,
                exclusion.name getOrElse exclusion.pattern)
              context.log.info("Periodic checks are done by actor '{}'.", checkName)
              handle(state.disable(source, checkActor))
            } getOrElse Behaviors.same

          case _: EventExclusionResponseArrived => // stale response
            Behaviors.same

          case SourceCheckSucceeded(source) =>
            state.enableIfDisabled(source) map { nextState =>
              enabledStateActor ! RadioControlProtocol.EnableSource(source)
              handle(nextState)
            } getOrElse Behaviors.same

          case SourceCheckTimeout(source, checkActor) =>
            if state.disabledSources.contains(source) then
              checkActor ! CancelSourceCheck(terminate = false)
            Behaviors.same

      handle(MetadataExclusionState(MetadataConfig.Empty, Map.empty, 1, seqNo = 0))
    }

  /**
    * The base trait for commands processed by the metadata source check actor.
    * This actor is responsible for checking the metadata state of a specific
    * radio source in periodic intervals.
    */
  private[control] sealed trait SourceCheckCommand

  /**
    * A command to tell the source check actor the result of the latest check.
    * If an exclusion was found, this means that the affected source still
    * needs to remain in disabled state. Otherwise, it can be played again.
    *
    * @param optExclusion an optional exclusion active for the source
    */
  private[control] case class MetadataCheckResult(optExclusion: Option[MetadataExclusion]) extends SourceCheckCommand

  /**
    * A command to tell the source check actor that the currently ongoing check
    * should be canceled, due to a timeout or a change in the global
    * configuration.
    *
    * @param terminate flag whether the actor should terminate itself
    */
  private[control] case class CancelSourceCheck(terminate: Boolean) extends SourceCheckCommand

  /**
    * An internal command for the source check actor telling it that it is now
    * due to run another check for the managed radio source.
    */
  private case object RunSourceCheck extends SourceCheckCommand

  /**
    * An internal command for the source check actor that propagates the result
    * of the intervals service on the resume intervals of the current source.
    *
    * @param refTime the reference time the service was queried at
    * @param result  the query result
    */
  private case class NextResumeInterval(refTime: LocalDateTime,
                                        result: IntervalQueryResult) extends SourceCheckCommand

  /**
    * Constant for a [[MetadataCheckResult]] message that indicates a
    * successful result of a metadata check. On receiving this result, the
    * source check actor assumes that the affected radio source can be played
    * again, since no applying exclusion was found. Note that this result is
    * sent also in case that there was an error with the radio stream. In this
    * case, the error will probably happen again when playback starts of this
    * source, and then the error state actor will kick in.
    */
  private val SuccessMetadataCheckResult = MetadataCheckResult(None)

  /**
    * A trait defining a factory function for an internal actor implementation
    * responsible for monitoring the metadata exclusion state of a specific
    * radio source. This actor schedules metadata checks on this radio source
    * periodically by creating and managing a metadata check runner actor.
    */
  private[control] trait SourceCheckFactory:
    /**
      * Returns a ''Behavior'' for creating a new actor instance.
      *
      * @param source           the radio source affected
      * @param namePrefix       a prefix for generating actor names
      * @param radioConfig      the radio player config
      * @param metadataConfig   the global metadata config
      * @param currentExclusion the detected metadata exclusion
      * @param stateActor       the metadata state actor
      * @param scheduleActor    the actor for scheduled invocations
      * @param clock            the clock
      * @param handleManager    the actor managing radio stream handles
      * @param intervalService  the intervals service
      * @param finderService    the service for finding metadata exclusions
      * @param runnerFactory    the factory for check runner actors
      * @return the ''Behavior'' for a new actor instance
      */
    def apply(source: RadioSource,
              namePrefix: String,
              radioConfig: RadioPlayerConfig,
              metadataConfig: MetadataConfig,
              currentExclusion: MetadataExclusion,
              stateActor: ActorRef[MetadataExclusionStateCommand],
              scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
              clock: Clock,
              handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
              intervalService: EvaluateIntervalsService,
              finderService: MetadataExclusionFinderService,
              runnerFactory: MetadataCheckRunnerFactory = checkRunnerBehavior): Behavior[SourceCheckCommand]

  /**
    * A default [[SourceCheckFactory]] instance that can be used to create new
    * instances of the source check actor.
    */
  private[control] val sourceCheckBehavior: SourceCheckFactory =
    (source: RadioSource,
     namePrefix: String,
     radioConfig: RadioPlayerConfig,
     metadataConfig: MetadataConfig,
     currentExclusion: MetadataExclusion,
     stateActor: ActorRef[MetadataExclusionStateCommand],
     scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
     clock: Clock,
     handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
     intervalService: EvaluateIntervalsService,
     finderService: MetadataExclusionFinderService,
     runnerFactory: MetadataCheckRunnerFactory) => Behaviors.setup { context =>
      def handleWaitForNextCheck(exclusion: MetadataExclusion, count: Int): Behavior[SourceCheckCommand] =
        Behaviors.receiveMessagePartial:
          case CancelSourceCheck(terminate) =>
            if terminate then Behaviors.stopped else Behaviors.same

          case RunSourceCheck =>
            val nextCount = count + 1
            context.log.info("Triggering metadata check {} for {}.", nextCount, source)
            val runnerBehavior = runnerFactory(source,
              namePrefix + "_run" + nextCount,
              metadataConfig,
              exclusion,
              handleManager,
              intervalService,
              finderService,
              context.self,
              clock)
            val runner = context.spawn(runnerBehavior, namePrefix + nextCount)

            val timeout = ScheduledInvocationActor.typedInvocationCommand(radioConfig.metadataCheckTimeout,
              stateActor,
              SourceCheckTimeout(source, context.self))
            scheduleActor ! timeout
            handleCheckInProgress(runner, nextCount)

          case NextResumeInterval(time, result) =>
            val timeUntilNextResumeInterval = result match
              case Before(start) => durationBetween(time, start.value, exclusion.checkInterval)
              case _ => exclusion.checkInterval
            val nextCheckDelay = timeUntilNextResumeInterval.min(exclusion.checkInterval)
            context.log.info("Scheduling next metadata check for {} after {}.", source, nextCheckDelay)
            scheduleActor ! ScheduledInvocationActor.typedInvocationCommand(nextCheckDelay,
              context.self, RunSourceCheck)
            handleWaitForNextCheck(exclusion, count)

      def handleCheckInProgress(checkRunner: ActorRef[MetadataCheckRunnerCommand],
                                count: Int): Behavior[SourceCheckCommand] =
        Behaviors.receiveMessagePartial:
          case CancelSourceCheck(terminate) =>
            checkRunner ! MetadataCheckRunnerTimeout
            if terminate then handleWaitForTermination() else Behaviors.same

          case MetadataCheckResult(None) =>
            context.log.info("Metadata check successful for {}.", source)
            stateActor ! SourceCheckSucceeded(source)
            Behaviors.stopped

          case MetadataCheckResult(Some(exclusion)) =>
            queryResumeIntervals(exclusion, count)

      def handleWaitForTermination(): Behavior[SourceCheckCommand] =
        Behaviors.receiveMessagePartial:
          case _: MetadataCheckResult =>
            Behaviors.stopped

      def queryResumeIntervals(exclusion: MetadataExclusion, count: Int): Behavior[SourceCheckCommand] =
        implicit val ec: ExecutionContext = context.executionContext
        val refTime = time(clock)
        val metadataSourceConfig = metadataConfig.metadataSourceConfig(source)
        intervalService.evaluateIntervals(metadataSourceConfig.resumeIntervals, refTime, 0) foreach { result =>
          context.self ! NextResumeInterval(refTime, result.result)
        }
        handleWaitForNextCheck(exclusion, count)

      context.log.info("Starting metadata source check actor {} for radio source {}.", namePrefix, source)
      queryResumeIntervals(currentExclusion, 0)
    }

  /**
    * The base trait for commands processed by the metadata check runner actor.
    * This actor is responsible for running a single test whether a source can
    * now be enabled again based on its metadata. This is done by fetching the
    * current metadata of the source's radio stream and matching it against the
    * exclusions defined. This is repeated until no match is found or the
    * timeout for the check is reached.
    */
  private[control] sealed trait MetadataCheckRunnerCommand

  /**
    * A command for sending the latest metadata from a radio stream to the
    * check runner actor.
    *
    * @param metadata the metadata
    * @param time     the time when the data was received
    */
  private[control] case class MetadataRetrieved(metadata: CurrentMetadata,
                                                time: LocalDateTime) extends MetadataCheckRunnerCommand

  /**
    * A command that tells the check runner actor that the monitored radio
    * stream has stopped. Depending on the context, this can mean different
    * things: If the stream stopped unexpectedly, this is an error. Otherwise,
    * a requested cancel operation is now complete.
    */
  private[control] case object RadioStreamStopped extends MetadataCheckRunnerCommand

  /**
    * A command telling the metadata check runner actor that the timeout for
    * the check was reached. This typically means that no updated metadata was
    * found, and therefore, the radio source should remain in excluded state.
    */
  private[control] case object MetadataCheckRunnerTimeout extends MetadataCheckRunnerCommand

  /**
    * An internal command to notify the check runner actor about the
    * availability of the handle for the current radio stream. If everything
    * went well, the message contains both the handle and the attached source
    * for metadata.
    *
    * @param triedHandleResult a [[Try]] with the stream handle and the
    *                          [[Source]] for metadata
    */
  private case class RadioStreamHandleReceived(triedHandleResult:
                                               Try[(RadioStreamHandle, Source[ByteString, NotUsed])])
    extends MetadataCheckRunnerCommand

  /**
    * An internal command the check runner actor sends to itself when the
    * response of a query to the intervals service arrives.
    *
    * @param response the response from the service
    */
  private case class ResumeIntervalResult(response: EvaluateIntervalsService.EvaluateIntervalsResponse)
    extends MetadataCheckRunnerCommand

  /**
    * An internal command the check runner actor sends to itself when the
    * response of a request to the metadata exclusion finder service arrives.
    * The sequence number of the response is checked to filter out stale
    * responses.
    *
    * @param metadataRetrieved the related metadata retrieved command
    * @param response          the response received from the service
    */
  private case class ExclusionFinderResponse(metadataRetrieved: MetadataRetrieved,
                                             response: MetadataExclusionFinderService.MetadataExclusionFinderResponse)
    extends MetadataCheckRunnerCommand

  /**
    * A trait defining a factory function for an internal actor that executes
    * a single check on a radio source that is disabled because of its
    * current metadata. Instances are created periodically for affected
    * sources to check whether those sources can now be played again.
    */
  private[control] trait MetadataCheckRunnerFactory:
    /**
      * Returns the ''Behavior'' to create a new instance of the metadata check
      * runner actor.
      *
      * @param source           the radio source to be checked
      * @param namePrefix       prefix to generate actor names
      * @param metadataConfig   the global metadata config
      * @param currentExclusion the currently detected metadata exclusion
      * @param handleManager    the actor managing radio stream handles
      * @param intervalService  the interval query service
      * @param finderService    the service for finding metadata exclusions
      * @param sourceChecker    the source checker parent actor
      * @param clock            the clock for querying the current time
      * @return the ''Behavior'' to create a new actor instance
      */
    def apply(source: RadioSource,
              namePrefix: String,
              metadataConfig: MetadataConfig,
              currentExclusion: MetadataExclusion,
              handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
              intervalService: EvaluateIntervalsService,
              finderService: MetadataExclusionFinderService,
              sourceChecker: ActorRef[SourceCheckCommand],
              clock: Clock): Behavior[MetadataCheckRunnerCommand]

  /**
    * An internal data class holding the information required while running a
    * single check for metadata exclusions.
    *
    * @param currentExclusion  the currently active exclusion
    * @param optResumeInterval the result of the latest query for resume
    *                          intervals
    * @param handle            the handle of the current radio stream
    * @param killSwitch        a [[KillSwitch]] to cancel the radio stream
    * @param optLastMetadata   the last metadata that was received
    * @param seqNo             a sequence number to deal with stale results
    */
  private case class CheckState(currentExclusion: MetadataExclusion,
                                optResumeInterval: Option[IntervalQueryResult],
                                handle: RadioStreamHandle,
                                killSwitch: KillSwitch,
                                optLastMetadata: Option[CurrentMetadata],
                                seqNo: Int):
    /**
      * Returns the last cached interval query result for the resume interval
      * if it is available and if it is still valid for the reference time
      * specified.
      *
      * @param time the reference time
      * @return an ''Option'' with the resume interval query result
      */
    def resumeIntervalAt(time: LocalDateTime): Option[IntervalQueryResult] =
      optResumeInterval flatMap :
        case r@Before(start) if time.isBefore(start.value) => Some(r)
        case r@Inside(_, until) if time.isBefore(until.value) => Some(r)
        case _ => None

    /**
      * Returns an updated [[CheckState]] instance with an increment sequence
      * number. This can be used to prepare an operation that could suffer from
      * a stale result.
      *
      * @return the updated state
      */
    def withNextSeqNo(): CheckState = copy(seqNo = seqNo + 1)
  end CheckState

  /**
    * A default [[MetadataCheckRunnerFactory]] instance that can be used to
    * create instances of the check runner actor.
    */
  private[control] val checkRunnerBehavior: MetadataCheckRunnerFactory =
    (source: RadioSource,
     namePrefix: String,
     metadataConfig: MetadataConfig,
     currentExclusion: MetadataExclusion,
     handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
     intervalService: EvaluateIntervalsService,
     finderService: MetadataExclusionFinderService,
     sourceChecker: ActorRef[SourceCheckCommand],
     clock: Clock) => Behaviors.setup[MetadataCheckRunnerCommand] { context =>
      val logPrefix = s"[METADATA CHECK '${source.uri}']"
      context.log.info("{} Starting check.", logPrefix)

      given classic.ActorSystem = context.system.toClassic

      given ExecutionContext = context.executionContext

      val metadataSourceConfig = metadataConfig.metadataSourceConfig(source)

      /**
        * Tries to obtain a handle to the current radio stream. The outcome is
        * passed to this actor.
        */
      def obtainStreamHandle(): Unit =
        // Set a rather long timeout to prevent that a stream is created which is then not released.
        given Timeout(1.hour)

        given Scheduler = context.system.scheduler

        val futHandle = handleManager.ask[RadioStreamHandleManagerActor.GetStreamHandleResponse] { ref =>
          val params = RadioStreamHandleManagerActor.GetStreamHandleParameters(source,
            s"${namePrefix}_metadataCheck")
          RadioStreamHandleManagerActor.GetStreamHandle(params, ref)
        }
        (for
          handleResponse <- futHandle
          handle <- Future.fromTry(handleResponse.triedStreamHandle)
          source <- handle.attachMetadataSinkOrCancel()
        yield (handle, source)).onComplete { triedResult =>
          context.self ! RadioStreamHandleReceived(triedResult)
        }

      /**
        * Starts a stream that reads the metadata from the current radio source
        * and passes it to this actor instance.
        *
        * @param metadataSource the source for metadata
        * @return a [[KillSwitch]] to cancel the stream
        */
      def runMetadataStream(metadataSource: Source[ByteString, NotUsed]): KillSwitch =
        val sink = Sink.foreach[ByteString] { metadata =>
          val metadataMessage = MetadataRetrieved(CurrentMetadata(metadata.utf8String), time(clock))
          context.self ! metadataMessage
        }
        val (killSwitch, futSink) = metadataSource.viaMat(KillSwitches.single)(Keep.right)
          .toMat(sink)(Keep.both)
          .run()
        futSink onComplete { _ =>
          context.self ! RadioStreamStopped
        }
        killSwitch

      /**
        * The command handler function of this actor that is active until the
        * radio stream has been set up.
        *
        * @param isTimeout flag whether the timeout is already reached
        * @return the next behavior of this actor
        */
      def init(isTimeout: Boolean): Behavior[MetadataCheckRunnerCommand] =
        Behaviors.receiveMessagePartial:
          case MetadataCheckRunnerTimeout =>
            context.log.info("{} Timeout before stream handle was received.", logPrefix)
            init(isTimeout = true)

          case RadioStreamHandleReceived(triedHandleResult) =>
            triedHandleResult match
              case Failure(exception) =>
                context.log.info("{} Could not obtain stream handle. Aborting check.", logPrefix, exception)
                sourceChecker ! SuccessMetadataCheckResult
                Behaviors.stopped
              case Success(handleResult) if isTimeout =>
                closeAndStop(handleResult._1, None, stopDirectly = true)
              case Success(handleResult) =>
                context.log.info("{} Received stream handle. Starting metadata stream.", logPrefix)
                val killSwitch = runMetadataStream(handleResult._2)
                val state = CheckState(
                  currentExclusion = currentExclusion,
                  optResumeInterval = None,
                  handle = handleResult._1,
                  killSwitch = killSwitch,
                  optLastMetadata = None,
                  seqNo = 0
                )
                handle(state)

      /**
        * The command handler function of this actor that is active while the
        * metadata check is in progress.
        *
        * @param state the current state of the check operation
        * @return the next behavior of this actor
        */
      def handle(state: CheckState): Behavior[MetadataCheckRunnerCommand] =
        Behaviors.receiveMessagePartial:
          case retrieved@MetadataRetrieved(data, time) =>
            context.log.info("{} Received metadata during check: {}.", logPrefix, data.data)
            val nextState = state.withNextSeqNo().copy(optLastMetadata = Some(data))
            finderService.findMetadataExclusion(metadataConfig, metadataSourceConfig, data, time, nextState.seqNo)
              .foreach { res =>
                context.self ! ExclusionFinderResponse(retrieved, res)
              }
            handle(nextState)

          case ExclusionFinderResponse(MetadataRetrieved(data, time), response) if response.seqNo == state.seqNo =>
            response.result match
              case Some(exclusion) =>
                handle(state.copy(currentExclusion = exclusion))
              case None if state.currentExclusion.resumeMode == ResumeMode.MetadataChange =>
                closeAndStopMetadataCheck(state)
              case None =>
                if metadataSourceConfig.optSongPattern.isEmpty ||
                  matches(metadataSourceConfig.optSongPattern.get, data.title) then
                  closeAndStopMetadataCheck(state)
                else
                  state.resumeIntervalAt(time) match
                    case Some(value) =>
                      handleResumeIntervalResult(value, state)
                    case None =>
                      val nextState = state.withNextSeqNo()
                      intervalService.evaluateIntervals(metadataSourceConfig.resumeIntervals, time, nextState.seqNo)
                        .foreach { res =>
                          context.self ! ResumeIntervalResult(res)
                        }
                      handle(nextState)

          case r: ExclusionFinderResponse =>
            // Ignore a state result with a non-matching sequence number.
            Behaviors.same

          case ResumeIntervalResult(response) if response.seqNo == state.seqNo =>
            handleResumeIntervalResult(response.result, state.copy(optResumeInterval = Some(response.result)))

          case r: ResumeIntervalResult =>
            // Ignore an interval result with a non-matching sequence number.
            Behaviors.same

          case MetadataCheckRunnerTimeout =>
            closeAndStopMetadataCheck(state, MetadataCheckResult(Some(state.currentExclusion)))

          case RadioStreamStopped =>
            // This means that the radio stream stopped due to an error. In this case, report a success result to
            // the parent. If the source is played again, the error can be handled, or - if it no longer occurs -,
            // updated metadata will be available again.
            context.log.warn("{} Unexpected end of metadata stream.", logPrefix)
            closeAndStop(state.handle, None, stopDirectly = true)

      /**
        * The command handler function for the state in which the actor just
        * waits for the completion of the radio stream after the handle was
        * released.
        *
        * @return the next behavior of this actor
        */
      def closing(): Behavior[MetadataCheckRunnerCommand] =
        Behaviors.receiveMessagePartial:
          case RadioStreamStopped =>
            context.log.info("{} Radio stream completed.", logPrefix)
            Behaviors.stopped

      /**
        * Initiates a graceful termination of the check operation from the
        * metadata check in progress state. The required information is
        * obtained from the given state.
        *
        * @param state         the current check state
        * @param resultMessage the result to send to the source check actor
        * @return the next behavior of this actor
        */
      def closeAndStopMetadataCheck(state: CheckState,
                                    resultMessage: MetadataCheckResult = SuccessMetadataCheckResult):
      Behavior[MetadataCheckRunnerCommand] =
        context.log.info("{} Shutting down metadata stream.", logPrefix)
        state.killSwitch.shutdown()
        closeAndStop(state.handle, state.optLastMetadata, resultMessage)

      /**
        * Initiates a graceful termination of the check operation by sending a
        * result message to the source checker actor and canceling the radio
        * stream. The actor stays alive until the metadata stream ends; this is
        * done to avoid a dead letter warning being logged when the stream is
        * complete.
        *
        * @param handle          the handle of the radio stream
        * @param optLastMetadata the last known metadata for this source
        * @param resultMessage   the result to send to the source checker actor
        * @param stopDirectly    flag whether the actor should stop immediately;
        *                        otherwise, a behavior is entered that waits for
        *                        the metadata stream to complete
        * @return the next behavior of this actor
        */
      def closeAndStop(handle: RadioStreamHandle,
                       optLastMetadata: Option[CurrentMetadata],
                       resultMessage: MetadataCheckResult = SuccessMetadataCheckResult,
                       stopDirectly: Boolean = false): Behavior[MetadataCheckRunnerCommand] =
        context.log.info("{} Terminating check actor.", logPrefix)
        handleManager ! RadioStreamHandleManagerActor.ReleaseStreamHandle(source, handle, optLastMetadata)
        sourceChecker ! resultMessage
        if stopDirectly then
          Behaviors.stopped
        else
          closing()

      /**
        * Handles a result from the [[EvaluateIntervalsService]]. The function
        * basically checks whether the radio source is in a resume interval,
        * and the current change of metadata is sufficient to terminate the
        * check.
        *
        * @param result    the result from the intervals service
        * @param nextState the next state to continue the check
        * @return the next behavior of this actor
        */
      def handleResumeIntervalResult(result: IntervalQueryResult,
                                     nextState: CheckState): Behavior[MetadataCheckRunnerCommand] =
        if isInResumeInterval(result) then
          closeAndStopMetadataCheck(nextState)
        else
          handle(nextState)

      obtainStreamHandle()
      init(isTimeout = false)
    }

  /**
    * Returns a flag whether the given result indicates that the current radio
    * source is now inside a resume interval.
    *
    * @param result the interval query result
    * @return a flag whether there is currently a resume interval active
    */
  private def isInResumeInterval(result: IntervalQueryResult): Boolean =
    result match
      case Inside(_, _) => true
      case _ => false
  
  /**
    * Returns the current local time from the given [[Clock]].
    *
    * @param clock the clock
    * @return the current time of the clock as local time
    */
  private def time(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
