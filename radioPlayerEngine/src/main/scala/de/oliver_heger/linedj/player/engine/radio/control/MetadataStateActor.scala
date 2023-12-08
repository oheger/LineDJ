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

import de.oliver_heger.linedj.player.engine.AudioSource
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, LocalBufferActor, PlaybackActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MetadataExclusion, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig}
import de.oliver_heger.linedj.player.engine.radio.stream.{RadioStreamActor, RadioStreamManagerActor}
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioEvent, RadioMetadataEvent, RadioSource}
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.{Actor, Props}
import org.apache.pekko.{actor => classic}

import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

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
      * @param streamManager      the actor managing radio stream actors
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
              streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
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
     streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
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
                streamManager,
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
      * @param streamManager    the actor managing radio stream actors
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
              streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
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
     streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
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
              streamManager,
              intervalService,
              finderService,
              context.self)
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
    * An internal command the check runner actor sends to itself when the
    * result of an interval query for the resume intervals arrives.
    *
    * @param result the query result
    */
  private case class ResumeIntervalResult(result: IntervalQueryResult) extends MetadataCheckRunnerCommand

  /**
    * An internal command the check runner actor sends to itself when the
    * result of a request to the metadata exclusion finder service arrives.
    * Note: In this case, there is no risk of stale results, since new metadata
    * is explicitly requested and cannot arrive at any time.
    *
    * @param metadataRetrieved the related metadata retrieved command
    * @param result            the result received from the service
    */
  private case class ExclusionFinderResult(metadataRetrieved: MetadataRetrieved,
                                           result: Option[MetadataExclusion]) extends MetadataCheckRunnerCommand

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
      * @param streamManager    the actor managing radio stream actors
      * @param intervalService  the interval query service
      * @param finderService    the service for finding metadata exclusions
      * @param sourceChecker    the source checker parent actor
      * @param retrieverFactory the factory to create a retriever actor
      * @return the ''Behavior'' to create a new actor instance
      */
    def apply(source: RadioSource,
              namePrefix: String,
              metadataConfig: MetadataConfig,
              currentExclusion: MetadataExclusion,
              streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
              intervalService: EvaluateIntervalsService,
              finderService: MetadataExclusionFinderService,
              sourceChecker: ActorRef[SourceCheckCommand],
              retrieverFactory: MetadataRetrieveActorFactory = retrieveMetadataBehavior):
    Behavior[MetadataCheckRunnerCommand]

  /**
    * An internal data class holding the information required while running a
    * single check for metadata exclusions.
    *
    * @param currentExclusion  the currently active exclusion
    * @param optResumeInterval the result of the latest query for resume
    *                          intervals
    */
  private case class CheckState(currentExclusion: MetadataExclusion,
                                optResumeInterval: Option[IntervalQueryResult]):
    /**
      * Returns the last cached interval query result for the resume interval
      * if it is available and if it is still valid for the reference time
      * specified.
      *
      * @param time the reference time
      * @return an ''Option'' with the resume interval query result
      */
    def resumeIntervalAt(time: LocalDateTime): Option[IntervalQueryResult] =
      optResumeInterval flatMap:
        case r@Before(start) if time.isBefore(start.value) => Some(r)
        case r@Inside(until) if time.isBefore(until.value) => Some(r)
        case _ => None

  /**
    * A default [[MetadataCheckRunnerFactory]] instance that can be used to
    * create instances of the check runner actor.
    */
  private[control] val checkRunnerBehavior: MetadataCheckRunnerFactory =
    (source: RadioSource,
     namePrefix: String,
     metadataConfig: MetadataConfig,
     currentExclusion: MetadataExclusion,
     streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
     intervalService: EvaluateIntervalsService,
     finderService: MetadataExclusionFinderService,
     sourceChecker: ActorRef[SourceCheckCommand],
     retrieverFactory: MetadataRetrieveActorFactory) => Behaviors.setup[MetadataCheckRunnerCommand] { context =>
      implicit val ec: ExecutionContext = context.executionContext
      val retrieverBehavior = retrieverFactory(source, streamManager, context.self)
      val retriever = context.spawn(retrieverBehavior, namePrefix + "_retriever")
      retriever ! GetMetadata
      val metadataSourceConfig = metadataConfig.metadataSourceConfig(source)

      def handle(state: CheckState): Behavior[MetadataCheckRunnerCommand] =
        Behaviors.receiveMessage:
          case retrieved@MetadataRetrieved(data, time) =>
            context.log.info("Received metadata during check: {}.", data.data)
            finderService.findMetadataExclusion(metadataConfig, metadataSourceConfig, data, time, 0) foreach { res =>
              context.self ! ExclusionFinderResult(retrieved, res.result)
            }
            Behaviors.same

          case ExclusionFinderResult(MetadataRetrieved(data, time), result) =>
            result match
              case Some(exclusion) =>
                retriever ! GetMetadata
                handle(state.copy(currentExclusion = exclusion))
              case None if state.currentExclusion.resumeMode == ResumeMode.MetadataChange =>
                terminateCheck(None)
              case None =>
                if metadataSourceConfig.optSongPattern.isEmpty ||
                  matches(metadataSourceConfig.optSongPattern.get, data.title) then
                  terminateCheck(None)
                else
                  state.resumeIntervalAt(time) match
                    case Some(value) =>
                      handleResumeIntervalResult(value, state)
                    case None =>
                      intervalService.evaluateIntervals(metadataSourceConfig.resumeIntervals, time,
                        0) foreach { res =>
                        context.self ! ResumeIntervalResult(res.result)
                      }
                      Behaviors.same

          case ResumeIntervalResult(result) =>
            handleResumeIntervalResult(result, state.copy(optResumeInterval = Some(result)))

          case MetadataCheckRunnerTimeout =>
            retriever ! CancelStream
            handleTimeout(Some(state.currentExclusion))

          case RadioStreamStopped =>
            // This means that the radio stream stopped due to an error. In this case, report a success result to
            // the parent. If the source is played again, the error can be handled, or - if it no longer occurs -,
            // updated metadata will be available again.
            sourceChecker ! MetadataCheckResult(None)
            Behaviors.stopped

      def handleTimeout(result: Option[MetadataExclusion]): Behavior[MetadataCheckRunnerCommand] =
        Behaviors.receiveMessagePartial:
          case RadioStreamStopped =>
            sourceChecker ! MetadataCheckResult(result)
            Behaviors.stopped

      def terminateCheck(result: Option[MetadataExclusion]): Behavior[MetadataCheckRunnerCommand] =
        retriever ! CancelStream
        handleTimeout(result)

      def handleResumeIntervalResult(result: IntervalQueryResult,
                                     nextState: CheckState): Behavior[MetadataCheckRunnerCommand] =
        if isInResumeInterval(result) then
          terminateCheck(None)
        else
          retriever ! GetMetadata
          handle(nextState)

      handle(CheckState(currentExclusion, None))
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
      case Inside(_) => true
      case _ => false

  /**
    * The base trait for commands processed by the metadata retrieve actor.
    * This actor opens a radio stream for a specific source and keeps track on
    * the metadata. The latest metadata that was received can be queried.
    */
  private[control] sealed trait MetadataRetrieveCommand

  /**
    * A command to request the latest metadata received from the monitored
    * radio stream.
    */
  private[control] case object GetMetadata extends MetadataRetrieveCommand

  /**
    * A command telling the metadata retrieve actor to cancel the current
    * stream and stop itself.
    */
  private[control] case object CancelStream extends MetadataRetrieveCommand

  /**
    * An internal command the metadata retriever actor sends to itself when the
    * stream manager actor sends the response with the requested stream actor.
    *
    * @param streamActor the radio stream actor reference
    */
  private case class StreamActorArrived(streamActor: classic.ActorRef) extends MetadataRetrieveCommand

  /**
    * An internal command the metadata retriever actor sends to itself when it
    * receives a radio event - which should be an update of metadata.
    *
    * @param event the event
    */
  private case class RadioEventArrived(event: RadioEvent) extends MetadataRetrieveCommand

  /**
    * An internal command the metadata retriever actor sends to itself to
    * propagate the resolved audio source to itself.
    *
    * @param audioSource the resolved audio source
    */
  private case class RadioStreamResolved(audioSource: AudioSource) extends MetadataRetrieveCommand

  /**
    * An internal command the metadata retriever actor sends to itself when the
    * stream actor dies unexpectedly.
    */
  private case object StreamActorDied extends MetadataRetrieveCommand

  /**
    * An internal command indicating to the metadata retriever actor that a
    * request for data to the stream actor has been answered.
    */
  private case object DataLoaded extends MetadataRetrieveCommand

  /**
    * A command handled by the bridge actor that tells it to request another
    * chunk of data from the stream actor.
    */
  private case object RequestData

  /**
    * A data class holding the information required while fetching metadata
    * from a radio stream.
    *
    * @param optMetadata    stores the latest metadata encountered if any
    * @param metadataTime   the time when the metadata was received
    * @param lastMetadata   the last metadata sent to the check runner
    * @param bridgeActor    the actor bridging between this actor and the
    *                       stream actor
    * @param streamActor    the radio stream actor
    * @param resolvedSource the resolved audio source
    * @param requestPending flag whether metadata has been requested
    */
  private case class MetadataRetrieveState(optMetadata: Option[CurrentMetadata],
                                           metadataTime: LocalDateTime,
                                           lastMetadata: Option[CurrentMetadata],
                                           bridgeActor: classic.ActorRef,
                                           streamActor: classic.ActorRef,
                                           resolvedSource: AudioSource,
                                           requestPending: Boolean):
    /**
      * Returns a [[MetadataRetrieved]] message to be sent to the check runner
      * actor if all criteria are fulfilled. Otherwise, result is ''None''. In
      * addition, an updated state is returned.
      *
      * @return an optional message to send and an updated state
      */
    def messageToSend(): (Option[MetadataRetrieved], MetadataRetrieveState) =
      if requestPending && optMetadata.isDefined && optMetadata != lastMetadata then
        (optMetadata.map { data => MetadataRetrieved(data, metadataTime) },
          copy(requestPending = false, lastMetadata = optMetadata))
      else (None, this)

    /**
      * Releases the current radio stream actor by sending a corresponding
      * command to the stream manager actor.
      *
      * @param source        the current radio source
      * @param streamManager the stream manager actor
      */
    def releaseStreamActor(source: RadioSource,
                           streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand]): Unit =
      streamManager ! RadioStreamManagerActor.ReleaseStreamActor(source, streamActor, resolvedSource, optMetadata)

  /**
    * A data class holding information required during the initialization phase
    * of the metadata retriever actor.
    *
    * @param requestPending    flag whether a metadata request has been
    *                          received
    * @param streamCanceled    flag whether a cancel command has been received
    * @param optStreamActor    the reference to the stream actor
    * @param optResolvedSource the resolved audio source
    */
  private case class MetadataRetrieveInitState(requestPending: Boolean = false,
                                               streamCanceled: Boolean = false,
                                               optStreamActor: Option[classic.ActorRef] = None,
                                               optResolvedSource: Option[AudioSource] = None):
    /**
      * Creates an initialized [[MetadataRetrieveState]] if all required data
      * is available. Otherwise, result is ''None''. This function is used to
      * check whether the initialization is now complete.
      *
      * @return an ''Option'' with the initial metadata retrieve state
      */
    def createRetrieveState(): Option[MetadataRetrieveState] =
      for
        streamActor <- optStreamActor
        resolvedSource <- optResolvedSource
      yield MetadataRetrieveState(streamActor = streamActor,
        resolvedSource = resolvedSource,
        requestPending = requestPending,
        optMetadata = None,
        metadataTime = LocalDateTime.now(),
        lastMetadata = None,
        bridgeActor = null
      )

  /** The chunk size for requested audio data. */
  private val AudioDataChunkSize = 4096

  /**
    * A trait defining a factory function for creating an internal actor that
    * retrieves metadata from a specific radio stream.
    */
  private[control] trait MetadataRetrieveActorFactory:
    /**
      * Returns a ''Behavior'' for a new actor instance to retrieve metadata
      * from a radio stream.
      *
      * @param source        the source of the radio stream
      * @param streamManager the actor managing radio stream actors
      * @param checkRunner   the actor reference for sending replies
      * @return the ''Behavior'' for the new instance
      */
    def apply(source: RadioSource,
              streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
              checkRunner: ActorRef[MetadataCheckRunnerCommand]): Behavior[MetadataRetrieveCommand]

  /**
    * A default [[MetadataRetrieveActorFactory]] implementation that can be
    * used to create instances of the metadata retriever actor.
    */
  private[control] val retrieveMetadataBehavior: MetadataRetrieveActorFactory =
    (source: RadioSource,
     streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
     checkRunner: ActorRef[MetadataCheckRunnerCommand]) => Behaviors.setup { context =>
      val eventAdapter = context.messageAdapter[RadioEvent] { event => RadioEventArrived(event) }
      val streamResponseAdapter = context.messageAdapter[RadioStreamManagerActor.StreamActorResponse] { response =>
        StreamActorArrived(response.streamActor)
      }

      val sourceListener: RadioStreamActor.SourceListener = (source, _) =>
        context.self ! RadioStreamResolved(source)
      val streamParams = RadioStreamManagerActor.StreamActorParameters(source, sourceListener, eventAdapter)
      streamManager ! RadioStreamManagerActor.GetStreamActor(streamParams, streamResponseAdapter)

      def streamInitializing(state: MetadataRetrieveInitState): Behavior[MetadataRetrieveCommand] =
        Behaviors.receiveMessagePartial:
          case GetMetadata =>
            streamInitializing(state.copy(requestPending = true))

          case CancelStream =>
            streamInitializing(state.copy(streamCanceled = true))

          case StreamActorArrived(streamActor) =>
            completeInitializationIfPossible(state.copy(optStreamActor = Some(streamActor)))

          case RadioStreamResolved(audioSource) =>
            completeInitializationIfPossible(state.copy(optResolvedSource = Some(audioSource)))

      def handle(retrieveState: MetadataRetrieveState): Behavior[MetadataRetrieveCommand] =
        Behaviors.receiveMessagePartial:
          case RadioEventArrived(event) =>
            event match
              case RadioMetadataEvent(_, data@CurrentMetadata(_), time) =>
                sendMetadataIfPossible(retrieveState.copy(optMetadata = Some(data), metadataTime = time))
              case _ => Behaviors.same

          case GetMetadata =>
            sendMetadataIfPossible(retrieveState.copy(requestPending = true))

          case CancelStream =>
            canceling(retrieveState)

          case DataLoaded =>
            retrieveState.bridgeActor ! RequestData
            Behaviors.same

          case StreamActorDied =>
            context.log.error("RadioStreamActor died when checking '{}'.", source)
            checkRunner ! RadioStreamStopped
            Behaviors.stopped

      def canceling(retrieveState: MetadataRetrieveState): Behavior[MetadataRetrieveCommand] =
        Behaviors.receiveMessagePartial:
          case DataLoaded =>
            checkRunner ! RadioStreamStopped
            retrieveState.releaseStreamActor(source, streamManager)
            Behaviors.stopped

          case StreamActorDied =>
            checkRunner ! RadioStreamStopped
            Behaviors.stopped

      def sendMetadataIfPossible(state: MetadataRetrieveState): Behavior[MetadataRetrieveCommand] =
        val (optMetadata, nextState) = state.messageToSend()
        optMetadata.foreach(checkRunner.!)
        handle(nextState)

      def completeInitializationIfPossible(initState: MetadataRetrieveInitState): Behavior[MetadataRetrieveCommand] =
        initState.createRetrieveState() match
          case Some(state) if initState.streamCanceled =>
            state.releaseStreamActor(source, streamManager)
            checkRunner ! RadioStreamStopped
            Behaviors.stopped

          case Some(state) =>
            context.watchWith(state.streamActor.toTyped, StreamActorDied)
            val bridgeActor = createBridgeActor(context, context.self, state.streamActor)
            bridgeActor ! RequestData
            handle(state.copy(bridgeActor = bridgeActor))

          case None =>
            streamInitializing(initState)

      streamInitializing(MetadataRetrieveInitState())
    }

  /**
    * Creates a helper actor that bridges between the metadata retriever actor
    * and the radio stream actor. The actor solves the problem that the
    * protocol of the stream actor is not directly compatible with the commands
    * of the retriever actor.
    *
    * @param context     the context of the retriever actor
    * @param receiver    the reference to the retriever actor
    * @param streamActor the stream actor to bridge
    * @return the bridge actor
    */
  private def createBridgeActor(context: ActorContext[MetadataRetrieveCommand],
                                receiver: ActorRef[MetadataRetrieveCommand],
                                streamActor: classic.ActorRef): classic.ActorRef =
    val props = Props(new Actor {
      override def receive: Receive = {
        case RequestData =>
          streamActor ! PlaybackActor.GetAudioData(AudioDataChunkSize)

        case _: LocalBufferActor.BufferDataResult =>
          receiver ! DataLoaded
      }
    })
    context.actorOf(props)

  /**
    * Returns the current local time from the given [[Clock]].
    *
    * @param clock the clock
    * @return the current time of the clock as local time
    */
  private def time(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
