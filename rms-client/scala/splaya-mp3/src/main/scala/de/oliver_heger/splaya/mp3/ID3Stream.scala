package de.oliver_heger.splaya.mp3

import java.io.InputStream
import java.io.PushbackInputStream

/**
 * A specialized input stream implementation which can be used to skip or
 * evaluate ID3 tags in MP3 files.
 *
 * Obviously, the MP3 library used for playing audio files has some problems
 * with MP3 files containing certain images in their ID3 tags. Therefore, this
 * stream can be wrapped around a data stream. It checks whether the wrapped
 * stream contains ID3 tags. If this is the case, the whole ID3 tags can be
 * skipped. For clients reading data from this stream it looks as if the data
 * file would not contain any ID3 information.
 *
 * After creating an instance passing in the wrapped stream, the ''skipID3()''
 * method has to be called. Afterwards the stream can be used in the usual way.
 * If ID3 information was present, it is skipped now.
 *
 * It is also possible to evaluate ID3 information. If this is desired, rather
 * than calling ''skipID3()'', call ''nextID3Frame()'' for obtaining a data
 * object describing the next ID3 frame. From this object the single ID3 tags
 * can be obtained.
 *
 * @param in the input stream to be filtered
 */
class ID3Stream(in: InputStream) extends PushbackInputStream(in,
  ID3Stream.HeaderSize) {
  /** A flag whether the end of the underlying input stream was reached. */
  private var endOfStream = false

  /**
   * Skips all ID3 headers in the wrapped input at the current position. This
   * method should be called directly after creation of this object. It checks
   * whether the wrapped stream starts with one or more ID3 headers. If this is
   * the case, ID3 information is skipped, and result is the number of skipped
   * ID3 headers. Otherwise, this method has no effect, and result is 0.
   * @return the number of ID3 headers which have been skipped
   * @throws IOException if a read error occurs
   */
  def skipID3(): Int = {
    var count = 0
    while (skipNextID3()) {
      count += 1
    }
    count
  }

  /**
   * Skips the next ID3 header at the current position of the wrapped input
   * stream if present. The return value indicates whether an ID3 header was
   * found at the current position. If not, this method has no effect.
   * @return a flag whether an ID3 header was skipped
   * @throws IOException if a read error occurs
   */
  def skipNextID3(): Boolean = {
    nextHeader().exists { header =>
      in.skip(header.size)
      true
    }
  }

  /**
   * Reads the next ID3v2 frame from this stream. If a frame is found at the
   * current position, it is fully read and returned. (The stream is then
   * advanced to the position directly after this frame.) Otherwise, ''None''
   * is returned.
   * @return an ''Option'' for the next ''ID3Frame''
   * @throws IOException if an error occurs
   */
  def nextID3Frame(): Option[ID3Frame] = {
    nextHeader() map (createFrame(_))
  }

  /**
   * Calculates the size of the whole ID3 tag block (excluding the ID3 header).
   * This method returns the number of bytes which have to be skipped in order
   * to read over the ID3 data.
   * @param header the array with the ID3 header
   * @return the size of the ID3 block (minus header size)
   */
  protected[mp3] def id3Size(header: Array[Byte]): Int = {
    import ID3Stream._
    val f1 = header(IdxSize).toInt << F1
    val f2 = header(IdxSize + 1).toInt << F2
    val f3 = header(IdxSize + 2).toInt << F3
    f1 + f2 + f3 + header(IdxSize + 3).toInt
  }

  /**
   * Tries to read the next ID3v2 header from the underlying input stream. If
   * a header is found, it is returned. Otherwise, the current position of
   * the stream is restored, and result is ''None''.
   * @return an ''Option'' with the next ''ID3Header''
   */
  private def nextHeader(): Option[ID3Header] = {
    val header = new Array[Byte](ID3Stream.HeaderSize)
    val read = readBuffer(header)
    if (!endOfStream && ID3Stream.isID3Header(header)) {
      Some(ID3Header(size = id3Size(header),
        version = ID3Stream.extractByte(header, ID3Stream.IdxVersion)))
    } else {
      unread(header, 0, read)
      None
    }
  }

  /**
   * Skips the ID3 frame described by the given header. This method advances
   * the underlying stream by the size field of the header.
   * @param header the header of the frame
   */
  private def skipID3Frame(header: ID3Header) {
    in.skip(header.size)
  }

  /**
   * Extracts all tags for the given ID3 frame if its version is supported.
   * This method is called if a valid ID3 frame header was found. It now checks
   * whether the version is supported and - if yes - iterates over all tags.
   * For an unknown version, an ''IDFrame'' object with an empty map of tags
   * is returned, and this frame is skipped.
   * @param header the header of the ID3v2 frame
   * @return the newly created ''ID3Frame'' object
   */
  private def createFrame(header: ID3Header): ID3Frame = {
    val tagmap = ID3Stream.Versions.get(header.version) match {
      case Some(vdata) =>
        extractTags(header, vdata)
      case None =>
        skipID3Frame(header)
        Map.empty[String, ID3Tag]
    }

    ID3Frame(header, tagmap)
  }

  /**
   * Extracts all tags of the current ID3v2 frame using the given
   * ''VersionData'' object. The frame is fully read. If it contains padding
   * at the end, it is skipped.
   * @param header the header of the current frame
   * @param vdata the object describing the properties of this frame version
   * @return the newly created ''ID3Frame''
   */
  private def extractTags(header: ID3Header, vdata: ID3Stream.VersionData): Map[String, ID3Tag] = {
    var tagmap = Map.empty[String, ID3Tag]
    var bytesToRead = header.size
    var padding = false

    while (!endOfStream && bytesToRead >= vdata.headerLength && !padding) {
      val tagHeader = new Array[Byte](vdata.headerLength)
      bytesToRead -= readBuffer(tagHeader)
      padding = tagHeader(0) == 0
      if (!endOfStream && !padding) {
        val tagSize = math.min(bytesToRead, vdata.extractSize(tagHeader))
        val tagData = new Array[Byte](tagSize)
        readBuffer(tagData)
        val tag = ID3Tag(vdata extractTagName tagHeader, tagData)
        tagmap = tagmap + (tag.name -> tag)
        bytesToRead -= tagSize
      }
    }

    if (bytesToRead > 0 && !endOfStream) {
      in.skip(bytesToRead)
    }
    tagmap
  }

  /**
   * Reads the whole buffer from the underlying stream or skips the read
   * operation if the end of the stream is reached.
   * @param buf the buffer to be read
   * @return the number of bytes read
   */
  private def readBuffer(buf: Array[Byte]): Int = {
    var ofs = 0
    while (!endOfStream && ofs < buf.length) {
      var cnt = in.read(buf, ofs, buf.length - ofs)
      if (cnt < 0) {
        endOfStream = true
      } else {
        ofs += cnt
      }
    }
    ofs
  }
}

