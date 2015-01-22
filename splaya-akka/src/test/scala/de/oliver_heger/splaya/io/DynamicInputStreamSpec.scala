package de.oliver_heger.splaya.io

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util

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
    val buffer = ArrayBuffer.empty[Byte]
    chunks foreach (buffer ++= toBytes(_))
    val testArray = bos.toByteArray
    testArray should be(buffer.toArray)
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

  it should "allow reading a single chunk of data byte-wise" in {
    val Data = "I would I were thy bird."
    val stream = createStreamWithChunks(Data)

    val bos = new ByteArrayOutputStream
    var c = stream.read()
    while (c != -1) {
      bos write c
      c = stream.read()
    }
    checkReadResult(bos, Data)
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
    val bos = new ByteArrayOutputStream
    val stream = new DynamicInputStream(initialCapacity)
    val buf = new Array[Byte](16)

    for (c <- Data) {
      stream append createReadResult(c)
      val count = stream read buf
      bos.write(buf, 0, count)
    }
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
}
