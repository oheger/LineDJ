package de.oliver_heger.splaya.tsthlp
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A class for generating stream data of (almost) arbitrary length. This class
 * is used in some unit tests which have to deal with streams. Basically, a
 * virtual stream is constructed. From this stream fragments starting at an
 * arbitrary position and with an arbitrary length can be queried. In order to
 * generate streams of arbitrary length, a configurable pattern is repeated and
 * appended by a numeric index.
 */
class StreamDataGenerator private (val pattern: String, val digits: Int) {
  /** The length of a block of the generated test stream. */
  val blockLen = pattern.length + digits

  /** The current position in the test source stream. */
  private var streamPosition: Int = 0

  /**
   * Generates content of a test stream. This method is able to generate a
   * stream of (almost) arbitrary length consisting of constant blocks followed
   * by indices. A substring of this stream can be returned.
   * @param pos the start position of the substring
   * @param length the length of the fragment
   * @return the specified substring of the test stream
   */
  def generateStreamContent(pos: Int, length: Int): String = {
    val startIdx = pos / blockLen
    val count = length / blockLen + 2
    val buf = new StringBuilder(count * blockLen)
    for (i <- 0 until count) {
      buf.append(pattern)
      val idx = (i + startIdx).toString()
      buf.append("0" * (digits - idx.length)).append(idx)
    }
    val startPos = pos % blockLen
    buf.substring(startPos, startPos + length)
  }

  /**
   * Generates an input stream with the specified test content.
   * @param pos the start position of the stream
   * @param length the length of the stream
   * @return the input stream with this content
   */
  def generateStream(pos: Int, length: Int): InputStream =
    new ByteArrayInputStream(generateStreamContent(pos, length).getBytes())

  /**
   * Returns the next input stream from the test sequence. Each method
   * invocation obtains the next portion of the test stream.
   * @param length the length of the next stream
   * @return the input stream
   */
  def nextStream(length: Int): InputStream = {
    val startPos = streamPosition
    streamPosition += length
    generateStream(startPos, length)
  }
}

/**
 * The corresponding object. It provides some constants and factory methods.
 */
object StreamDataGenerator {
  /** The default pattern for test streams. */
  val DefaultPattern = "TestStreamContent_"

  /** The default number of digits. */
  val DefaultDigits = 5

  /**
   * Creates a new ''StreamDataGenerator'' instance with the default generator
   * pattern and the default number of digits.
   * @return the newly created instance
   */
  def apply(): StreamDataGenerator = apply(null, 0)

  /**
   * Creates a new ''StreamDataGenerator'' instance with the specified
   * parameters.
   * @param pattern the pattern to be used for generating the content of the
   * stream; can be '''null''', then a default pattern is used
   * @param digits the number of digits of the numeric indices appended to the
   * pattern string; smaller numbers are padded with zeros; if this parameter
   * is less than 1, a default value is used
   * @return the newly created instance
   */
  def apply(pattern: String, digits: Int) = {
    new StreamDataGenerator(
      if (pattern == null) DefaultPattern else pattern,
      if (digits <= 0) DefaultDigits else digits)
  }

  /**
   * Reads the content of an input stream into a byte array. This is useful for
   * instance for checking the content of a test file. The input stream is not
   * closed.
   * @param stream the stream to be read
   * @return an array with the content of the stream
   * @throws IOException if an IO error occurs
   */
  def readStream(stream: InputStream): Array[Byte] = {
    val out = new ByteArrayOutputStream
    var c = stream.read()
    while (c != -1) {
      out.write(c)
      c = stream.read()
    }
    out.toByteArray()
  }
}
