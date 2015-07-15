package de.oliver_heger.splaya

/**
 * Definition of a factory service interface for creating
 * [[de.oliver_heger.splaya.AudioPlayer]] instances.
 *
 * This is a very basic interface which defines just a single factory method.
 */
trait AudioPlayerFactory {
  /**
   * Creates a new ''AudioPlayer'' instance.
   * @return the newly created player instance
   */
  def createAudioPlayer(): AudioPlayer
}
