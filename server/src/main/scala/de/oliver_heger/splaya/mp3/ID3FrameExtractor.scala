package de.oliver_heger.splaya.mp3

import scala.collection.mutable.ArrayBuffer

object ID3FrameExtractor {
  /** An array with tag names for ID3v2.2 frames. */
  private val TagsV2 = Array("TT2", "TP1", "TAL", "TYE", "TRK")

  /** An array with tag names for ID3v2.3 and 4 frames. */
  private val TagsV3 = Array("TIT2", "TPE1", "TALB", "TYER", "TRCK")

  /** A map with version-specific data. */
  private val Versions = createVersionDataMap()

  /** Factor for shifting a byte position in an integer. */
  private val ByteShift = 8

  /**
   * A default ''FrameDataHandler'' implementation which is used initially and
   * if no data left from the processing of a previous chunk. This object has
   * no internal state and thus can be shared and reused.
   */
  private val DirectFrameDataHandler = new FrameDataHandler {
    /**
     * @inheritdoc This implementation just returns the passed in data.
     */
    override def frameData(nextChunk: Array[Byte]): Array[Byte] = nextChunk

    /**
     * @inheritdoc This implementation always returns '''true'''.
     */
    override def canProcess(nextChunk: Array[Byte]): Boolean = true
  }

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
   * Extracts all tags from the given ID3v2 frame (in binary form) using the given
   * ''VersionData'' object.
   * @param data the content of the frame as binary array
   * @param tagSizeLimit the maximum allowed size of a tag
   * @param complete a flag whether this is the last chunk
   * @param versionData the object describing the properties of this frame version
   * @param tagList the list where to append new tag mappings
   * @return the updated list with tag mappings and a ''FrameDataHandler'' for
   *         processing the next chunk
   */
  private def processFrame(data: Array[Byte], tagSizeLimit: Int, complete: Boolean, versionData:
  VersionData, tagList: List[(String, ID3Tag)]): (List[(String, ID3Tag)], FrameDataHandler) = {
    var tags = tagList
    var nextHandler: FrameDataHandler = DirectFrameDataHandler
    var index = 0
    var padding = false

    def bytesToRead: Int = data.length - index

    while (bytesToRead >= versionData.headerLength && !padding) {
      val headerPos = index
      index += versionData.headerLength
      padding = data(headerPos) == 0
      if (!padding) {
        val tagSize = versionData.extractSize(data, headerPos)
        if (tagSize > tagSizeLimit) {
          index += tagSize
          if (index > data.length) {
            nextHandler = new SkipFrameDataHandler(data.length - index)
          }
        } else {

          if (complete || tagSize <= bytesToRead) {
            val actSize = math.min(bytesToRead, tagSize)
            val tagData = data.slice(index, index + actSize)
            val tag = ID3Tag(versionData.extractTagName(data, headerPos), tagData)
            tags = (tag.name -> tag) :: tags
          } else {
            nextHandler = new IncompleteFrameDataHandler(data drop headerPos, tagSize +
              versionData.headerLength)

          }
          index += tagSize
        }
      }
    }

    if (bytesToRead > 0) {
      nextHandler = new IncompleteFrameDataHandler(data drop index, versionData.headerLength)
    }
    (tags, nextHandler)
  }

