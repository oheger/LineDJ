/*
 * Copyright 2015-2022 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.impl

import akka.actor.SupervisorStrategy.Escalate
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.impl.LocalBufferActor.BufferDataResult
import de.oliver_heger.linedj.player.engine.impl.PlaybackActor.GetAudioData
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

private object SourceStreamReaderActor {
  /** Error message for an unexpected audio data request. */
  private val ErrUnexpectedRequest =
    "[SourceStreamReaderActor] Unexpected request for audio data!"

  /**
    * A message this actors sends to itself when an audio stream could be
    * resolved. The m3u data was read, and the final audio stream URL was
    * discovered.
    *
    * @param audioStreamRef the resolved audio stream reference
    */
  private case class AudioStreamResolved(audioStreamRef: StreamReference)

  private class SourceStreamReaderActorImpl(config: PlayerConfig, streamRef: StreamReference,
                                            sourceListener: ActorRef, m3uReader: M3uReader)
    extends SourceStreamReaderActor(config, streamRef, sourceListener, m3uReader) with ChildActorFactory

  /**
    * Creates a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param config         the player configuration
    * @param streamRef      the reference to the audio stream for playback
    * @param sourceListener reference to an actor that is sent an audio source
    *                       message when the final audio stream is available
    * @param m3uReader      the object to resolve playlist references
    * @return creation properties for a new actor instance
    */
  def apply(config: PlayerConfig, streamRef: StreamReference, sourceListener: ActorRef,
            m3uReader: M3uReader = new M3uReader): Props =
    Props(classOf[SourceStreamReaderActorImpl], config, streamRef, sourceListener, m3uReader)
}

/**
  * An actor class that reads audio data from an internet radio stream.
  *
  * This actor implements part of the functionality of a data source for
  * [[PlaybackActor]]. It mainly combines resolving of an m3u stream reference
  * and managing a [[StreamBufferActor]] actor. An instance is initialized
  * with a [[StreamReference]]. If this reference points to a m3u file,
  * an [[M3uReader]] object is used to process the file and extract the
  * actual URI of the audio stream. Otherwise, the reference is considered to
  * already represent the audio stream.
  *
  * With the audio stream at hand a [[StreamBufferActor]] is created. This
  * actor handles ''GetAudioData'' messages by delegating to the stream buffer
  * actor. It also deals with the case that the buffer is not yet filled yet,
  * and thus an empty read result is returned. In this case, the request is
  * repeated.
  *
  * It is sometimes necessary to know to the actual URL of the audio stream
  * that is played. Therefore, this actor sends an ''AudioSource'' message to a
  * listener actor passed to the constructor when the final audio stream is
  * determined.
  *
  * Supervision is implemented by delegating to the parent actor.
  *
  * An instance of this actor class can only be used for reading a single
  * audio stream. It cannot be reused and has to be closed afterwards.
  *
  * @param config         the player configuration
  * @param streamRef      the reference to the audio stream for playback
  * @param sourceListener reference to an actor that is sent an audio source
  *                       message when the final audio stream is available
  * @param m3uReader      the object to resolve playlist references
  */
private class SourceStreamReaderActor(config: PlayerConfig, streamRef: StreamReference,
                                      sourceListener: ActorRef, m3uReader: M3uReader) extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import SourceStreamReaderActor._

  /** Stores the stream buffer actor. */
  private var bufferActor: Option[ActorRef] = None

  /** Stores a pending request for audio data. */
  private var pendingDataRequest: Option[PlaybackActor.GetAudioData] = None

  /** Stores the client that triggered a request for audio data. */
  private var dataClient: Option[ActorRef] = None

  /** The delegating supervisor strategy. */
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: java.io.IOException => Escalate
  }

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    implicit val mat: ActorSystem = context.system
    implicit val ec: ExecutionContext = context.dispatcher
    m3uReader.resolveAudioStream(config, streamRef) onComplete { triedReference =>
      val resultMsg = triedReference match {
        case Failure(exception) =>
          log.error("Resolving of stream reference failed.", exception)
          PoisonPill
        case Success(value) => AudioStreamResolved(value)
      }
      self ! resultMsg
    }
  }

  override def receive: Receive = {
    case AudioStreamResolved(ref) =>
      if (bufferActor.isEmpty) {
        bufferActor = createBufferActor(ref)
        sourceListener ! createAudioSourceMsg(ref)
        pendingDataRequest foreach bufferActor.get.!
      }

    case req: PlaybackActor.GetAudioData =>
      if (dataClient.isDefined) {
        sender() ! PlaybackProtocolViolation(req, ErrUnexpectedRequest)
      } else {
        sendDataRequest(req)
        pendingDataRequest = Some(req)
        dataClient = Some(sender())
      }

    case data: BufferDataResult =>
      if (data.data.nonEmpty) {
        dataClient foreach (_ ! data)
        dataClient = None
      } else {
        pendingDataRequest foreach sendDataRequest
      }

    case CloseRequest =>
      val bufferRef = bufferActor match {
        case Some(ref) =>
          ref ! CloseRequest
          ref
        case None =>
          sender() ! CloseAck(self)
          null
      }
      context become closing(sender(), bufferRef)
  }

  /**
    * A receive function that becomes active when this actor receives a
    * ''CloseRequest''. Afterwards no more messages are handled, only the
    * close ack from the buffer actor.
    *
    * @param client    the client that triggered the close operation
    * @param bufferRef reference to the buffer actor that is expected to send a
    *                  ''CloseAck'' message
    * @return the receive function
    */
  private def closing(client: ActorRef, bufferRef: ActorRef): Receive = {
    case CloseAck(ref) if ref == bufferRef =>
      client ! CloseAck(self)
  }

  /**
    * Sends a request to the buffer actor for audio data. If the buffer actor
    * is not available yet, this method does nothing.
    *
    * @param req the request to be sent
    */
  private def sendDataRequest(req: GetAudioData): Unit = {
    bufferActor foreach (_ ! req)
  }

  /**
    * Creates the actor that buffers the audio stream to be played.
    *
    * @param ref the reference to the audio stream
    * @return the ''StreamBufferActor'' reference
    */
  private def createBufferActor(ref: StreamReference): Option[ActorRef] =
    Some(createChildActor(config.applyBlockingDispatcher(Props(classOf[StreamBufferActor],
      config, ref))))

  /**
    * Creates an audio source message referencing the specified stream. This
    * message is sent to the audio source listener.
    *
    * @param ref the stream reference to the current audio stream
    * @return the ''AudioSource'' message
    */
  private def createAudioSourceMsg(ref: StreamReference): AudioSource =
    AudioSource(ref.uri, Long.MaxValue, 0, 0)
}
