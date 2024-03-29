/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.stream

import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.{PlaybackActor, PlaybackProtocolViolation}
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource, RadioSourceChangedEvent, RadioSourceErrorEvent}
import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, SupervisorStrategy, Terminated, typed}

object RadioDataSourceActor:
  /**
    * Creates a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param config        the player configuration
    * @param eventManager  the actor for generating events
    * @param streamManager the actor managing radio stream actors
    * @return the ''Props'' for creating a new actor instance
    */
  def apply(config: PlayerConfig,
            eventManager: typed.ActorRef[RadioEvent],
            streamManager: typed.ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand]): Props =
    Props(classOf[RadioDataSourceActor], config, eventManager, streamManager)

  /** Error message for an unexpected audio source request. */
  private val ErrPendingSourceRequest = "Request for audio source already pending!"

  /** Error message for an unexpected audio data request. */
  private val ErrPendingDataRequest = "Request for audio data already pending!"

  /** A message indicating the end of the current source. */
  private val SourceEndMessage = BufferDataComplete

  /**
    * Adapts the URI of the given audio source if necessary. If the current URI
    * does not have a file extension and a default extension is defined in the
    * radio source, the URI is adapted accordingly.
    *
    * @param src         the audio source in question
    * @param radioSource the radio source
    * @return the adapted audio source
    */
  private def updateAudioSourceFileExtension(src: AudioSource, radioSource: RadioSource):
  AudioSource =
    radioSource.defaultExtension
      .map(e => updateAudioSourceWithDefaultFileExtension(src, e)) getOrElse src

  /**
    * Adapts the URI of the given audio source with the specified default file
    * extension. The extension is simply added to the URI, no matter if there
    * is already an extension. Note that the resulting URI does not need to be
    * correct; the extension is evaluated by the playback actor when the
    * playback context is created.
    *
    * @param src        the audio source in question
    * @param defaultExt the default file extension
    * @return the adapted audio source
    */
  private def updateAudioSourceWithDefaultFileExtension(src: AudioSource, defaultExt: String):
  AudioSource =
    val dotExt = s".$defaultExt"
    if !src.uri.endsWith(dotExt) then src.copy(uri = src.uri + dotExt)
    else src

  /**
    * Handles a pending request by applying the specified function. If the
    * given option is defined, the actor it contains is passed to the
    * provided function. Result is always ''None'', indicating that the pending
    * request has been served.
    *
    * @param req the optional pending request
    * @param f   the function to be applied on the requesting actor
    * @return an empty option
    */
  private def servePending(req: Option[ActorRef])(f: ActorRef => Unit): Option[ActorRef] =
    req foreach f
    None

  /**
    * Handles a pending request by sending the specified message. If the given
    * option is defined, the provided message is sent to this actor. Result is
    * always ''None'', indicating that the pending request has been served.
    *
    * @param req the optional pending request
    * @param msg the message to be sent
    * @return an empty option
    */
  private def servePending(req: Option[ActorRef], msg: => Any): Option[ActorRef] =
    lazy val message = msg
    servePending(req)(_ ! message)

/**
  * An actor class acting as a data source for a [[PlaybackActor]] for playing
  * a radio stream.
  *
  * This actor class expects messages that identify the radio stream to be
  * played. For each stream to be played a [[RadioStreamActor]] instance
  * is created. This actor opens the stream and answers requests for audio
  * data. When another message identifying an audio stream is received the
  * current stream is closed, and playback starts with the new stream. (The
  * playback actor then is passed an end-of-file message so that it will
  * request a new audio source and create a new playback context.)
  *
  * If an error occurs when playing an audio stream - which is caused by an
  * IO error in one of the child actors -, playback of the current stream is
  * also stopped by sending the playback actor an end-of-file message on its
  * next request for audio data.
  *
  * @param config        the audio player configuration
  * @param eventManager  the actor for generating events
  * @param streamManager the actor managing radio stream actors
  */
