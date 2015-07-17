package de.oliver_heger.splaya.playback

import java.io.InputStream

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
