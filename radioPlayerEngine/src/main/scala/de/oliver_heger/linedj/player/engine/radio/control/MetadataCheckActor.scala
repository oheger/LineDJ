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
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamBuilder
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioSource}

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
object MetadataCheckActor {
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
      * @param source               the radio source to be checked
      * @param namePrefix           prefix to generate actor names
      * @param playerConfig         the audio player config
      * @param metadataConfig       the global metadata config
      * @param metadataSourceConfig the metadata config of the radio source
      * @param currentExclusion     the currently detected metadata exclusion
      * @param clock                the clock to query times
      * @param streamBuilder        the stream builder
      * @param intervalService      the interval query service
      * @param sourceChecker        the source checker parent actor
      * @param retrieverFactory     the factory to create a retriever actor
      * @return the ''Behavior'' to create a new actor instance
      */
    def apply(source: RadioSource,
              namePrefix: String,
              playerConfig: PlayerConfig,
              metadataConfig: MetadataConfig,
              metadataSourceConfig: RadioSourceMetadataConfig,
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
     metadataSourceConfig: RadioSourceMetadataConfig,
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
            val time = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            val metadata = CurrentMetadata(data.utf8String)
            sendMetadataIfPossible(retrieveState.copy(optMetadata = Some(metadata), metadataTime = time))

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
}
