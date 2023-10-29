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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.player.engine.{PlaybackContext, PlaybackContextFactory}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.{actor => classic}

import java.io.InputStream

/**
  * An actor implementation that manages the [[PlaybackContextFactory]]s known
  * by the player engine.
  *
  * The actor supports messages for adding and removing factories. In addition,
  * it can be asked to create a [[PlaybackContext]] for a given audio stream.
  */
object PlaybackContextFactoryActor {
  /**
    * The base trait for the commands supported by this actor.
    */
  sealed trait PlaybackContextCommand

  /**
    * A command to add a new [[PlaybackContextFactory]].
    *
    * @param factory the factory to add
    */
  case class AddPlaybackContextFactory(factory: PlaybackContextFactory) extends PlaybackContextCommand

  /**
    * A command to remove a [[PlaybackContextFactory]].
    *
    * @param factory the factory to remove
    */
  case class RemovePlaybackContextFactory(factory: PlaybackContextFactory) extends PlaybackContextCommand

  /**
    * A command to request the creation of a [[PlaybackContext]] for an audio
    * stream. The actor checks the managed factories whether one is able to
    * handle the stream and create a context. The result is then sent back to
    * the given receiver via a [[CreatePlaybackContextResult]] message.
    *
    * @param stream   the audio input stream
    * @param uri      the URI associated with the stream
    * @param receiver the receiver of the result
    */
  case class CreatePlaybackContext(stream: InputStream,
                                   uri: String,
                                   receiver: classic.ActorRef) extends PlaybackContextCommand

  /**
    * A command telling this actor to stop itself.
    */
  case object Stop extends PlaybackContextCommand

  /**
    * A message sent by this actor as response to a [[CreatePlaybackContext]]
    * message. The context creation can fail; therefore, this class stores an
    * ''Option''.
    *
    * @param optContext the result of the creation
    */
  case class CreatePlaybackContextResult(optContext: Option[PlaybackContext])

  /**
    * A specialized implementation of a [[PlaybackContextFactory]] which contains
    * a number of sub factories.
    *
    * When a new [[PlaybackContext]] is to be created the sub factories are
    * invoked one after each other until a one is found that returns a valid
    * context object. This result is then returned.
    *
    * There are methods for adding and removing sub factories. These methods do
    * not change the current instance, but create a new one with an updated list
    * of sub factories.
    */
  private class CombinedPlaybackContextFactory(val subFactories: List[PlaybackContextFactory])
    extends PlaybackContextFactory {
    /**
      * Creates a new instance of ''CombinedPlaybackContextFactory'' which has the
      * specified ''PlaybackContextFactory'' as a sub factory.
      *
      * @param factory the factory to be added
      * @return a new combined factory containing the specified sub factory
      */
    def addSubFactory(factory: PlaybackContextFactory): CombinedPlaybackContextFactory =
      new CombinedPlaybackContextFactory(factory :: subFactories)

    /**
      * Creates a new instance of ''CombinedPlaybackContextFactory'' which does
      * not contain the specified ''PlaybackContextFactory'' as a sub factory.
      *
      * @param factory the factory to be removed
      * @return a new combined factory without the specified sub factory
      */
    def removeSubFactory(factory: PlaybackContextFactory): CombinedPlaybackContextFactory =
      new CombinedPlaybackContextFactory(subFactories filterNot (_ == factory))

    /**
      * @inheritdoc This implementation iterates over the contained sub factories
      *             (in no specific order). The first ''PlaybackContext'' which is
      *             created is returned.
      */
    override def createPlaybackContext(stream: InputStream, uri: String): Option[PlaybackContext] = {
      subFactories.foldLeft[Option[PlaybackContext]](None) { (optCtx, f) =>
        optCtx orElse f.createPlaybackContext(stream, uri)
      }
    }
  }

  /**
    * Returns a ''Behavior'' to create a new actor instance.
    *
    * @return the ''Behavior'' for the new instance
    */
  def apply(): Behavior[PlaybackContextCommand] =
    handle(new CombinedPlaybackContextFactory(Nil))

  /**
    * The main message handling function of this actor.
    *
    * @param combinedFactory the combined factory storing all currently
    *                        available factories
    * @return the updated ''Behavior''
    */
  private def handle(combinedFactory: CombinedPlaybackContextFactory): Behavior[PlaybackContextCommand] =
    Behaviors.receiveMessage {
      case AddPlaybackContextFactory(factory) =>
        handle(combinedFactory.addSubFactory(factory))

      case RemovePlaybackContextFactory(factory) =>
        handle(combinedFactory.removeSubFactory(factory))

      case CreatePlaybackContext(stream, uri, receiver) =>
        val context = combinedFactory.createPlaybackContext(stream, uri)
        receiver ! CreatePlaybackContextResult(context)
        Behaviors.same

      case Stop =>
        Behaviors.stopped
    }
}
