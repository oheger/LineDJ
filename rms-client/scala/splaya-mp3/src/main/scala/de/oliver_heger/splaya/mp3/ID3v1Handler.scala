package de.oliver_heger.splaya.mp3

/**
 * An object providing access to ID3v1 tags.
 *
 * The main functionality of this object is to create an
 * [[de.oliver_heger.splaya.mp3.ID3TagProvider]] object from a binary data
 * buffer. It is checked whether the buffer contains a valid ID3v1 frame. If
 * this is the case, the data is extracted and available through the
 * ''ID3TagProvider'' object. Otherwise, a dummy provider is returned which
 * contains only ''None'' values.
 */
object ID3v1Handler {
  /** Constant for the size of a binary buffer containing a valid ID3v1 frame. */
  val FrameSize = 128

  /** The encoding name for ISO-8859-1. */
  private final val Encoding = "ISO-8859-1"

  /** Constant for a space character. */
  private final val Space: Byte = ' '

  /** Start position of the title tag. */
  private val TitlePos = 3

  /** Length of the title tag. */
  private val TitleLen = 30

  /** Start position of the artist tag. */
  private val ArtistPos = 33

  /** Length of the artist tag. */
  private val ArtistLen = 30

  /** Start position of the album tag. */
  private val AlbumPos = 63

  /** Length of the artist tag. */
  private val AlbumLen = 30

  /** Start position of the year tag. */
  private val YearPos = 93

  /** Length of the year tag. */
  private val YearLen = 4

  /** Position of the track number tag. */
  private val TrackNoPos = 126

  /** Constant for an undefined tag provider. */
  private final val UndefinedProvider = ID3v1TagProvider(None, None, None,
    None, None)

  /**
   * Returns an ''ID3TagProvider'' object for the specified buffer. If the
   * buffer contains a valid ID3v1 frame, the tag information is extracted and
   * can be queried from the returned provider object. Otherwise, a provider
   * object is returned which does not contain any data.
   * @param buf the buffer with the ID3v1 data
   * @return an ''ID3TagProvider'' for extracting tag information
   */
  def providerFor(buf: Array[Byte]): ID3TagProvider = {
    if (buf.length == FrameSize) {
      buf match {
        case Array('T', 'A', 'G', _*) =>
          createProvider(buf)
        case _ => UndefinedProvider
      }
    } else UndefinedProvider
  }

  /**
   * Extracts ID3 tags if a valid frame was detected.
   * @param buf the buffer with ID3 data
   * @return an ''ID3TagProvider'' providing access to the tag values
   */
  private def createProvider(buf: Array[Byte]): ID3TagProvider =
    ID3v1TagProvider(title = extractString(buf, TitlePos, TitleLen),
      artist = extractString(buf, ArtistPos, ArtistLen),
      album = extractString(buf, AlbumPos, AlbumLen),
      inceptionYearString = extractString(buf, YearPos, YearLen),
      trackNoString = extractTrackNo(buf))

  /**
   * Extracts a string value from the given byte buffer at the given position.
   * The string is encoded in ISO-8859-1, it may be padded with 0 bytes or
   * space. If data is found, an option with the trimmed string is returned.
   * Otherwise, result is ''None''.
   * @param buf the buffer with the binary data
   * @param start the start index of the string to extract
   * @param length the maximum length of the string
   * @return an option with the extracted string
   */
  private[mp3] def extractString(buf: Array[Byte], start: Int, length: Int): Option[String] = {
    val endIdx = start + length
    var firstNonSpace = -1
    var lastNonSpace = -1
    var pos = start

    while (pos < endIdx && buf(pos) != 0) {
      if (buf(pos) != Space) {
        lastNonSpace = pos
        if (firstNonSpace < 0) {
          firstNonSpace = pos
        }
      }
      pos += 1
    }

    if (firstNonSpace < 0) None
    else Some(new String(buf, firstNonSpace, lastNonSpace - firstNonSpace + 1,
      Encoding))
  }

  /**
   * Extracts information about the track number. The track number is available
   * in ID3v1.1 only. If defined, it is located in the last byte of the
   * comment tag.
   * @param buf the buffer with the binary data
   * @return an Option for the track number as string
   */
  private def extractTrackNo(buf: Array[Byte]): Option[String] = {
    if (buf(TrackNoPos) != 0 && buf(TrackNoPos - 1) == 0) {
      val trackNo = extractByte(buf, TrackNoPos)
      Some(trackNo.toString)
    } else None
  }

  /**
   * A simple implementation of the ''ID3TagProvider'' interface based on a
   * case class.
   */
  private case class ID3v1TagProvider(title: Option[String],
    artist: Option[String], album: Option[String],
    inceptionYearString: Option[String], trackNoString: Option[String])
    extends ID3TagProvider
}
