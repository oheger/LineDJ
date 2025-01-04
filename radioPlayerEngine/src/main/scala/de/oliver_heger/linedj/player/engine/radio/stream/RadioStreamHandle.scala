/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamHandle.SinkType
import org.apache.pekko.NotUsed
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Failure

object RadioStreamHandle:
  /**
    * A type alias for the result type of the sinks passed to the stream
    * builder. These are attachable sinks, therefore, they materialize an actor
    * that can be used to control them.
    */
  type SinkType = ActorRef[AttachableSink.AttachableSinkControlCommand[ByteString]]

  /**
    * A trait defining a factory function for [[RadioStreamHandle]] instances
    * based on a [[RadioStreamBuilder]]. Since the setup of radio streams and
    * their handles is not trivial, a dedicated factory trait is introduced for
    * this purpose. This also improves testability, since a stub or mock
    * factory can be created easily.
    */
  trait Factory:
    /**
      * Creates a radio stream using the given [[RadioStreamBuilder]] with the
      * provided parameters. Starts this stream and returns a
      * [[RadioStreamHandle]] to it.
      *
      * @param builder    the builder to construct the radio stream
      * @param streamUri  the URI of the stream
      * @param bufferSize the size of the playback buffer
      * @param streamName a name for the stream; if multiple streams are active
      *                   in parallel, a unique name must be provided here
      * @param system     the implicit actor system
      * @return a [[Future]] with the newly created instance for the specified
      *         radio stream
      */
    def create(builder: RadioStreamBuilder,
               streamUri: String,
               bufferSize: Int = RadioStreamBuilder.DefaultBufferSize,
               streamName: String = "radioStream")
              (using system: classic.ActorSystem): Future[RadioStreamHandle]
  end Factory

  /**
    * A default implementation of the [[Factory]] trait that is fully
    * functional and can be used to create new [[RadioStreamHandle]] instances.
    */
  final val factory: Factory = new Factory:
    override def create(builder: RadioStreamBuilder,
                        streamUri: String,
                        bufferSize: Int,
                        streamName: String)
                       (using system: classic.ActorSystem): Future[RadioStreamHandle] =
      val audioSink = AttachableSink[ByteString](s"${streamName}_audioStream")
      val metaSink = AttachableSink[ByteString](s"${streamName}_metadataStream", buffered = true)
      val params = RadioStreamBuilder.RadioStreamParameters(
        streamUri = streamUri,
        sinkAudio = audioSink,
        sinkMeta = metaSink,
        bufferSize = bufferSize
      )
      builder.buildRadioStream(params).map { result =>
        val (audioSinkCtrl, metaSinkCtrl) = result.graph.run()
        RadioStreamHandle(audioSinkCtrl, metaSinkCtrl, result, createStreamWatcherActor(audioSinkCtrl, streamName))
      }

  /**
    * A ''given'' to obtain the [[ExecutionContext]] from a given actor system.
    *
    * @param system the actor system
    * @return the execution context from the actor system
    */
  private given executionContextFromSystem(using system: classic.ActorSystem): ExecutionContext = system.dispatcher

  /**
    * An internal message that tells the stream completion watcher actor that
    * the monitored stream has completed.
    *
    * @param notifyPromise the promise to propagate the notification
    * @param streamName    the name of the affected stream
    */
  private case class StreamCompleted(notifyPromise: Promise[Unit],
                                     streamName: String)

  /**
    * Returns a [[Future]] that gets completed when the radio stream controlled
    * by the given actor completes. This function spawns another actor that
    * watches the control actor. When it terminates, this means that the radio
    * stream has been completed. Via the future returned by this function, it
    * is easy to find out when the stream is done.
    *
    * @param ctrlActor  the actor controlling the audio stream
    * @param streamName the name of the radio stream
    * @param system     the actor system
    * @return a [[Future]] that completes when the stream completes
    */
  private def createStreamWatcherActor(ctrlActor: SinkType, streamName: String)
                                      (using system: classic.ActorSystem): Future[Unit] =
    val promise = Promise[Unit]()
    val notifyMessage = StreamCompleted(promise, streamName)
    system.spawn(handleWatchStream(ctrlActor, notifyMessage), s"${streamName}_watcher")
    promise.future

  /**
    * The message handling function of the stream watcher actor.
    *
    * @return the next behavior function for the actor
    */
  private def handleWatchStream(ctrActor: SinkType, msg: StreamCompleted): Behavior[StreamCompleted] =
    Behaviors.setup[StreamCompleted] { context =>
      context.watchWith(ctrActor, msg)
      context.log.info("Watching for death of control actor for radio stream '{}'.", msg.streamName)

      Behaviors.receive {
        case (context, StreamCompleted(promise, streamName)) =>
          context.log.info("Received notification about completed radio stream '{}'.", streamName)
          promise.success(())
          Behaviors.stopped
      }
    }
end RadioStreamHandle

/**
  * A data class storing data for accessing a radio stream.
  *
  * An instance of this class is created from a [[RadioStreamBuilder]] by
  * passing in [[AttachableSink]] objects for the audio data and metadata. It
  * holds the control actors for those sinks and provides some convenience
  * functions to interact with them. Using this class, a radio stream can be
  * attached to concrete sources, detached, and completely canceled. It is also
  * possible to monitor when the stream completes by using the [[Future]]
  * property.
  *
  * @param audioSinkControl the control actor for the audio data sink
  * @param metaSinkControl  the control actor for the metadata sink
  * @param builderResult    the original result of the [[RadioStreamBuilder]]
  */
