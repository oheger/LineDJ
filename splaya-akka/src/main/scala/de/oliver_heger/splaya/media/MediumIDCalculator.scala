package de.oliver_heger.splaya.media

import java.nio.file.Path
import java.util.zip.CRC32

/**
 * An internally used helper class for calculating the ID of a medium.
 *
 * The ID of a medium is calculated based on its content, i.e. the audio files
 * found in its directory structure. This class performs this calculation.
 * Internally, it uses a CRC32 checksum to determine a medium ID (given the
 * limited number of media, this should be sufficient). The results of this
 * class should be compatible with older versions of the MP3 player engine.
 */
private class MediumIDCalculator {
  /**
   * Calculates an alphanumeric medium ID for the specified input parameters.
   * @param mediumRoot the root directory of the medium
   * @param mediumContent the files obtained from this medium; note: the files
   *                      are expected to be relative to the medium root path
   * @return the calculated medium ID
   */
  def calculateMediumID(mediumRoot: Path, mediumContent: Seq[Path]): String = {
    val crc = new CRC32
    mediumContent map mediumRoot.relativize map (_.toString.replace('\\', '/')) sortWith(_ < _) foreach { s => crc.update(s.getBytes)}
    java.lang.Long.toHexString(crc.getValue)
  }
}
