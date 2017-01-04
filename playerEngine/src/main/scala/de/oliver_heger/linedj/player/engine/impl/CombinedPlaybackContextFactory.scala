/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.io.InputStream

import de.oliver_heger.linedj.player.engine.{PlaybackContext, PlaybackContextFactory}

/**
 * A specialized implementation of a ''PlaybackContextFactory'' which contains
 * a number of sub factories.
 *
 * When a new ''PlaybackContext'' is to be created the sub factories are
 * invoked one after each other until a one is found that returns a valid
 * context object. This result is then returned.
 *
 * There are methods for adding and removing sub factories. These methods do
 * not change the current instance, but create a new one with an updated list
 * of sub factories.
 */
class CombinedPlaybackContextFactory(val subFactories: List[PlaybackContextFactory]) extends
PlaybackContextFactory {
  /**
   * Creates a new instance of ''CombinedPlaybackContextFactory'' which has the
   * specified ''PlaybackContextFactory'' as a sub factory.
   * @param factory the factory to be added
   * @return a new combined factory containing the specified sub factory
   */
  def addSubFactory(factory: PlaybackContextFactory): CombinedPlaybackContextFactory =
    new CombinedPlaybackContextFactory(factory :: subFactories)

  /**
   * Creates a new instance of ''CombinedPlaybackContextFactory'' which does
   * not contain the specified ''PlaybackContextFactory'' as a sub factory.
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
