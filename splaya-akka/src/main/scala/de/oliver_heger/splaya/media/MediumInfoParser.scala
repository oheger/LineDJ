package de.oliver_heger.splaya.media

import java.io.ByteArrayInputStream

import org.slf4j.LoggerFactory

/**
 * Companion object for ''MediumInfoParser''.
 */
private object MediumInfoParser {
  /** Constant for the XML element defining the name of a medium. */
  private val ElemName = "name"

  /** Constant for the XML element defining the medium description. */
  private val ElemDesc = "description"

  /** Constant for the XML element defining the playlist standard order. */
  private val ElemOrder = "order"

  /** Constant for the XML element defining the standard order mode. */
  private val ElemOrderMode = "mode"

  /** Constant for the XML element defining additional ordering parameters. */
  private val ElemOrderParams = "params"

  /**
   * Parses the content of a medium description file and returns a ''NodeSeq''
   * that can be evaluated.
   * @param data the array with the content of the medium description
   * @return the parsed XML node sequence
   */
  private def parseMediumDescription(data: Array[Byte]): xml.NodeSeq = {
    val stream = new ByteArrayInputStream(data)
    xml.XML.load(stream)
  }

  /**
   * Extracts information about a medium from the given XML document and
   * returns it as a ''MediumSettingsData'' object.
   * @param mediumURI the URI to the affected medium
   * @param elem the XML structure to be processed
   * @return the resulting ''MediumSettingsData'' object
   */
  private def extractMediumInfo(mediumURI: String, elem: xml.NodeSeq): MediumSettingsData = {
    val elemOrder = elem \ ElemOrder
    MediumSettingsData(name = (elem \ ElemName).text,
      description = (elem \ ElemDesc).text,
      orderMode = (elemOrder \ ElemOrderMode).text,
      orderParams = elemOrder \ ElemOrderParams \ "_",
      mediumURI = mediumURI)
  }
}

/**
 * An internally used helper class for parsing XML files with meta data about
 * media.
 *
 * An instance of this class can parse an XML document with media information
 * (using the typical format). The XML is passed in as a byte array because
 * this is the result produced by a file loader actor.
 */
private class MediumInfoParser {

  import MediumInfoParser._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Parses the given medium information provided in binary form and converts
   * it to a ''MediumInfo'' object if possible. In case of a failure, result is
   * ''None''.
   * @param data the data to be parsed
   * @param mediumURI the URI of the medium
   * @return an option with the resulting ''MediumInfo'' object
   */
  def parseMediumInfo(data: Array[Byte], mediumURI: String): Option[MediumSettingsData] = {
    try {
      Some(extractMediumInfo(mediumURI, parseMediumDescription(data)))
    } catch {
      case ex: Exception =>
        log.error("Error when parsing medium description for " + mediumURI, ex)
        None
    }
  }
}

/**
 * An internally used data class storing meta data about a medium.
 * @param name the name of the medium
 * @param description a description of the medium
 * @param mediumURI the medium URI
 * @param orderMode the default ordering mode for a playlist for this medium
 */
private case class MediumSettingsData(override val name: String,
                                      override val description: String,
                                      override val mediumURI: String,
                                      orderMode: String,
                                      orderParams: xml.NodeSeq) extends MediumInfo