/**
 * The companion object of ''ID3Stream''.
 */
object ID3Stream {
  /** A blank string. */
  private[mp3] val Blank = ""

  /** The factor for byte 1 of the header size. */
  private val F1 = 21

  /** The factor for byte 2 of the header size. */
  private val F2 = 14

  /** The factor for byte 3 of the header size. */
  private val F3 = 7

  /** The index of the version number in the header. */
  private val IdxVersion = 3

  /** The index of the size information in the header. */
  private val IdxSize = 6

  /** The size of an ID3 header. */
  private val HeaderSize = 10

  /** The string identifying an ID3 header. */
  private val HeaderID = "ID3".getBytes

  /** Text encoding ISO-88559-1. */
  private val EncISO88591 = TextEncoding("ISO-8859-1", false)

  /** Text encoding UTF 16. */
  private val EncUTF16 = TextEncoding("UTF-16", true)

  /** Text encoding UTF-16 without BOM. */
  private val EncUTF16BE = TextEncoding("UTF-16BE", true)

  /** Text encoding UTF-8. */
  private val EncUTF8 = TextEncoding("UTF-8", false)

  /**
   * An array with all supported text encodings in an order which corresponds
   * to the text encoding byte used within ID3v2 tags.
   */
  private[mp3] val Encodings = Array(EncISO88591, EncUTF16, EncUTF16BE, EncUTF8)

  private val Versions = createVersionDataMap()

  /** The mask for extracting a byte value to an unsigned integer. */
  private val ByteMask = 0xFF

  /** Factor for shifting a byte position in an integer. */
  private val ByteShift = 8

  /**
   * Tests whether the specified array contains an ID3 header. This method
   * checks whether the given array starts with the ID3 header tag.
   * @param arr the array to be checked
   * @return '''true''' if this array contains an ID3 header, '''false'''
   * otherwise
   */
  def isID3Header(arr: Array[Byte]): Boolean = {
    if (arr.length >= HeaderID.length) {
      var idx = 0
      var ok = true
      while (ok && idx < HeaderID.length) {
        ok = arr(idx) == HeaderID(idx)
        idx += 1
      }
      ok
    } else {
      false
    }
  }

  /**
   * Extracts a string from the given byte array using the specified encoding.
   * @param buf the byte array
   * @param ofs the start offset of the string in the buffer
   * @param len the length of the string
   * @param enc the name of the encoding
   * @return the resulting string
   */
  private[mp3] def extractString(buf: Array[Byte], ofs: Int, len: Int,
    enc: String): String =
    new String(buf, ofs, len, enc)

  /**
   * Extracts an integer value with the given number of bytes from the given
   * byte array.
   * @param buf the byte array
   * @param ofs the start position of the integer number
   * @param len the length of the integer (i.e. the number of bytes)
   * @return the extracted integer value
   */
  private def extractInt(buf: Array[Byte], ofs: Int, len: Int): Int = {
    var intVal = extractByte(buf, ofs)
    for (i <- 1 until len) {
      intVal <<= ByteShift
      intVal |= extractByte(buf, ofs + i)
    }
    intVal
  }

