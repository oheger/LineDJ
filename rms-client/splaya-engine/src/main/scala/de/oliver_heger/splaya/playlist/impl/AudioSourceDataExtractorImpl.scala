package de.oliver_heger.splaya.playlist.impl
import java.io.IOException

import scala.collection.mutable.Set

import org.slf4j.LoggerFactory

import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.fs.StreamSource
import de.oliver_heger.splaya.osgiutil.ServiceWrapper.convertToOption
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.MediaDataExtractor

/**
 * A default implementation of the ''AudioSourceDataExtractor'' trait.
 *
 * This implementation manages a set of of
 * [[de.oliver_heger.splaya.MediaDataExtractor]] objects. Requests to extract
 * meta data are delegated to these extractors. The first defined meta data
 * produced by an extractor is returned. The URIs of the files to be
 * processed are evaluated using the passed in
 * [[de.oliver_heger.splaya.fs.FSService]].
 *
 * Implementation note: This class is not thread-safe. It is intended to be
 * used by an actor so that access is shielded.
 *
 * @param fsService the ''FSService'' instance
 */
class AudioSourceDataExtractorImpl(fsService: ServiceWrapper[FSService])
  extends AudioSourceDataExtractor {
  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[AudioSourceDataExtractorImpl])

  /** Stores the media data extractors available in the system. */
  private var mediaDataExtractors: Set[MediaDataExtractor] = Set.empty

  /**
   * inheritdoc This implementation delegates to the available
   * ''MediaDataExtractor'' objects until one is found which is able to
   * produce data.
   */
  override def extractAudioSourceData(mediumURI: String, uri: String): Option[AudioSourceData] = {
    log.info("Extract audio data for {}.", uri)
    if (mediaDataExtractors.isEmpty) {
      log.warn("No media data extractors available!")
      None
    } else fsService flatMap { fs => extractMetaData(fs.resolve(mediumURI, uri)) }
  }

  /**
   * inheritdoc This implementation adds the given extractor to an internal
   * set.
   */
  override def addMediaDataExtractor(extr: MediaDataExtractor) {
    mediaDataExtractors += extr
  }

  /**
   * inheritdoc This implementation removes the specified extractor from the
   * internal set.
   */
  override def removeMediaDataExtractor(extr: MediaDataExtractor) {
    mediaDataExtractors -= extr
  }

  /**
   * Queries all media data extractors to extract data from the given stream.
   * @param src the stream source
   * @return an ''Option'' with extracted meta data
   */
  private def extractMetaData(src: StreamSource): Option[AudioSourceData] = {
    (None.asInstanceOf[Option[AudioSourceData]] /: mediaDataExtractors) { (optData, extr) =>
      if (optData.isDefined) optData
      else invokeMetaDataExtractor(src, extr)
    }
  }

  /**
   * Queries the given meta data extractor for the specified stream source.
   * @param src the stream source
   * @param extr the ''MediaDataExtractor''
   * @return an ''Option'' with extracted meta data
   */
  private def invokeMetaDataExtractor(src: StreamSource,
    extr: MediaDataExtractor): Option[AudioSourceData] = {
    val stream = src.openStream()
    try {
      extr.extractData(stream)
    } catch {
      case ex: Exception =>
        log.error("MediaDataExtractor throw an exception!", ex)
        None
    } finally {
      try {
        stream.close()
      } catch {
        case ex: IOException =>
          log.warn("Error when closing input stream!", ex)
      }
    }
  }
}
