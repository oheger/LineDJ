package de.oliver_heger.splaya.playlist

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

import org.slf4j.LoggerFactory

/**
 * The package object for the ''impl'' package.
 */
package object impl {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** Constant for the encoding of XML files. */
  val XMLEncoding = "iso-8859-1"

  /**
   * Loads an XML file from the given input stream with the default encoding.
   * Occurring exceptions are caught. If the XML document exists and can be
   * loaded successfully, it is returned as an option. Otherwise, result is
   * ''None''. The passed in stream is closed.
   * @param stream a function returning an input stream
   * @return an ''Option'' object with the loaded XML content
   */
  def loadXML(stream: () => InputStream): Option[xml.Elem] = {
    var in: Reader = null
    try {
      in = new BufferedReader(new InputStreamReader(stream(), XMLEncoding))
      Some(xml.XML.load(in))
    } catch {
      case ex: Exception =>
        log.error("Could not load file", ex)
        None
    } finally {
      close(in)
    }
  }

  /**
   * Closes the specified stream object ignoring any exceptions.
   */
  def close(r: Closeable) {
    if (r != null) {
      try {
        r.close()
      } catch {
        case ioex: IOException =>
          log.warn("Error when closing stream.", ioex)
      }
    }
  }
}
