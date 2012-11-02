package de.oliver_heger.splaya.mp3

import java.io.IOException
import java.io.InputStream

import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.MediaDataExtractor

/**
 * A specialized ''MediaDataExtractor'' implementation which is able to extract
 * ID3 tags from MP3 audio files.
 *
 * This implementation uses the MP3 utility classes provided by this library
 * to extract all ID3 frames (of all supported versions) and the duration of
 * the passed in input stream. If in the passed in stream no ID3 frames nor
 * MP3 frames can be found, it is considered to be no MP3 file, and result
 * is '' None''. If an audio file contains multiple ID3 frames with the same
 * tags, frames with a higher version take precedence.
 */
class MP3MediaDataExtractor extends MediaDataExtractor {
  /**
   * @inheritdoc This implementation tries to find ID3 frames and extract
   * information from them. Also an iteration over all MP3 frames is done to
   * determine the duration of the audio file. If no or only few MP3 frames
   * are found, the input stream is considered to be no MP3 file. (It could be
   * well the case that an arbitrary binary file accidently contains valid
   * MP3 frames; therefore, we use a kind of threshold: if the duration
   * extracted from the found MP3 frames is less than a second, the file is
   * ignored.)
   */
  def extractData(stream: InputStream): Option[AudioSourceData] = {
    val tailStream = new TailStream(stream, ID3v1Handler.FrameSize)
    val id3Stream = new ID3Stream(tailStream)
    val mp3Stream = new MpegStream(id3Stream)

    val id3v2Frames = extractID3v2Frames(id3Stream)
    val duration = determineDuration(mp3Stream)
    if (duration < 1000) None
    else {
      val provider = createCombinedTagProvider(id3v2Frames,
        extractID3v1Data(tailStream))
      Some(createAudioSourceData(provider, duration))
    }
  }

  /**
   * Orders the given list of ''ID3Frame'' objects based on their version.
   * Lower versions come first.
   * @param frames the list with frames to be sorted
   * @return the sorted list
   */
  private[mp3] def sortFrames(frames: List[ID3Frame]): List[ID3Frame] = {
    frames sortWith (_.header.version < _.header.version)
  }

  /**
   * Extracts all ID3v2 frames from the given input stream.
   * @param id3Stream the ID3 input stream
   * @return a (unsorted) list of ID3v2 frames extracted from the stream
   */
  private def extractID3v2Frames(id3Stream: ID3Stream): List[ID3Frame] = {
    var frames = List[ID3Frame]()
    var frameOpt = id3Stream.nextID3Frame()
    while (frameOpt.isDefined) {
      frames = frameOpt.get :: frames
      frameOpt = id3Stream.nextID3Frame()
    }
    frames
  }

  /**
   * Extracts ID3v1 tag data from the given stream. This information is located
   * at the very end of the audio file. So after iteration over all MP3 frames
   * it should be available in the tail buffer.
   * @param tailStream the tail stream
   * @return a tag provider for the ID3v1 tag data
   */
  private def extractID3v1Data(tailStream: TailStream): ID3TagProvider =
    ID3v1Handler.providerFor(tailStream.tail)

  /**
   * Iterates over all MP3 frames in the given stream to determine the audio
   * file's duration.
   * @return the duration in milliseconds
   */
  private def determineDuration(mp3Stream: MpegStream): Long = {
    var duration = 0.0
    var frameOpt = mp3Stream.nextFrame()
    while (frameOpt.isDefined) {
      duration += frameOpt.get.duration
      mp3Stream.skipFrame()
      frameOpt = mp3Stream.nextFrame()
    }
    scala.math.round(duration)
  }

  /**
   * Creates a combined ID3 tag provider which contains all ID3 tag information
   * extracted from the audio file. The ID3 frames holding the data of the
   * combined provider are ordered by their version, so that frames with a
   * higher version take precedence over frames with a lower version.
   * @param v2Frames the list with ID3v2 frames (unordered)
   * @param v1Provider the provider for ID3v1 data
   * @return a combined ID3 tag provider
   */
  private def createCombinedTagProvider(v2Frames: List[ID3Frame],
    v1Provider: ID3TagProvider): ID3TagProvider = {
    val providers = (List(v1Provider) /: v2Frames) {
      (pl, frame) => ID3Stream.providerFor(frame) :: pl
    }
    new CombinedID3TagProvider(providers)
  }

  /**
   * Creates an ''AudioSourceData'' object with all extracted ID3 tag data.
   * @param provider the combined ID3 tag provider
   * @param duration the audio file's duration
   * @return the ''AudioSourceData'' object
   */
  private def createAudioSourceData(provider: ID3TagProvider, duration: Long): AudioSourceData = {
    new AudioSourceDataImpl(title = defaultString(provider.title),
      albumName = defaultString(provider.album),
      artistName = defaultString(provider.artist),
      inceptionYear = defaultInt(provider.inceptionYear),
      trackNo = defaultInt(provider.trackNo),
      duration = duration)
  }

  /**
   * Returns a default string for the given option. If the option is defined,
   * its value is used. Otherwise, '''null''' is returned.
   * @param opt the option
   * @return the default value for this option
   */
  private def defaultString(opt: Option[String]): String =
    if (opt.isDefined) opt.get else null

  /**
   * Returns a default numeric value for the given option. If the option is
   * defined, its value is used. Otherwise, 0 is returned.
   * @param opt the option
   * @return the default value for this option
   */
  private def defaultInt(opt: Option[Int]): Int =
    if (opt.isDefined) opt.get else 0

  /**
   * An implementation of ''AudioSourceData'' based on the passed in arguments.
   */
  private case class AudioSourceDataImpl(title: String, albumName: String,
    artistName: String, duration: Long, inceptionYear: Int,
    trackNo: Int) extends AudioSourceData
}
