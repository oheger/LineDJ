package de.oliver_heger.splaya.media

import akka.actor.Actor

/**
 * Companion object.
 */
object MediumInfoParserActor {
  /** The name of an unknown medium. */
  private val UnknownMedium = "unknown"

  /**
   * Template for a default settings object to be returned in case of a
   * parsing error.
   */
  private[media] lazy val DummyMediumSettingsData = MediumSettingsData(name = UnknownMedium,
    description = "", mediumURI = "", orderMode = "", orderParams = xml.NodeSeq.Empty)

  /**
   * A message processed by ''MediumInfoParserActor'' which tells it to parse a
   * medium description. The actor will delegate the received data to its
   * associated parser and send a [[MediumInfo]] object as answer.
   * @param descriptionData the data of the description in binary form
   * @param mediumURI the medium URI
   */
  case class ParseMediumInfo(descriptionData: Array[Byte], mediumURI: String)

  /**
   * Returns a ''MediumInfo'' object for an undefined medium. This object can
   * be used if no information about a medium is available (e.g. if no or an
   * invalid description file is encountered).
   * @return a ''MediumInfo'' object for an undefined medium
   */
  def undefinedMediumInfo: MediumInfo = DummyMediumSettingsData
}

/**
 * A specialized actor implementation which wraps a [[MediumInfoParser]].
 *
 * This actor class is used to parse medium description files in parallel. At
 * construction time a ''MediumInfoParser'' is passed. This actor accepts a
 * message for parsing a medium description file. The file content is delegated
 * to the associated parser. The result is sent back as a
 * [[MediumSettingsData]] object.
 *
 * @param parser the parser for medium description data
 */
class MediumInfoParserActor(parser: MediumInfoParser) extends Actor {

  import MediumInfoParserActor._

  override def receive: Receive = {
    case ParseMediumInfo(desc, mediumURI) =>
      sender ! parser.parseMediumInfo(desc, mediumURI).getOrElse(DummyMediumSettingsData.copy
        (mediumURI = mediumURI))
  }
}