  /**
   * Creates a map which associates versions of ID3v2 frames with
   * corresponding ''VersionData'' objects.
   * @return the mapping
   */
  private def createVersionDataMap(): Map[Int, VersionData] =
    Map(2 -> VersionData(nameLength = 3, sizeLength = 3, headerLength = 6,
      tagNames = TagsV2),
      3 -> VersionData(nameLength = 4, sizeLength = 4, headerLength = 10,
        tagNames = TagsV3),
      4 -> VersionData(nameLength = 4, sizeLength = 4, headerLength = 10,
        tagNames = TagsV3))

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
   * @param tagNames an array with the names of the tags exposed through the
   *                 ''ID3TagProvider'' trait
   */
  private case class VersionData(nameLength: Int, sizeLength: Int,
                                 headerLength: Int, tagNames: Array[String]) {
    /**
     * Extracts the tag name from the given header array.
     * @param header an array with the bytes of the tag header
     * @param ofs the offset into the array
     * @return the tag name as string
     */
    def extractTagName(header: Array[Byte], ofs: Int): String =
      ID3Tag.extractName(header, ofs, nameLength)

    /**
     * Extracts the size of the tag's content from the given header array. This
     * is the size without the header.
     * @param header an array with the bytes of the tag header
     * @param ofs the offset into the array
     * @return the size of the tag's content
     */
    def extractSize(header: Array[Byte], ofs: Int): Int =
      extractInt(header, ofs + nameLength, sizeLength)

    /**
     * Creates an ''ID3TagProvider'' for the specified frame.
     * @param frame the frame
     * @return an ''ID3TagProvider'' for reading data from this frame
     */
    def createProvider(frame: ID3Frame): ID3TagProvider =
      new ID3v2TagProvider(frame, tagNames)
  }

  /**
   * An internally used trait that manages the data available for the currently
   * processed frame. This is necessary when frames are processed in chunks.
   * Then it can be the case that a tag cannot be processed directly because
   * part of its data is the next chunk.
   */
  private trait FrameDataHandler {
    /**
     * Checks whether processing is now possible with the data of the next
     * chunk.
     * @param nextChunk the content of the next chunk
     * @return a flag whether now sufficient data is available for further
     *         processing
     */
    def canProcess(nextChunk: Array[Byte]): Boolean

    /**
     * Returns the data to be processed for the next chunk. In the most simple
     * case, this is the passed in array. If there was remaining data from a
     * previous chunk, a different array may be returned.
     * @param nextChunk the content of the next chunk
     * @return the data to be used for the upcoming processing step
     */
    def frameData(nextChunk: Array[Byte]): Array[Byte]
  }

  /**
   * A ''FrameDataHandler'' that is used when a tag cannot be processed because
   * it has additional content in another chunk. This class stores the content
   * of the tag available so far and adds data as it becomes available.
   *
   * @param truncatedContent the truncated content of the tag
   * @param tagLength the actual length of the tag
   */
  private class IncompleteFrameDataHandler(truncatedContent: Array[Byte], tagLength: Int) extends
  FrameDataHandler {
    /** A buffer for constructing the full tag content. */
    private val buffer = ArrayBuffer.empty[Byte]
    buffer ++= truncatedContent

    /**
     * @inheritdoc This implementation checks whether now the complete content
     *             of the current tag is available
     */
    override def canProcess(nextChunk: Array[Byte]): Boolean = {
      buffer ++= nextChunk
      buffer.size >= tagLength
    }

    /**
     * @inheritdoc This implementation returns the content collected so far.
     */
    override def frameData(nextChunk: Array[Byte]): Array[Byte] = buffer.toArray
  }

  /**
   * A ''FrameDataHandler'' that is used to skip a tag whose size is larger
   * than the configured limit and that spans multiple chunks. It ignores a
   * number of bytes.
   * @param skipBytes the negative number of bytes that have to be skipped;
   *                  skipping is done when this counter reaches 0
   */
  private class SkipFrameDataHandler(skipBytes: Int) extends FrameDataHandler {
    /** The current skip counter. */
    private var count = skipBytes

    /**
     * @inheritdoc This implementation adds the size of the given array to the
     *             internal counter. If the counter is now greater than 0,
     *             processing can continue.
     */
    override def canProcess(nextChunk: Array[Byte]): Boolean = {
      count += nextChunk.length
      count > 0
    }

