package de.oliver_heger.splaya.io

import java.io.{IOException, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.util

import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.FileReaderActor.ReadResult
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.ArrayBuffer

object DynamicInputStreamSpec {
  /**
   * Extracts the bytes from a given string.
   * @param data the string
   * @return the bytes of this string
   */
  private def toBytes(data: String): Array[Byte] =
    data.getBytes(StandardCharsets.UTF_8)

  /**
   * Creates a ''ReadResult'' object with the specified string content.
   * @param data the content of the result object
   * @return the corresponding ''ReadResult'' object
   */
  private def createReadResult(data: String): ReadResult = {
    val bytes = toBytes(data)
    ReadResult(bytes, bytes.length)
  }

  /**
   * Creates a new test stream instance and appends the specified chunks to it.
   * @param chunks the chunks to be added
   * @return the new stream instance
   */
  private def createStreamWithChunks(chunks: String*): DynamicInputStream =
    appendChunks(new DynamicInputStream, chunks: _*)

  /**
   * Appends the data in the given chunks to the specified stream.
   * @param stream the stream
   * @param chunks the chunks of data to be appended
   * @return the modified stream
   */
  private def appendChunks(stream: DynamicInputStream, chunks: String*): DynamicInputStream = {
    chunks foreach (c => stream.append(createReadResult(c)))
    stream
  }

  /**
   * Reads the whole content from the given stream and stores it in an
   * output stream. The output stream can either be provided or is newly
   * created. It can later be used to verify that correct data was
   * read.
   * @param stream the stream to be read
   * @param optOutputStream an optional output stream for storing results
   * @param chunkSize the size of single chunks
   * @return the output stream with the data read
   */
  private def readStream(stream: DynamicInputStream, optOutputStream:
  Option[ByteArrayOutputStream] = None, chunkSize: Int = 16): ByteArrayOutputStream = {
    val bos = optOutputStream.getOrElse(new ByteArrayOutputStream)
    val buf = new Array[Byte](chunkSize)
    var count = stream.complete() read buf
    while (count != -1) {
      bos.write(buf, 0, count)
      count = stream read buf
    }
    bos
  }

  /**
   * Appends data to a stream while reading portions of data. This method
   * implements a basic check for the management of the chunks of a
   * ''DynamicInputStream''.
   * @param stream the test stream
   * @param optOutputStream an optional output stream for storing read results;
   *                        if this is not provided, a new stream is created
   * @param bufSize the size of the buffer for read operations
   * @param data a sequence of chunks to be added to the stream
   * @return the output stream with the data read from the stream
   */
  private def readWhileAppending(stream: DynamicInputStream, optOutputStream:
  Option[ByteArrayOutputStream], bufSize: Int, data: Seq[String]): ByteArrayOutputStream = {
    val buf = new Array[Byte](bufSize)
    val bos = optOutputStream.getOrElse(new ByteArrayOutputStream)
    for (c <- data) {
      stream append createReadResult(c)
      val count = stream read buf
      bos.write(buf, 0, count)
    }
    bos
  }

  /**
   * Combines a number of string chunks to an array.
   * @param chunks the chunks to be combined
   * @return the resulting array
   */
  private def combineChunks(chunks: String*): Array[Byte] = {
    val buffer = ArrayBuffer.empty[Byte]
    chunks foreach (buffer ++= toBytes(_))
    val expectedArray = buffer.toArray
    expectedArray
  }
}

/**
 * Test class for ''DynamicInputStream''.
 */
class DynamicInputStreamSpec extends FlatSpec with Matchers {

  import de.oliver_heger.splaya.io.DynamicInputStreamSpec._

  /**
   * Checks whether the expected data has been read from a test stream.
   * @param bos an output stream collecting the test reads
   * @param chunks the content of the expected chunks
   * @return the array extracted from the output stream
   */
  private def checkReadResult(bos: ByteArrayOutputStream, chunks: String*): Array[Byte] = {
    val expectedArray: Array[Byte] = combineChunks(chunks: _*)
    val testArray = bos.toByteArray
    testArray should be(expectedArray)
    testArray
  }

  "A DynamicInputStream" should "read no data if it has no content" in {
    def createBuffer(): Array[Byte] = {
      val buffer = new Array[Byte](16)
      util.Arrays.fill(buffer, 1.asInstanceOf[Byte])
      buffer
    }

    val stream = new DynamicInputStream
    val buffer = createBuffer()
    stream.read(buffer) should be(0)
    buffer should be(createBuffer())
  }

  it should "have 0 bytes available after its creation" in {
    val stream = new DynamicInputStream
    stream.available() should be(0)
  }

  it should "return -1 in read() if it has no content" in {
    val stream = new DynamicInputStream
    stream.read() should be(-1)
  }

  /**
   * Reads a stream using the read() method that returns a single byte.
   * @param stream the stream to be read
   * @return an output stream with the data read
   */
  private def readStreamByteWise(stream: DynamicInputStream): ByteArrayOutputStream = {
    val bos = new ByteArrayOutputStream
    var c = stream.read()
    while (c != -1) {
      bos write c
      c = stream.read()
    }
    bos
  }

  it should "allow reading a single chunk of data byte-wise" in {
    val Data = "I would I were thy bird."
    val stream = createStreamWithChunks(Data)

    checkReadResult(readStreamByteWise(stream), Data)
  }

  it should "allow reading multiple chunks of data byte-wise" in {
    val c1 = "What man art thou, that, thus bescreen'd in night,"
    val c2 = "So stumblest on my counsel?"
    val stream = createStreamWithChunks(c1, c2)

    checkReadResult(readStreamByteWise(stream), c1 + c2)
  }

  it should "return the number of available bytes of a single chunk" in {
    val Data = "Madam!"
    val chunk = createReadResult(Data)
    val stream = new DynamicInputStream

    stream.append(chunk) should be(stream)
    stream.available() should be(chunk.length)
  }

  it should "return the number of available bytes if multiple chunks are involved" in {
    val chunk1 = createReadResult("Shall I hear more,")
    val chunk2 = createReadResult("or shall I speak at this?")
    val stream = new DynamicInputStream

    stream append chunk1
    stream append chunk2
    stream.read()
    stream.available() should be(chunk1.length + chunk2.length - 1)
  }

  it should "allow reading an array from a single chunk of data" in {
    val Data = """By and by, I come—
                 |To cease thy strife, and leave me to my grief.
                 |To-morrow will I send."""
    val stream = createStreamWithChunks(Data)
    val length = stream.available()

    val bos = new ByteArrayOutputStream
    val buf = new Array[Byte](length)
    stream.read(buf) should be(length)
    bos.write(buf, 0, length)
    checkReadResult(bos, Data)
  }

  it should "allow reading an array with offset and length from a single chunk of data" in {
    val Data = """A thousand times the worse, to want thy light.
                 |Love goes toward love as schoolboys from their books,
                 |But love from love, toward school with heavy looks."""
    val stream = createStreamWithChunks(Data)
    val length = stream.available()

    val bos = new ByteArrayOutputStream
    val buf = new Array[Byte](2 * length)
    val offset = 8
    stream.read(buf, offset, buf.length - offset) should be(length)
    bos.write(buf, offset, length)
    checkReadResult(bos, Data)
  }

  it should "take the offset of an ArraySource into account" in {
    val Data = List("O, I am fortune's fool!", "You fools of fortune.")
    val startIndex = 3
    val stream = new DynamicInputStream

    Data map { s => new ArraySource {
      override val data: Array[Byte] = toBytes(s)
      override val length: Int = data.length - startIndex
      override val offset: Int = startIndex
    }
    } foreach { src => stream append src}
    stream.complete()

    val buffer = ArrayBuffer.empty[Byte]
    Data foreach { s => buffer ++= toBytes(s) drop startIndex}
    readStream(stream).toByteArray should be(buffer.toArray)
  }

  it should "read no data if no content is available" in {
    val Data = "So thrive my soul-"
    val stream = createStreamWithChunks(Data)
    val buf = new Array[Byte](128)
    val bos = new ByteArrayOutputStream
    val length = stream read buf
    bos.write(buf, 0, length)
    checkReadResult(bos, Data)

    util.Arrays.fill(buf, 8.toByte)
    stream.read(buf) should be(0)
    assert(buf forall (_ == 8.toByte))
  }

  it should "allow reading data from multiple chunks" in {
    val Data = Array("O Romeo, Romeo, wherefore art thou Romeo?",
      "Deny thy father and refuse thy name;",
      "Or if thou wilt not, be but sworn my love",
      "And I'll no longer be a Capulet.")
    val stream = createStreamWithChunks(Data: _*)

    checkReadResult(readStream(stream), Data: _*)
  }

  it should "have the default initial capacity if not specified otherwise" in {
    val stream = new DynamicInputStream
    stream.capacity should be(DynamicInputStream.DefaultCapacity)
  }

  /**
   * Checks whether data can be read from a stream while still chunks are
   * added.
   * @param initialCapacity the initial capacity to be used for the stream
   * @return the test stream
   */
  private def checkCombinedReadAndAppendOperations(initialCapacity: Int): DynamicInputStream = {
    val Data = Array("'Tis but thy name that is my enemy:",
      "Thou art thyself, though not a Montague.",
      "What's Montague? It is nor hand nor foot,",
      "Nor arm nor face, nor any other part",
      "Belonging to a man. O be some other name!",
      "What's in a name? That which we call a rose",
      "By any other word would smell as sweet;",
      "So Romeo would, were he not Romeo call'd,",
      "Retain that dear perfection which he owes",
      "Without that title. Romeo, doff thy name,",
      "and for thy name, which is no part of thee,",
      "Take all myself.")
    val stream = new DynamicInputStream(initialCapacity)
    val bos = readWhileAppending(stream, None, 16, Data)
    readStream(stream = stream, optOutputStream = Some(bos))
    checkReadResult(bos, Data: _*)
    stream
  }

  it should "allow combined read and append operations within its capacity" in {
    checkCombinedReadAndAppendOperations(8)
  }

  it should "increase its capacity automatically" in {
    val stream = checkCombinedReadAndAppendOperations(4)
    stream.capacity should be(8)
  }

  it should "not allow adding data after it has been completed" in {
    val stream = new DynamicInputStream
    stream append createReadResult("O true apothecary!")
    stream.complete()

    intercept[IllegalStateException] {
      stream append createReadResult("Thy drugs are quick. Thus with a kiss I die.")
    }
  }

  it should "indicate that it supports mark operations" in {
    val stream = new DynamicInputStream
    stream.markSupported should be (right = true)
  }

  it should "throw an exception if reset() is called without mark()" in {
    val stream = new DynamicInputStream
    stream append createReadResult("Swits and spurs, swits and spurs, or I'll cry a match.")
    stream.read()

    intercept[IOException] {
      stream.reset()
    }
  }

  /**
   * Helper method for checking whether reset() works as expected - event if
   * applied multiple times.
   * @param numberOfResets the number of reset operations to execute
   */
  private def checkReset(numberOfResets: Int): Unit = {
    val Data = Array("Romeo:",
      "Hold, Tybalt! Good Mercutio!",
      "[Tybalt under Romeo's arm thrusts Mercutio in. Away Tybalt]",
      "Mercutio:",
      "I am hurt.",
      "A plague a' both your houses! I am sped.",
      "Is he gone and hath nothing?")
    val stream = new DynamicInputStream(4)
    stream append createReadResult(Data(0))
    val buf = new Array[Byte](1024)
    stream read buf

    stream.mark(16384) // this actually means "no limit"
    val bos = readWhileAppending(stream, None, 16, Data.tail)
    val count = stream read buf
    bos.write(buf, 0, count)
    checkReadResult(bos, Data.tail: _*)

    for(i <- 0 until numberOfResets) {
      stream.reset()
      val bos2 = new ByteArrayOutputStream
      val count = stream read buf
      bos2.write(buf, 0, count)
      checkReadResult(bos2, Data.tail: _*)
    }
  }

  it should "handle mark and reset operations correctly" in {
    checkReset(1)
  }

  it should "support multiple resets to the same mark" in {
    checkReset(4)
  }

  it should "ignore a mark operation when the read limit is reached" in {
    val stream = new DynamicInputStream(3)
    stream append createReadResult("Romeo, away, be gone!")
    val buf = new Array[Byte](8)
    stream read buf
    stream.mark(16)

    appendChunks(stream, "The citizens are up, and Tybalt slain.",
    "Stand not amaz'd, the Prince will doom thee death")
    stream read buf
    stream read buf
    stream read buf
    appendChunks(stream, "If thou art taken. Hence be gone, away!")
    stream.capacity should be (3)
    intercept[IOException] {
      stream.reset()
    }
  }

  it should "support a clear operation" in {
    val stream = new DynamicInputStream
    appendChunks(stream, "Romeo, away, be gone!",
      "The citizens are up, and Tybalt slain.",
      "Stand not amaz'd, the Prince will doom thee death")
    stream.complete()

    stream.clear()
    stream.available() should be(0)
    stream should not be 'completed
    val Chunk = "If thou art taken. Hence be gone, away!"
    appendChunks(stream, Chunk)
    readStream(stream).toByteArray should be(toBytes(Chunk))
  }

  it should "remove mark data when cleared" in {
    val stream = new DynamicInputStream
    appendChunks(stream, "Double, double toil and trouble", "Fire burn, and cauldron bubble.")
    stream.read()
    stream.mark(64)

    stream.clear()
    intercept[IOException] {
      stream.reset()
    }
  }

  it should "create an array source automatically if necessary" in {
    val Data = """I prithee do not mock me, fellow studient,
                 |I think it was to see my mother's wedding.""".stripMargin
    val stream = new DynamicInputStream

    stream append toBytes(Data)
    checkReadResult(readStream(stream), Data)
  }

  it should "offer a convenience method for creating array source objects" in {
    val Data = toBytes("Indeed, my lord, it followed hard upon.")
    val source = DynamicInputStream.arraySourceFor(Data, 7)

    source.data should be(Data)
    source.offset should be(7)
    source.length should be(Data.length - 7)
  }

  it should "report a failed find operation" in {
    val Data = "My lord, I came to see your father's funeral."
    val stream = createStreamWithChunks(Data)

    stream find '*'.toByte shouldBe false
  }

  it should "fail a find operation if no data is available" in {
    val stream = new DynamicInputStream

    stream find 0 shouldBe false
  }

  it should "be able to find a specific byte in a chunk" in {
    val Data = "My lord, I came to see your father's funeral."
    val stream = createStreamWithChunks(Data)

    stream find ','.toByte shouldBe true
    val expected = Data substring 8
    stream.available() should be(expected.length)
    checkReadResult(readStream(stream), expected)
  }

  it should "be able to find a specific byte in multiple chunks" in {
    val Remaining = " furnish forth the marriage tables."
    val Data = Array("Thrift, thrift, Horatio, the funeral bak'd-meats", "Did coldly" + Remaining)
    val stream = createStreamWithChunks(Data: _*)
    val chunk = new Array[Byte](5)
    stream read chunk

    stream find 'y' shouldBe true
    stream.available() should be(Remaining.length)
    checkReadResult(readStream(stream), Remaining)
  }

  it should "skip to the end of the stream after a failed find operation" in {
    val stream = createStreamWithChunks("Ay, marry, is't,", "But to my mind, though I am " +
      "native here", "And to the manner born, it is a custom",
      "More honor'd in the breach than the observance.")

    stream find 'x' shouldBe false
    stream.available() should be(0)
  }

  it should "support reset together with find" in {
    val Data = "What does this mean, my lord?"
    val stream = createStreamWithChunks(Data)

    stream mark 1000
    stream find ',' shouldBe true
    stream.reset()
    checkReadResult(readStream(stream), Data)
  }

  it should "allow skipping data in a single chunk" in {
    val Remaining = " face."
    val Data = "Then saw you not his" + Remaining
    val skipLen = 20
    val stream = createStreamWithChunks(Data)

    stream skip skipLen should be(skipLen)
    checkReadResult(readStream(stream), Remaining)
  }

  it should "allow skipping data over multiple chunks" in {
    val Data = Array("The King doth wake to-night and takes his rouse,",
      "Keeps wassail, and the swagg'ring up-spring reels;",
      "And as he drains his draughts of Rhenish down,",
      "The kettle-drum and trumpet thus bray out",
      "The triumph of his pledge.", "Is it a custom?")
    val ChunkLen = 10
    val SkipLen = 60
    val stream = createStreamWithChunks(Data: _*)
    val chunk = new Array[Byte](ChunkLen)
    stream read chunk

    stream skip SkipLen should be(SkipLen)
    val remaining = combineChunks(Data: _*) drop ChunkLen + SkipLen
    stream.available() should be(remaining.length)
    val bos = readStream(stream)
    bos.toByteArray should be(remaining)
  }

  it should "support reset together with skip" in {
    val Data = "O day and night, but this is wondrous strange!"
    val readLen = 5
    val stream = createStreamWithChunks(Data)
    stream read new Array[Byte](readLen)

    stream mark 100
    stream skip 15 should be(15)
    stream.reset()
    val out = readStream(stream)
    out.toByteArray should be(toBytes(Data.substring(readLen)))
  }

  it should "check for the number of bytes available before a skip operation" in {
    val stream = createStreamWithChunks("A countenance more", "In sorrow than in anger.")
    val count = stream.available()

    stream skip 1000 should be(count)
    stream.available() should be(0)
    stream read new Array[Byte](16) should be(0)
  }
}
