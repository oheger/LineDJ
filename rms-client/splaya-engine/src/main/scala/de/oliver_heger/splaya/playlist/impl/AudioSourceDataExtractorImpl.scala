package de.oliver_heger.splaya.playlist.impl
import org.slf4j.LoggerFactory

import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.fs.StreamSource
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.playlist.AudioSourceDataExtractor
import de.oliver_heger.splaya.AudioSourceData
import javazoom.jl.decoder.Bitstream
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader

/**
 * A default implementation of the ''AudioSourceDataExtractor'' trait.
 *
 * This implementation is specialized on MP3 audio files. It uses some default
 * functionality provided by the Java sound API plus JavaZoom specific methods
 * for extracting meta data. The URIs passed to the extractor are evaluated
 * using the passed in ''SourceResolver''.
 *
 * @param fsService the ''FSService'' instance
 */
class AudioSourceDataExtractorImpl(fsService: ServiceWrapper[FSService])
  extends AudioSourceDataExtractor {
  /** The logger. */
  val log = LoggerFactory.getLogger(classOf[AudioSourceDataExtractorImpl])

  /**
   * inheritdoc This implementation first calls ''getProperties()'' to extract
   * meta data as a map. Then it delegates to
   * ''createSourceDataFromProperties()'' in order to create the required
   * output object.
   */
  override def extractAudioSourceData(mediumURI: String, uri: String):
    Option[AudioSourceData] = {
    log.info("Extract audio data for {}.", uri)

    try {
      val props = getProperties(mediumURI, uri)
      Some(createSourceDataFromProperties(props, uri))
    } catch {
      case ex: Exception =>
        log.warn("Could not extract audio data for file " + uri, ex)
        None
    }
  }

  /**
   * Resolves the specified URI and extracts audio meta data as a map.
   * @param uri the URI of the audio file
   * @return a map with the meta data properties
   */
  protected def getProperties(mediumURI: String, uri: String):
    java.util.Map[String, Object] = {
    val data = new java.util.HashMap[String, Object]
    fsService foreach { svc =>
      val source = svc.resolve(mediumURI, uri)
      val stream = source.openStream()
      try {
        val reader = new MpegAudioFileReader
        val format = reader.getAudioFileFormat(stream)
        data.putAll(format.properties())
        AudioSourceDataExtractorImpl.determineDuration(data, source);
      } finally {
        stream.close()
      }
    }
    data
  }

  /**
   * Converts the given map of properties to an ''AudioSourceData'' object.
   * This method is called with the result returned by ''getProperties()''.
   * @param props the map with properties
   * @param uri the URI of the file to be resolved
   * @return the corresponding ''AudioSourceData'' object
   */
  protected[impl] def createSourceDataFromProperties(
    props: java.util.Map[String, Object], uri: String): AudioSourceData = {
    import AudioSourceDataExtractorImpl._
    val title = if (props.containsKey(KeyTitle)) strProp(props, KeyTitle) else uri
    val duration =
      if (props.containsKey(KeyDuration)) {
        props.get(KeyDuration).asInstanceOf[Long]
      } else 0
    new AudioSourceDataImpl(title = title,
      albumName = strProp(props, KeyAlbum),
      artistName = strProp(props, KeyInterpret),
      inceptionYear = parseNumericProperty(props.get(KeyYear)).toInt,
      trackNo = parseNumericProperty(props.get(KeyTrack)).toInt,
      duration = duration)
  }
}

/**
 * The companion object for ''AudioSourceDataExtractorImpl''.
 */
object AudioSourceDataExtractorImpl {
  /** Constant for the title key. */
  val KeyTitle = "title";

  /** Constant for the interpret key. */
  val KeyInterpret = "author";

  /** Constant for the duration key. The value of this property is a Long. */
  val KeyDuration = "duration";

  /** Constant for the album key. Here the name of the album is stored. */
  val KeyAlbum = "album";

  /**
   * Constant for the track key identifying the track number. Note that the
   * value is a string because it may contain arbitrary information like
   * 01/10.
   */
  val KeyTrack = "mp3.id3tag.track";

  /** Constant for the inception year key. */
  val KeyYear = "date";

  /** Constant for the duration property if it is stored in an ID3 tag. */
  val KeyID3Duration = "mp3.id3tag.length";

  /**
   * Parses a numeric property in string form. If the property is undefined
   * (i.e. '''null''') or does not start with a valid numeric value,
   * 0 is returned. Otherwise, the leading part of the value that
   * consists only of digits is parsed into a numeric value.
   *
   * @param val the value of the property as a string
   * @return the corresponding numeric value
   */
  private def parseNumericProperty(value: Any): Long = {
    if (value == null) {
      0
    } else {
      val svalue = value.toString()
      val idx = svalue.indexWhere(!Character.isDigit(_))
      if (idx == 0) {
        0
      } else {
        val part = if (idx < 0) svalue else svalue.substring(0, idx)
        part.toLong
      }
    }
  }

  /**
   * Helper method for extracting a string property from the given map.
   * @param map the map with properties
   * @param key the key of the property in question
   * @return the string value of this property
   */
  private def strProp(map: java.util.Map[String, Object], key: String): String =
    map.get(key).asInstanceOf[String]

  /**
   * Determines the duration of the media file. This implementation first
   * checks whether the duration can be obtained from the properties. If not,
   * ''fetchDuration(FileContent)'' will be called for determining the
   * duration from the media file. The result is stored in the map.
   *
   * @param props the properties
   * @param content the content of the media file in question
   * @throws IOException if an error occurs
   */
  private def determineDuration(props: java.util.Map[String, Object],
    source: StreamSource) {
    val durationTag = props.get(KeyID3Duration)
    var duration: java.lang.Long =
      if (durationTag != null) {
        // transform duration into long
        durationTag.toString.toLong
      } else {
        fetchDuration(source)
      }

    props.put(KeyDuration, duration);
  }

  /**
   * Determines the playback duration of the specified media file. This method
   * is called if the duration cannot be obtained from the properties.
   *
   * @param content the content of the media file
   * @return the duration in milliseconds
   */
  private def fetchDuration(source: StreamSource): Long = {
    val bis = new Bitstream(source.openStream())
    var length = 0L

    try {
      var header = bis.readFrame()
      while (header != null) {
        length += header.ms_per_frame().toLong;
        bis.closeFrame();
        header = bis.readFrame()
      }
    } finally {
      bis.close();
    }

    length;
  }
}
