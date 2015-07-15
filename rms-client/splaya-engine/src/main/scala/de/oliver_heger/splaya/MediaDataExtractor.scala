package de.oliver_heger.splaya
import java.io.InputStream

/**
 * A trait defining an interface for components that can extract meta data from
 * media files.
 *
 * An audio player application typically needs access to meta data of audio
 * files, e.g. title, artist, album name, etc. Such meta data can be obtained
 * through this interface. There will be different implementations for the
 * different audio file formats supported by the audio player application.
 *
 * This interface is pretty lean. It defines a method for extracting media data
 * from a stream. If a concrete implementation cannot handle the stream, it
 * should return ''None''. It is also allowed to throw an exception.
 */
trait MediaDataExtractor {
  /**
   * Extracts meta data from the given stream if possible. A concrete
   * implementation has to inspect the passed in stream whether it can handle
   * it. If so, all possible meta data should be extracted and returned as
   * an [[de.oliver_heger.splaya.AudioSourceData]] object. If the stream is
   * not supported, result is ''None''. Exceptions can be thrown, they have to
   * be handled by the caller. The stream must not be closed, this is also
   * done by the caller.
   * @param stream the stream to be processed
   * @return an ''Option'' object with extracted meta data
   */
  def extractData(stream: InputStream): Option[AudioSourceData]
}