class RadioDataSourceActor(config: PlayerConfig,
                           eventManager: typed.ActorRef[RadioEvent],
                           streamManager: typed.ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand])
  extends Actor with ActorLogging:

  import RadioDataSourceActor._

  /** Stores the current source reader child actor. */
  private var currentSourceReader: Option[ActorRef] = None

  /** Stores a pending request for the current audio source. */
  private var pendingAudioSourceRequest: Option[ActorRef] = None

  /** Stores a pending request for audio data. */
  private var pendingAudioDataRequest: Option[ActorRef] = None

  /** Stores the current audio source. */
  private var currentAudioSource: Option[AudioSource] = None

  /** Stores the current radio source. */
  private var currentRadioSource: RadioSource = _

  /** Records CloseAck messages from sources that are still pending. */
  private var pendingCloseAck = 0

  /**
    * A flag whether a new source has been added. Then the current source has
    * to be stopped, and playback of the new source has to be prepared.
    */
  private var newSource = true

  /**
    * This actor uses a supervision strategy that stops a child actor when it
    * encounters an IO exception.
    */
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy():
    case _: java.io.IOException => Stop

  /**
    * The source listener function to be passed when creating a new radio
    * stream actor. This function sends the source to Self.
    */
  private val sourceListener: RadioStreamActor.SourceListener = (source, sender) => self.tell(source, sender)

  override def receive: Receive =
    case r: RadioSource =>
      currentSourceReader foreach { r =>
        r ! CloseRequest
        pendingCloseAck += 1
      }
      val params = RadioStreamManagerActor.StreamActorParameters(r, sourceListener, eventManager)
      streamManager ! RadioStreamManagerActor.GetStreamActorClassic(params, self)
      stopCurrentSource()

    case RadioStreamManagerActor.StreamActorResponse(r, streamActor) =>
      currentSourceReader = Some(streamActor)
      context watch streamActor
      currentRadioSource = r
      eventManager ! RadioSourceChangedEvent(r)
      log.info("Next radio source for playback: {}.", r.uri)

    case src: AudioSource if fromCurrentReader() =>
      val updatedSrc = updateAudioSourceFileExtension(src, currentRadioSource)
      currentAudioSource = Some(updatedSrc)
      pendingAudioSourceRequest = servePending(pendingAudioSourceRequest)(handleSourceRequest(_, updatedSrc))

    case PlaybackActor.GetAudioSource =>
      if pendingAudioSourceRequest.isDefined then
        sender() ! PlaybackProtocolViolation(PlaybackActor.GetAudioSource, ErrPendingSourceRequest)
      else
        currentAudioSource match
          case Some(s) => handleSourceRequest(sender(), s)
          case None => pendingAudioSourceRequest = Some(sender())

    case req: PlaybackActor.GetAudioData =>
      if pendingAudioDataRequest.isDefined then
        sender() ! PlaybackProtocolViolation(req, ErrPendingDataRequest)
      else
        currentSourceReader match
          case Some(r) if !newSource =>
            r ! req
            pendingAudioDataRequest = Some(sender())
          case _ => sender() ! SourceEndMessage

    case data: BufferDataResult if fromCurrentReader() =>
      pendingAudioDataRequest = servePending(pendingAudioDataRequest, data)

    case CloseAck(_) =>
      handleCloseAck()

    case Terminated(_) =>
      log.warning("Actor for current data source died!")
      eventManager ! RadioSourceErrorEvent(currentRadioSource)
      pendingAudioSourceRequest = servePending(pendingAudioSourceRequest, AudioSource.ErrorSource)
      stopCurrentSource()
      currentSourceReader = None

    case CloseRequest =>
      currentSourceReader foreach { r =>
        r ! CloseRequest
        pendingCloseAck += 1
      }
      sendCloseAckIfPossible(sender())
      context become closing(sender())

  /**
    * Returns a receive function for the state closing (after the actor
    * received a CloseRequest message). In this state only ''CloseAck''
    * messages are processed. A ''CloseAck'' is sent to the client not before
    * all outstanding ''CloseAck'' messages from radio sources are received.
    *
    * @param client the client requesting the close
    * @return the receive function for this state
    */
  private def closing(client: ActorRef): Receive =
    case CloseAck(_) =>
      handleCloseAck()
      sendCloseAckIfPossible(client)

  /**
    * Modifies internal state to stop the current source.
    */
  private def stopCurrentSource(): Unit =
    newSource = true
    pendingAudioDataRequest = servePending(pendingAudioDataRequest, SourceEndMessage)
    currentAudioSource = None

  /**
    * Checks whether the sending actor is the current source reader actor.
    *
    * @return '''true''' if the current message was sent from the current
    *         source reader actor
    */
  private def fromCurrentReader(): Boolean =
    sender() == currentSourceReader.orNull

  /**
    * Handles a request for the current audio source. The source is passed to
    * the given actor, and some internal flags are updated.
    *
    * @param client the client actor
    * @param source the audio source
    */
  private def handleSourceRequest(client: ActorRef, source: AudioSource): Unit =
    client ! source
    newSource = false

  /**
    * Handle a CloseAck message. The number of pending Ack messages is
    * decreased.
    */
  private def handleCloseAck(): Unit =
    context unwatch sender()
    context stop sender()
    pendingCloseAck -= 1

  /**
    * Sends a ''CloseAck'' message to the specified client actor if this actor
    * is fully closed. All outstanding ''CloseAck'' messages from source
    * reader actors have been received.
    *
    * @param client the client actor
    */
  private def sendCloseAckIfPossible(client: ActorRef): Unit =
    if pendingCloseAck == 0 then
      client ! CloseAck(self)