  /**
   * Extracts a single byte from the given buffer and converts it to an
   * (unsigned) integer.
   * @param buf the byte buffer
   * @param idx the index in the buffer
   * @return the resulting unsigned integer
   */
  private[mp3] def extractByte(buf: Array[Byte], idx: Int): Int =
    buf(idx).toInt & 0xFF

  /**
   * Creates a map which associates versions of ID3v2 frames with
   * corresponding ''VersionData'' objects.
   * @return the mapping
   */
  private def createVersionDataMap(): Map[Int, VersionData] =
    Map(2 -> VersionData(nameLength = 3, sizeLength = 3, headerLength = 6),
      3 -> VersionData(nameLength = 4, sizeLength = 4, headerLength = 10),
      4 -> VersionData(nameLength = 4, sizeLength = 4, headerLength = 10))

  /**
   * A class with information about differences in the single ID3v2 versions.
   * Instances of this class are created for each supported version. They
   * contain the lengths of various internal fields. They also provide
   * functionality for extracting information from a tag header stored in a
   * byte array.
   *
   * @param nameLength the length of a tag name in this version
   * @param sizeLength the length of the size header field in this version
   * @param headerLength the total length of a tag header in bytes
   */
  private case class VersionData(nameLength: Int, sizeLength: Int,
    headerLength: Int) {
    /**
     * Extracts the tag name from the given header array.
     * @param header an array with the bytes of the tag header
     * @return the tag name as string
     */
    def extractTagName(header: Array[Byte]): String =
      extractString(header, 0, nameLength, EncISO88591.encName)

    /**
     * Extracts the size of the tag's content from the given header array. This
     * is the size without the header.
     * @param header an array with the bytes of the tag header
     * @return the size of the tag's content
     */
    def extractSize(header: Array[Byte]): Int =
      extractInt(header, nameLength, sizeLength)
  }
}

/**
 * A data class describing an ID3v2 header.
 *
 * @param version the version of this ID3 header (e.g. 3 for ID3v2, version 3)
 * @param size the size of the data stored in the associated tag (excluding
 * header size)
 */
case class ID3Header(version: Int, size: Int)

/**
 * A data class describing a single tag in an ID3v2 frame. These tags contain
 * the actual data. There are methods for querying data either in binary form or
 * as strings.
 *
 * @param name the name of this tag (this is the internal name as defined by
 * the ID3v2 specification)
 * @param data an array with the content of this tag
 */
case class ID3Tag(name: String, private val data: Array[Byte]) {
  /**
   * Returns the size of the content of this tag.
   */
  def size: Int = throw new UnsupportedOperationException("Not yet implemented!")

  /**
   * Returns the content of this tag as a byte array. The return value is a copy
   * of the internal data of this tag.
   * @return the content of this tag as a byte array
   */
  def asBytes: Array[Byte] =
    data.clone()

  /**
   * Returns the content of this tag as a string. If the tag contains an
   * encoding specification, it is taken into account.
   * @return the content of this tag as string
   */
  def asString: String = {
    data match {
      case Array() => ID3Stream.Blank
      case Array(0) => ID3Stream.Blank
      case _ =>
        var ofs = 0
        val encFlag = ID3Stream.extractByte(data, 0)
        val encoding = if (encFlag < ID3Stream.Encodings.length) {
          ofs = 1
          ID3Stream.Encodings(encFlag)
        } else ID3Stream.Encodings(0)
        val len = calcStringLength(encoding.doubleByte) - ofs
        ID3Stream.extractString(data, ofs, len, encoding.encName)
    }
  }

  /**
   * Determines the length of this tag's string content by skipping 0 bytes at
   * the end of the buffer.
   * @param doubleByte a flag whether a double byte encoding is used
   */
  private def calcStringLength(doubleByte: Boolean): Int = {
    var length = data.length
    if (doubleByte) {
      while (length >= 2 && data(length - 1) == 0 && data(length - 2) == 0) {
        length -= 2
      }
    } else {
      while (length >= 1 && data(length - 1) == 0) {
        length -= 1
      }
    }
    length
  }
}

/**
 * A data class describing a complete ID3v2 frame. The frame consists of a
 * header (which can also be used to find out the version), and a map containing
 * the tags with the actual data.
 *
 * @param header the header of this frame
 * @param tags an immutable map with all ID3v2 tags; they can be directly
 * accessed by name
 */
case class ID3Frame(header: ID3Header, tags: Map[String, ID3Tag])

/**
 * A simple data class representing a text encoding.
 *
 * @param encName the name of the text encoding
 * @param doubleByte a flag whether this encoding uses double bytes
 */
private case class TextEncoding(encName: String, doubleByte: Boolean)
