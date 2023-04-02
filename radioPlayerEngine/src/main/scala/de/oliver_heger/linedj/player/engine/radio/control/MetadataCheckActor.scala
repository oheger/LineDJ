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
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamBuilder
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioSource}

import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContextExecutor, Future}
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