case class RadioStreamHandle(audioSinkControl: ActorRef[AttachableSink.AttachableSinkControlCommand[ByteString]],
                             metaSinkControl: ActorRef[AttachableSink.AttachableSinkControlCommand[ByteString]],
                             builderResult: RadioStreamBuilder.BuilderResult[SinkType, SinkType],
                             futStreamDone: Future[Unit]):

  import RadioStreamHandle.executionContextFromSystem

  /**
    * Convenience function to attach to both the audio sink and the metadata
    * sink of the associated radio stream. The function returns a [[Future]]
    * with a tuple of sources that can be used to stream the audio and metadata
    * of the radio stream.
    *
    * @param timeout a timeout for the operations to attach to the sinks
    * @param system  the implicit actor system
    * @return a [[Future]] with the sources to obtain the radio stream data
    */
  def attach(timeout: Timeout = AttachableSink.DefaultAttachTimeout)
            (using system: classic.ActorSystem): Future[(Source[ByteString, NotUsed], Source[ByteString, NotUsed])] =
    val futAudioSource = attachAudioSink(timeout)
    val futMetaSource = attachMetadataSink(timeout)
    for
      audioSource <- futAudioSource
      metaSource <- futMetaSource
    yield (audioSource, metaSource)

  /**
    * Tries to attach to both the audio sink and the metadata sink of the
    * associated radio stream and returns a [[Future]] with a tuple of the
    * resulting sources. If this operation fails, the
    * [[RadioStreamHandle.cancelStream]] function is called. This function
    * supports error handling in the typical use case of creating a radio 
    * stream and directly attaching to it. If the handle could be created, but
    * later attaching fails for whatever reason, the whole operation should be
    * treated as an error, and the stream should be released.
    *
    * @param timeout a timeout for the operations to attach to the sinks
    * @param system  the implicit actor system
    * @return a [[Future]] with the sources to obtain the radio stream data
    */
  def attachOrCancel(timeout: Timeout = AttachableSink.DefaultAttachTimeout)
                    (using system: classic.ActorSystem):
  Future[(Source[ByteString, NotUsed], Source[ByteString, NotUsed])] =
    cancelOnError(attach(timeout))

  /**
    * Attaches to the [[Sink]] for audio data of the managed stream and returns
    * a [[Future]] with a [[Source]] that can be used to stream this data.
    *
    * @param timeout a timeout for the operation to attach to the sink
    * @param system  the implicit actor system
    * @return a [[Future]] with the source to obtain the audio data of the 
    *         radio stream
    */
  def attachAudioSink(timeout: Timeout = AttachableSink.DefaultAttachTimeout)
                     (using system: classic.ActorSystem): Future[Source[ByteString, NotUsed]] =
    AttachableSink.attachConsumer(audioSinkControl, timeout)

  /**
    * Tries to attach to the [[Sink]] for audio data and returns a [[Future]]
    * with a [[Source]] that can be used to stream this data. If this operation
    * fails, this function calls the [[RadioStreamHandle.cancelStream]] method.
    * This is analogous to the [[RadioStreamHandle.attachOrCancel]] method, but
    * limit to the audio data source.
    *
    * @param timeout a timeout for the operation to attach to the sink
    * @param system  the implicit actor system
    * @return a [[Future]] with the source to obtain the audio data of the
    *         radio stream
    */
  def attachAudioSinkOrCancel(timeout: Timeout = AttachableSink.DefaultAttachTimeout)
                             (using system: classic.ActorSystem): Future[Source[ByteString, NotUsed]] =
    cancelOnError(attachAudioSink(timeout))

  /**
    * Attaches to the [[Sink]] for metadata of the managed stream and returns a
    * [[Future]] with a [[Source]] that can be used to obtain this data.
    *
    * @param timeout a timeout for the operation to attach to the sink
    * @param system  the implicit actor system
    * @return a [[Future]] with the source to obtain the metadata of the radio
    *         stream
    */
  def attachMetadataSink(timeout: Timeout = AttachableSink.DefaultAttachTimeout)
                        (using system: classic.ActorSystem): Future[Source[ByteString, NotUsed]] =
    AttachableSink.attachConsumer(metaSinkControl, timeout)

  /**
    * Tries to attach to the [[Sink]] for metadata and returns a [[Future]]
    * with a [[Source]] that can be used to stream this data. If this operation
    * fails, this function calls the [[RadioStreamHandle.cancelStream]] method.
    * This is analogous to the [[RadioStreamHandle.attachOrCancel]] method, but
    * limit to the metadata source.
    *
    * @param timeout a timeout for the operation to attach to the sink
    * @param system  the implicit actor system
    * @return a [[Future]] with the source to obtain the metadata of the radio
    *         stream
    */
  def attachMetadataSinkOrCancel(timeout: Timeout = AttachableSink.DefaultAttachTimeout)
                                (using system: classic.ActorSystem): Future[Source[ByteString, NotUsed]] =
    cancelOnError(attachMetadataSink(timeout))

  /**
    * Convenience function to send a request to detach from the sinks of the
    * radio streams to the managed control actors.
    */
  def detach(): Unit =
    AttachableSink.detachConsumer(audioSinkControl)
    AttachableSink.detachConsumer(metaSinkControl)

  /**
    * Convenience function to cancel the associated radio stream. No matter
    * whether the sinks are in attached state or not, the whole stream is
    * completed.
    */
  def cancelStream(): Unit =
    builderResult.killSwitch.shutdown()

  /**
    * Decorates the given [[Future]] with a side effect for error handling, so
    * that the associated stream is canceled in case of a failure.
    *
    * @param futResult the affected [[Future]]
    * @param system    the implicit actor system
    * @tparam A the type of the [[Future]]
    * @return the decorated [[Future]]
    */
  private def cancelOnError[A](futResult: Future[A])
                              (using system: classic.ActorSystem): Future[A] =
    futResult.andThen {
      case Failure(exception) => cancelStream()
    }
