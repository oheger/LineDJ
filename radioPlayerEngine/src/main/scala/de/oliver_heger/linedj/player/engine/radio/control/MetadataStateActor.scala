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

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.Sink
import akka.stream.{KillSwitch, Materializer}
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig}
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamBuilder
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioEvent, RadioMetadataEvent, RadioSource}

import java.time.{Clock, LocalDateTime, ZoneOffset}
import java.util.regex.{Matcher, Pattern}
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
object MetadataStateActor {
  /**
    * Tries to find a [[MetadataExclusion]] from the given configurations that
    * matches the provided metadata.
    *
    * @param metadataConfig the global metadata configuration
    * @param sourceConfig   the configuration for the current radio source
    * @param metadata       the metadata to check
    * @return an ''Option'' with a matched exclusion
    */
  private[control] def findMetadataExclusion(metadataConfig: MetadataConfig,
                                             sourceConfig: RadioSourceMetadataConfig,
                                             metadata: CurrentMetadata): Option[MetadataExclusion] = {
    lazy val (optArtist, optSong) = extractSongData(sourceConfig, metadata)

    (sourceConfig.exclusions ++ metadataConfig.exclusions).find { exclusion =>
      val optData = exclusion.matchContext match {
        case MatchContext.Title => Some(metadata.title)
        case MatchContext.Artist => optArtist
        case MatchContext.Song => optSong
        case MatchContext.Raw => Some(metadata.data)
      }
      optData exists { data => matches(exclusion.pattern, data) }
    }
  }

  /**
    * Tries to extract the song title and artist from the given metadata. If
    * the radio source defines a corresponding extraction pattern, it is
    * applied and evaluated. Otherwise, exclusions will match on the whole
    * stream title.
    *
    * @param sourceConfig the configuration for the current radio source
    * @param metadata     the metadata
    * @return a pair with the optional extracted artist and song title
    */
  private def extractSongData(sourceConfig: RadioSourceMetadataConfig,
                              metadata: CurrentMetadata): (Option[String], Option[String]) =
    sourceConfig.optSongPattern match {
      case Some(pattern) =>
        getMatch(pattern, metadata.title).map { matcher =>
          (Some(matcher.group(MetadataConfig.ArtistGroup)), Some(matcher.group(MetadataConfig.SongTitleGroup)))
        } getOrElse ((None, None))
      case None =>
        (Some(metadata.title), Some(metadata.title))
    }

  /**
    * Tries to match the given input against the pattern and returns an
    * ''Option'' with the [[Matcher]] if a match was found.
    *
    * @param pattern the pattern
    * @param input   the input string
    * @return an ''Option'' with the matcher
    */
  private def getMatch(pattern: Pattern, input: String): Option[Matcher] = {
    val matcher = pattern.matcher(input)
    if (matcher.matches()) Some(matcher) else None
  }

  /**
    * Checks whether the given pattern matches the input string.
    *
    * @param pattern the pattern
    * @param input   the input string
    * @return a flag whether this is a match
    */
  private def matches(pattern: Pattern, input: String): Boolean = getMatch(pattern, input).isDefined

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

  /** The name prefix for actors checking a specific radio source. */
  final val SourceCheckActorNamePrefix = "metadataSourceCheck"

  /**
    * A trait defining a factory function for creating new instances of the
    * metadata state actor.
    */
  trait Factory {
    /**
      * Returns a ''Behavior'' for creating a new actor instance.
      *
      * @param radioConfig        the radio player config
      * @param enabledStateActor  the actor managing the source enabled state
      * @param scheduleActor      the actor for scheduled invocations
      * @param eventActor         the event manager actor
      * @param streamBuilder      the stream builder
      * @param intervalService    the evaluate intervals service
      * @param clock              a clock
      * @param sourceCheckFactory the factory for source check actors
      * @return the ''Behavior'' for the new actor instance
      */
    def apply(radioConfig: RadioPlayerConfig,
              enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
              scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
              eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
              streamBuilder: RadioStreamBuilder,
              intervalService: EvaluateIntervalsService,
              clock: Clock = Clock.systemDefaultZone(),
              sourceCheckFactory: SourceCheckFactory = sourceCheckBehavior): Behavior[MetadataExclusionStateCommand]
  }

