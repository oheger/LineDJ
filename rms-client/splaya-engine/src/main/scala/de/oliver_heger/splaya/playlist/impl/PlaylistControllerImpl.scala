package de.oliver_heger.splaya.playlist.impl

import de.oliver_heger.splaya.engine.PlaylistController
import scala.actors.Actor
import de.oliver_heger.splaya.engine.msg.Exit

/**
 * The default implementation of the ''PlaylistController'' trait.
 *
 * This implementation is actually a thin wrapper around an actor controlling
 * the playlist. Methods are implemented by sending corresponding messages to
 * the wrapped actor.
 *
 * @param actor the actor implementing the actual functionality
 */
class PlaylistControllerImpl(actor: Actor) extends PlaylistController {
  /**
   * @inheritdoc This implementation sends a ''MoveTo'' message to the wrapped
   * actor.
   */
  def moveToSourceAt(idx: Int) {
    actor ! MoveTo(idx)
  }

  /**
   * @inheritdoc This implementation sends a ''MoveRelative'' message to the
   * wrapped actor.
   */
  def moveToSourceRelative(delta: Int) {
    actor ! MoveRelative(delta)
  }

  /**
   * @inheritdoc This implementation first tells the actor to save its current
   * playlist. Then a ''ReadMedium'' message is sent.
   */
  def readMedium(rootUri: String) {
    actor ! SavePlaylist
    actor ! ReadMedium(rootUri)
  }

  /**
   * @inheritdoc This implementation first tells the actor to save its current
   * playlist. Then an ''Exit'' message is sent.
   */
  def shutdown() {
    actor ! SavePlaylist
    actor ! Exit
  }
}