    /**
     * @inheritdoc This implementation returns the part of the given array
     *             which can be processed. The current counter contains the
     *             number of valid bytes in this chunk. It may be 0 or negative
     *             for the last chunk.
     */
    override def frameData(nextChunk: Array[Byte]): Array[Byte] =
      if (count <= 0) Array.empty
      else nextChunk.drop(nextChunk.length - count)
  }

}

/**
 * A class for extracting ID3v2 frames from audio files.
 *
 * This class implements functionality for parsing ID3 data provided as blocks
 * of binary arrays. From this data the values of ID3 tags are extracted and
 * made available in form of ''ID3Frame'' objects. From such objects, meta data
 * about audio files can be queried quite easily.
 *
 * Because ID3v2 frames may become large (for instance, it is possible to embed
 * image files) a chunk-wise processing is supported. An instance of
 * ''ID3FrameExtractor'' can be used to read data from a single ID3 frame.
 * The audio file can be read in multiple chunks, each chunk is then passed to
 * the ''addData()'' method. When all chunks have been processed the resulting
 * ''ID3Frame'' can be obtained from the ''createFrame()'' method; it is created
 * dynamically based on the information collected by this object.
 *
 * To avoid unconstrained memory consumption, an instance can be configured to
 * filter out tags whose size exceeds a specific limit. These tags are just
 * ignored when data is processed.
 *
 * This class is not thread-safe. It can be used only by a single thread to
 * process a single ID3 frame.
 *
 * @param header the header of the frame to be processed
 * @param tagSizeLimit an optional limit for the size of a tag
 */
class ID3FrameExtractor(val header: ID3Header, val tagSizeLimit: Int = Integer.MAX_VALUE) {

  import ID3FrameExtractor._

  /** The version data for the currently processed frame. */
  private val versionData = Versions.get(header.version)

  /** The list with tag data gathered so far. */
  private var tagList: List[(String, ID3Tag)] = Nil

  /** The current frame data handler. */
  private var dataHandler: FrameDataHandler = DirectFrameDataHandler

  /**
   * Adds a chunk of data to this object. It is processed as far as possible,
   * and tags and their values are extracted. Note that the passed in chunk of
   * data does not need to be aligned with the end of a tag. Then the affected
   * tag will be processed when the next chunk of data arrives. With the
   * boolean parameter it can be specified whether this is the last chunk. In
   * this case, all tags are processed, even if they are truncated.
   * @param data the data of the frame to be processed
   * @param complete a flag whether this is the last chunk
   * @return a reference to this extractor for method chaining
   */
  def addData(data: Array[Byte], complete: Boolean = true): ID3FrameExtractor = {
    if (dataHandler.canProcess(data) || complete) {
      versionData foreach { v =>
        val processingResult = processFrame(dataHandler frameData data, tagSizeLimit, complete,
          v, tagList)
        tagList = processingResult._1
        dataHandler = processingResult._2
      }
    }
    this
  }

  /**
   * Creates an ''ID3Frame'' object with the data extracted so far by this
   * object. With this method the results of processing can be retrieved.
   * @return an ''ID3Frame'' object with the extracted ID3 data
   */
  def createFrame(): ID3Frame = ID3Frame(header, Map(tagList: _*))

  /**
   * Tries to create an ''ID3TagProvider'' from the data extracted so far by
   * this object. This may fail if the ID3v2 sub version is not supported;
   * therefore, result is an option. If the version is supported, a frame
   * is created, and a new provided is returned for it.
   * @return an option with an ''ID3TagProvider'' for the current frame data
   */
  def createTagProvider(): Option[ID3TagProvider] =
    versionData map (_.createProvider(createFrame()))
}


/**
 * A data class describing a complete ID3v2 frame. The frame consists of a
 * header (which can also be used to find out the version), and a map containing
 * the tags with the actual data.
 *
 * @param header the header of this frame
 * @param tags an immutable map with all ID3v2 tags; they can be directly
 *             accessed by name
 */
case class ID3Frame(header: ID3Header, tags: Map[String, ID3Tag])
