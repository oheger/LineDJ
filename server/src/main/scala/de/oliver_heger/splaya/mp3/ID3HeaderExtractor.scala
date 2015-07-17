package de.oliver_heger.splaya.mp3

import java.nio.charset.StandardCharsets

/**
 * Companion object for ''ID3DataExtractor''.
 */
object ID3HeaderExtractor {
  /** Constant for the size of an ID3 header. */
  val ID3HeaderSize = 10

  /** The string identifying an ID3 header. */
  private val HeaderID = "ID3".getBytes(StandardCharsets.UTF_8)

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

  /**
   * Tests whether the specified array contains an ID3 header. This method
   * checks whether the given array starts with the ID3 header tag.
   * @param arr the array to be checked
   * @return '''true''' if this array contains an ID3 header, '''false'''
   *         otherwise
   */
  private def isID3Header(arr: Array[Byte]): Boolean =
    arr.length >= ID3HeaderSize && arr.startsWith(HeaderID)

  /**
   * Creates an ''ID3Header'' object from a binary representation of an ID3
   * header. The passed in array is expected to contain a valid header.
   * @param arr the array with the header in its binary form
   * @return the extracted ''ID3Header'' object
   */
  private def createID3Header(arr: Array[Byte]): ID3Header =
    ID3Header(size = id3Size(arr), version = extractByte(arr, IdxVersion))

  /**
   * Calculates the size of the whole ID3 tag block (excluding the ID3 header).
   * This method returns the number of bytes which have to be skipped in order
   * to read over the ID3 data.
   * @param header the array with the ID3 header
   * @return the size of the ID3 block (minus header size)
   */
  private def id3Size(header: Array[Byte]): Int = {
    val f1 = header(IdxSize).toInt << F1
    val f2 = header(IdxSize + 1).toInt << F2
    val f3 = header(IdxSize + 2).toInt << F3
    f1 + f2 + f3 + header(IdxSize + 3).toInt
  }
}

/**
 * A class for evaluating and extracting ID3-related information from audio
 * files.
 *
 * This class implements central functionality related to the processing of ID3
 * tags. It is the central entry point for various tasks that deal with meta
 * data stored in audio files.
 *
 * The class mainly assumes that data from audio files is available as binary
 * arrays. It can check whether such arrays represent valid elements of ID3
 * frames and create corresponding representations. These representations can
 * be used by other components of the audio player engine, e.g. to provide the
 * user additional information about the audio files available.
 *
 * Implementation note: Instances of this class can safely be shared between
 * multiple threads; they are state-less.
 */
class ID3HeaderExtractor {

  import ID3HeaderExtractor._

  /**
   * Tries to extract an [[ID3Header]] object from the given data array. If
   * this succeeds, the returned option is defined and contains meta
   * information about the extracted header. Otherwise, result is ''None''.
   * @param data the array with ID3 header data
   * @return an option with the extracted header
   */
  def extractID3Header(data: Array[Byte]): Option[ID3Header] =
    if (isID3Header(data)) Some(createID3Header(data))
    else None
}

/**
 * A class representing the header of an ID3v2 tag as used within MP3 audio
 * files.
 *
 * ID3 tags can contain meta information about audio files. Their binary
 * representation starts with a header block containing some description about
 * the tag. This class is a simple representation of such a header. It is not
 * very valuable on itself, but is required for enhanced processing of ID3
 * information, which is a frequent task when dealing with MP3 files.
 *
 * @param version the version of this ID3 header (e.g. 3 for ID3v2, version 3)
 * @param size the size of the data stored in the associated tag (excluding
 *             header size)
 */
case class ID3Header(version: Int, size: Int)
