package de.oliver_heger.splaya.media

import java.nio.file.Path

import akka.actor.Actor
import de.oliver_heger.splaya.media.MediumIDCalculatorActor.{CalculateMediumID,
MediumIDCalculationResult}

/**
 * Companion object.
 */
object MediumIDCalculatorActor {

  /**
   * A message processed by ''MediumIDCalculatorActor'' telling it that it
   * should calculate a medium ID based on the passed in information.
   * @param mediumURI the medium URI
   * @param mediumRoot the root path of the medium
   * @param mediumContent a sequence with the media files on this medium
   */
  case class CalculateMediumID(mediumURI: String, mediumRoot: Path, mediumContent: Seq[Path])

  /**
   * A message sent by ''MediumIDCalculatorActor'' with the result of a medium
   * ID calculation.
   * @param mediumURI the medium URI
   * @param mediumID the calculated alphanumeric medium ID
   */
  case class MediumIDCalculationResult(mediumURI: String, mediumID: String)

}

/**
 * A specialized actor implementation which wraps a [[MediumIDCalculator]].
 *
 * This actor class is instantiated with a ''MediumIDCalculator'' object to be
 * wrapped. It accepts messages for calculating the ID of a medium based on its
 * content. Such messages are handled by delegating to the associated
 * calculator. The resulting medium ID is then sent back to the calling actor.
 *
 * @param calculator the ''MediumIDCalculator'' to be used by this instance
 */
class MediumIDCalculatorActor(calculator: MediumIDCalculator) extends Actor {
  override def receive: Receive = {
    case CalculateMediumID(mediumURI, mediumRoot, mediumContent) =>
      val mediumID = calculator.calculateMediumID(mediumRoot, mediumContent)
      sender ! MediumIDCalculationResult(mediumURI = mediumURI, mediumID = mediumID)
  }
}