  /**
    * A data class holding the internal state for metadata exclusion checks.
    *
    * @param metaConfig      the current [[MetadataConfig]]
    * @param disabledSources a map with radio sources in disabled state due to
    *                        matched metadata and their responsible check
    *                        actors
    * @param count           a counter for generating actor names
    */
  private case class MetadataExclusionState(metaConfig: MetadataConfig,
                                            disabledSources: Map[RadioSource, ActorRef[SourceCheckCommand]],
                                            count: Int) {
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
      if (disabledSources.contains(source)) Some(copy(disabledSources = disabledSources - source))
      else None
  }

  /**
    * A default [[Factory]] implementation to create instances of the metadata
    * state actor.
    */
  final val metadataStateBehavior: Factory =
    (radioConfig: RadioPlayerConfig,
     enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
     scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
     eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
     streamBuilder: RadioStreamBuilder,
     intervalService: EvaluateIntervalsService,
     clock: Clock,
     sourceCheckFactory: SourceCheckFactory) => Behaviors.setup { context =>
      val listener = context.messageAdapter[RadioEvent] { event => HandleRadioEvent(event) }
      eventActor ! EventManagerActor.RegisterListener(listener)

      def handle(state: MetadataExclusionState): Behavior[MetadataExclusionStateCommand] =
        Behaviors.receiveMessage {
          case InitMetadataConfig(metaConfig) =>
            state.disabledSources foreach { e =>
              context.log.info("Stopping source check actor for {} due to configuration change.", e._1)
              enabledStateActor ! RadioControlProtocol.EnableSource(e._1)
              e._2 ! CancelSourceCheck(terminate = true)
            }
            handle(state.copy(metaConfig = metaConfig, disabledSources = Map.empty))

          case HandleRadioEvent(event) =>
            event match {
              case RadioMetadataEvent(source, data@CurrentMetadata(_), _)
                if !state.disabledSources.contains(source) =>
                val sourceConfig = state.metaConfig.metadataSourceConfig(source)
                findMetadataExclusion(state.metaConfig, sourceConfig, data) map { exclusion =>
                  val checkName = s"$SourceCheckActorNamePrefix${state.count}"
                  val checkBehavior = sourceCheckFactory(source,
                    checkName,
                    radioConfig,
                    state.metaConfig,
                    exclusion,
                    context.self,
                    scheduleActor,
                    clock,
                    streamBuilder,
                    intervalService)
                  val checkActor = context.spawn(checkBehavior, checkName)
                  enabledStateActor ! RadioControlProtocol.DisableSource(source)
                  handle(state.disable(source, checkActor))
                } getOrElse Behaviors.same
              case _ =>
                Behaviors.same
            }

          case SourceCheckSucceeded(source) =>
            state.enableIfDisabled(source) map { nextState =>
              enabledStateActor ! RadioControlProtocol.EnableSource(source)
              handle(nextState)
            } getOrElse Behaviors.same

          case SourceCheckTimeout(source, checkActor) =>
            if (state.disabledSources.contains(source)) {
              checkActor ! CancelSourceCheck(terminate = false)
            }
            Behaviors.same
        }

      handle(MetadataExclusionState(MetadataConfig.Empty, Map.empty, 1))
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
  private[control] trait SourceCheckFactory {
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
      * @param streamBuilder    the stream builder
      * @param intervalService  the intervals service
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
              streamBuilder: RadioStreamBuilder,
              intervalService: EvaluateIntervalsService,
              runnerFactory: MetadataCheckRunnerFactory = checkRunnerBehavior): Behavior[SourceCheckCommand]
  }

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
     streamBuilder: RadioStreamBuilder,
     intervalService: EvaluateIntervalsService,
     runnerFactory: MetadataCheckRunnerFactory) => Behaviors.setup { context =>
      def handleWaitForNextCheck(exclusion: MetadataExclusion, count: Int): Behavior[SourceCheckCommand] =
        Behaviors.receiveMessagePartial {
          case CancelSourceCheck(terminate) =>
            if (terminate) Behaviors.stopped else Behaviors.same

          case RunSourceCheck =>
            val nextCount = count + 1
            context.log.info("Triggering metadata check {} for {}.", nextCount, source)
            val runnerBehavior = runnerFactory(source,
              namePrefix + "_run" + nextCount,
              radioConfig.playerConfig,
              metadataConfig,
              exclusion,
              clock,
              streamBuilder,
              intervalService,
              context.self)
            val runner = context.spawn(runnerBehavior, namePrefix + nextCount)

            val timeout = ScheduledInvocationActor.typedInvocationCommand(radioConfig.metadataCheckTimeout,
              stateActor,
              SourceCheckTimeout(source, context.self))
            scheduleActor ! timeout
            handleCheckInProgress(runner, nextCount)

          case NextResumeInterval(time, result) =>
            val timeUntilNextResumeInterval = result match {
              case Before(start) => durationBetween(time, start.value, exclusion.checkInterval)
              case _ => exclusion.checkInterval
            }
            val nextCheckDelay = timeUntilNextResumeInterval.min(exclusion.checkInterval)
            context.log.info("Scheduling next metadata check for {} after {}.", source, nextCheckDelay)
            scheduleActor ! ScheduledInvocationActor.typedInvocationCommand(nextCheckDelay,
              context.self, RunSourceCheck)
            handleWaitForNextCheck(exclusion, count)
        }

      def handleCheckInProgress(checkRunner: ActorRef[MetadataCheckRunnerCommand],
                                count: Int): Behavior[SourceCheckCommand] =
        Behaviors.receiveMessagePartial {
          case CancelSourceCheck(terminate) =>
            checkRunner ! MetadataCheckRunnerTimeout
            if (terminate) handleWaitForTermination() else Behaviors.same

          case MetadataCheckResult(None) =>
            context.log.info("Metadata check successful for {}.", source)
            stateActor ! SourceCheckSucceeded(source)
            Behaviors.stopped

          case MetadataCheckResult(Some(exclusion)) =>
            queryResumeIntervals(exclusion, count)
        }

      def handleWaitForTermination(): Behavior[SourceCheckCommand] =
        Behaviors.receiveMessagePartial {
          case _: MetadataCheckResult =>
            Behaviors.stopped
        }

      def queryResumeIntervals(exclusion: MetadataExclusion, count: Int): Behavior[SourceCheckCommand] = {
        implicit val ec: ExecutionContext = context.executionContext
        val refTime = time(clock)
        val metadataSourceConfig = metadataConfig.metadataSourceConfig(source)
        intervalService.evaluateIntervals(metadataSourceConfig.resumeIntervals, refTime, 0) foreach { result =>
          context.self ! NextResumeInterval(refTime, result.result)
        }
        handleWaitForNextCheck(exclusion, count)
      }

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
    * A trait defining a factory function for an internal actor that executes
    * a single check on a radio source that is disabled because of its
    * current metadata. Instances are created periodically for affected
    * sources to check whether those sources can now be played again.
    */
  private[control] trait MetadataCheckRunnerFactory {
    /**
      * Returns the ''Behavior'' to create a new instance of the metadata check
      * runner actor.
      *
      * @param source           the radio source to be checked
      * @param namePrefix       prefix to generate actor names
      * @param playerConfig     the audio player config
      * @param metadataConfig   the global metadata config
      * @param currentExclusion the currently detected metadata exclusion
      * @param clock            the clock to query times
      * @param streamBuilder    the stream builder
      * @param intervalService  the interval query service
      * @param sourceChecker    the source checker parent actor
      * @param retrieverFactory the factory to create a retriever actor
      * @return the ''Behavior'' to create a new actor instance
      */
    def apply(source: RadioSource,
              namePrefix: String,
              playerConfig: PlayerConfig,
              metadataConfig: MetadataConfig,
              currentExclusion: MetadataExclusion,
              clock: Clock,
              streamBuilder: RadioStreamBuilder,
              intervalService: EvaluateIntervalsService,
              sourceChecker: ActorRef[SourceCheckCommand],
              retrieverFactory: MetadataRetrieveActorFactory = retrieveMetadataBehavior):
    Behavior[MetadataCheckRunnerCommand]
  }

  /**
    * An internal data class holding the information required while running a
    * single check for metadata exclusions.
    *
    * @param currentExclusion  the currently active exclusion
    * @param optResumeInterval the result of the latest query for resume
    *                          intervals
    */
  private case class CheckState(currentExclusion: MetadataExclusion,
                                optResumeInterval: Option[IntervalQueryResult]) {
    /**
      * Returns the last cached interval query result for the resume interval
      * if it is available and if it is still valid for the reference time
      * specified.
      *
      * @param time the reference time
      * @return an ''Option'' with the resume interval query result
      */
    def resumeIntervalAt(time: LocalDateTime): Option[IntervalQueryResult] =
      optResumeInterval flatMap {
        case r@Before(start) if time.isBefore(start.value) => Some(r)
        case r@Inside(until) if time.isBefore(until.value) => Some(r)
        case _ => None
      }
  }

  /**
    * A default [[MetadataCheckRunnerFactory]] instance that can be used to
    * create instances of the check runner actor.
    */
  private[control] val checkRunnerBehavior: MetadataCheckRunnerFactory =
    (source: RadioSource,
     namePrefix: String,
     playerConfig: PlayerConfig,
     metadataConfig: MetadataConfig,
     currentExclusion: MetadataExclusion,
     clock: Clock,
     streamBuilder: RadioStreamBuilder,
     intervalService: EvaluateIntervalsService,
     sourceChecker: ActorRef[SourceCheckCommand],
     retrieverFactory: MetadataRetrieveActorFactory) => Behaviors.setup[MetadataCheckRunnerCommand] { context =>
      implicit val ec: ExecutionContext = context.executionContext
      val retrieverBehavior = retrieverFactory(source, playerConfig, clock, streamBuilder, context.self)
      val retriever = context.spawn(retrieverBehavior, namePrefix + "_retriever")
      retriever ! GetMetadata
      val metadataSourceConfig = metadataConfig.metadataSourceConfig(source)

      def handle(state: CheckState): Behavior[MetadataCheckRunnerCommand] =
        Behaviors.receiveMessage {
          case MetadataRetrieved(data, time) =>
            findMetadataExclusion(metadataConfig, metadataSourceConfig, data) match {
              case Some(exclusion) =>
                retriever ! GetMetadata
                handle(state.copy(currentExclusion = exclusion))
              case None if state.currentExclusion.resumeMode == ResumeMode.MetadataChange =>
                terminateCheck(None)
              case None =>
                if (metadataSourceConfig.optSongPattern.isEmpty ||
                  matches(metadataSourceConfig.optSongPattern.get, data.title)) {
                  terminateCheck(None)
                } else {
                  state.resumeIntervalAt(time) match {
                    case Some(value) =>
                      handleResumeIntervalResult(value, state)
                    case None =>
                      intervalService.evaluateIntervals(metadataSourceConfig.resumeIntervals, time,
                        0) foreach { res =>
                        context.self ! ResumeIntervalResult(res.result)
                      }
                      Behaviors.same
                  }
                }
            }

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
        }

      def handleTimeout(result: Option[MetadataExclusion]): Behavior[MetadataCheckRunnerCommand] =
        Behaviors.receiveMessagePartial {
          case RadioStreamStopped =>
            sourceChecker ! MetadataCheckResult(result)
            Behaviors.stopped
        }

      def terminateCheck(result: Option[MetadataExclusion]): Behavior[MetadataCheckRunnerCommand] = {
        retriever ! CancelStream
        handleTimeout(result)
      }

      def handleResumeIntervalResult(result: IntervalQueryResult,
                                     nextState: CheckState): Behavior[MetadataCheckRunnerCommand] =
        if (isInResumeInterval(result)) {
          terminateCheck(None)
        } else {
          retriever ! GetMetadata
          handle(nextState)
        }

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
    result match {
      case Inside(_) => true
      case _ => false
    }

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
    * future with the stream builder result completes.
    *
    * @param triedResult the tried stream builder result
    */
  private case class StreamBuilderResultArrived(triedResult: Try[RadioStreamBuilder.BuilderResult[Future[Done],
    Future[Done]]]) extends MetadataRetrieveCommand

  /**
    * An internal command the metadata retriever actor sends to itself when a
    * chunk of metadata was received.
    *
    * @param data the raw metadata
    */
  private case class MetadataArrived(data: ByteString) extends MetadataRetrieveCommand

  /**
    * A data class holding the information required while fetching metadata
    * from a radio stream.
    *
    * @param optMetadata    stores the latest metadata encountered if any
    * @param metadataTime   the time when the metadata was received
    * @param lastMetadata   the last metadata sent to the check runner
    * @param killSwitch     the kill switch to cancel the stream
    * @param requestPending flag whether metadata has been requested
    */
  private case class MetadataRetrieveState(optMetadata: Option[CurrentMetadata],
                                           metadataTime: LocalDateTime,
                                           lastMetadata: Option[CurrentMetadata],
                                           killSwitch: KillSwitch,
                                           requestPending: Boolean) {
    /**
      * Returns a [[MetadataRetrieved]] message to be sent to the check runner
      * actor if all criteria are fulfilled. Otherwise, result is ''None''. In
      * addition, an updated state is returned.
      *
      * @return an optional message to send and an updated state
      */
    def messageToSend(): (Option[MetadataRetrieved], MetadataRetrieveState) =
      if (requestPending && optMetadata.isDefined && optMetadata != lastMetadata)
        (optMetadata.map { data => MetadataRetrieved(data, metadataTime) },
          copy(requestPending = false, lastMetadata = optMetadata))
      else (None, this)
  }

  /**
    * A trait defining a factory function for creating an internal actor that
    * retrieves metadata from a specific radio stream.
    */
  private[control] trait MetadataRetrieveActorFactory {
    /**
      * Returns a ''Behavior'' for a new actor instance to retrieve metadata
      * from a radio stream.
      *
      * @param source        the source of the radio stream
      * @param config        the player configuration
      * @param clock         a clock for obtaining the current time
      * @param streamBuilder the object for building radio streams
      * @param checkRunner   the actor reference for sending replies
      * @return the ''Behavior'' for the new instance
      */
    def apply(source: RadioSource,
              config: PlayerConfig,
              clock: Clock,
              streamBuilder: RadioStreamBuilder,
              checkRunner: ActorRef[MetadataCheckRunnerCommand]): Behavior[MetadataRetrieveCommand]
  }

  /**
    * A default [[MetadataRetrieveActorFactory]] implementation that can be
    * used to create instances of the metadata retriever actor.
    */
  private[control] val retrieveMetadataBehavior: MetadataRetrieveActorFactory =
    (source: RadioSource,
     config: PlayerConfig,
     clock: Clock,
     streamBuilder: RadioStreamBuilder,
     checkRunner: ActorRef[MetadataCheckRunnerCommand]) => Behaviors.setup { context =>
      implicit val mat: Materializer = Materializer(context)
      implicit val ec: ExecutionContextExecutor = context.system.executionContext
      val sinkAudio = Sink.ignore
      val sinkMeta = Sink.foreach[ByteString] { data =>
        context.self ! MetadataArrived(data)
      }

      streamBuilder.buildRadioStream(config, source.uri, sinkAudio, sinkMeta) onComplete { triedResult =>
        context.self ! StreamBuilderResultArrived(triedResult)
      }

      def streamInitializing(requestPending: Boolean, streamCanceled: Boolean): Behavior[MetadataRetrieveCommand] =
        Behaviors.receiveMessagePartial {
          case StreamBuilderResultArrived(triedResult) =>
            triedResult match {
              case Success(result) =>
                if (streamCanceled) context.self ! CancelStream

                // Start the stream, even if it was canceled, to ensure that proper cleanup is performed.
                result.graph.run()._2 onComplete { _ =>
                  checkRunner ! RadioStreamStopped
                }
                val retrieveState = MetadataRetrieveState(optMetadata = None,
                  metadataTime = LocalDateTime.now(),
                  lastMetadata = None,
                  killSwitch = result.killSwitch,
                  requestPending = requestPending)
                handle(retrieveState)

              case Failure(exception) =>
                context.log.error("Could not open radio stream.", exception)
                checkRunner ! RadioStreamStopped
                Behaviors.same
            }

          case GetMetadata =>
            streamInitializing(requestPending = true, streamCanceled)

          case CancelStream =>
            streamInitializing(requestPending, streamCanceled = true)
        }

      def handle(retrieveState: MetadataRetrieveState): Behavior[MetadataRetrieveCommand] =
        Behaviors.receiveMessagePartial {
          case MetadataArrived(data) =>
            val metadata = CurrentMetadata(data.utf8String)
            sendMetadataIfPossible(retrieveState.copy(optMetadata = Some(metadata), metadataTime = time(clock)))

          case GetMetadata =>
            sendMetadataIfPossible(retrieveState.copy(requestPending = true))

          case CancelStream =>
            retrieveState.killSwitch.shutdown()
            Behaviors.same
        }

      def sendMetadataIfPossible(state: MetadataRetrieveState): Behavior[MetadataRetrieveCommand] = {
        val (optMetadata, nextState) = state.messageToSend()
        optMetadata.foreach(checkRunner.!)
        handle(nextState)
      }

      streamInitializing(requestPending = false, streamCanceled = false)
    }

  /**
    * Returns the current local time from the given [[Clock]].
    *
    * @param clock the clock
    * @return the current time of the clock as local time
    */
  private def time(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
