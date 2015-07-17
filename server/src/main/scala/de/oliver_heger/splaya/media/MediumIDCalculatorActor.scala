package de.oliver_heger.splaya.media

import java.nio.file.Path

import akka.actor.Actor
import de.oliver_heger.splaya.media.MediumIDCalculatorActor.CalculateMediumID

/**
 * Companion object.
 */
object MediumIDCalculatorActor {

  /**
   * A message processed by ''MediumIDCalculatorActor'' telling it that it
   * should calculate a medium ID based on the passed in information.
   * @param mediumRoot the root path of the medium
   * @param mediumURI the medium URI
   * @param mediumContent a sequence with the media files on this medium
   */
  private[media] case class CalculateMediumID(mediumRoot: Path, mediumURI: String, mediumContent:
  Seq[MediaFile])

}

/**
 * A specialized actor implementation which wraps a [[MediumIDCalculator]].
 *
 * This actor class is instantiated with a ''MediumIDCalculator'' object to be
 * wrapped. It accepts messages for calculating the ID of a medium based on its
 * content. Such messages are handled by delegating to the associated
 * calculator. The resulting data object with the medium ID and the content
 * file URIs is then sent back to the calling actor.
 *
 * @param calculator the ''MediumIDCalculator'' to be used by this instance
 */
class MediumIDCalculatorActor(calculator: MediumIDCalculator) extends Actor {
  override def receive: Receive = {
    case CalculateMediumID(mediumRoot, mediumURI, mediumContent) =>
      sender ! calculator.calculateMediumID(mediumRoot, mediumURI, mediumContent)
  }
}
