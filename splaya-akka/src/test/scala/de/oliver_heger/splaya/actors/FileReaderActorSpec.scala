package de.oliver_heger.splaya.actors

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

/**
 * Companion object for ''FileReaderActorSpec''.
 */
object FileReaderActorSpec {
  private val TestData = """Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy
                  eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam
                  voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita
                  kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem
                  ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod
                  tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At
                  vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd
                  gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem ipsum
                  dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor
                  invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero
                  eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no
                  sea takimata sanctus est Lorem ipsum dolor sit amet."""

  /**
   * Helper method for converting a string to a byte array.
   * @param s the string
   * @return the byte array
   */
  private def toBytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  /**
   * Returns a byte array with the complete test data.
   * @return the test bytes
   */
  private def testBytes() = toBytes(TestData)

  /**
   * Returns a byte array with the test data in the given range.
   * @param from index of the first byte
   * @param to index of the last byte (excluding)
   * @return the test bytes in the specified range
   */
  private def testBytes(from: Int, to: Int): Array[Byte] = toBytes(TestData.substring(from, to))
}

/**
 * Test class for ''FileReaderActor''.
 */
class FileReaderActorSpec(actorSystem: ActorSystem) extends TestKit(actorSystem)
with ImplicitSender with Matchers with FlatSpecLike with BeforeAndAfterAll {

  import de.oliver_heger.splaya.actors.FileReaderActor._
  import de.oliver_heger.splaya.actors.FileReaderActorSpec._

  /** Stores the temporary file created by a test case. */
  private lazy val testFile = createDataFile()

  def this() = this(ActorSystem("FileReaderActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds

    Files deleteIfExists testFile
  }

  /**
   * Creates a temporary file with test data.
   * @return the path to the test file
   */
  private def createDataFile(): Path = {
    val file = File.createTempFile("FileReaderActor", "tmp")
    val out = new FileOutputStream(file)
    try {
      out.write(testBytes())
    } finally {
      out.close()
    }
    file.toPath
  }

  private def readerActor(): ActorRef = {
    system.actorOf(Props[FileReaderActor])
  }

  /**
   * Helper method for calling the test actor to read the test file in a single
   * chunk.
   * @param reader the test reader actor
   * @return the result object with the chunk of data read from the file
   */
  private def readTestFile(reader: ActorRef): ReadResult = {
    val BufferSize = 2 * TestData.length
    reader ! ReadData(BufferSize)
    val result = expectMsgType[ReadResult]
    result.data.length should be(BufferSize)
    reader ! ReadData(BufferSize)
    expectMsgType[EndOfFile].path should be(testFile)
    result
  }

  /**
   * Checks the result object obtained from reading the test file with the
   * test read actor.
   * @param result the result object to be checked
   * @return the checked result object
   */
  private def checkTestFileReadResult(result: ReadResult): ReadResult = {
    result.length should be(TestData.length)
    result.data.take(result.length) should be(testBytes())
    result
  }

  "A FileReaderActor" should
    "send EOF message when queried for data in uninitialized state" in {
    val reader = readerActor()
    reader ! ReadData(128)
    expectMsgType[EndOfFile].path should be(null)
  }

  it should "be able to read a file in a single chunk" in {
    val reader = readerActor()
    reader ! InitFile(testFile)
    val result = readTestFile(reader)
    checkTestFileReadResult(result)
  }

  it should "be able to read a file in multiple small chunks" in {
    val BufferSize = TestData.length / 4
    val resultBuffer = ArrayBuffer.empty[Byte]
    val reader = readerActor()
    reader ! InitFile(testFile)
    reader ! ReadData(BufferSize)

    fishForMessage() {
      case ReadResult(data, len) =>
        resultBuffer ++= data take len
        reader ! ReadData(BufferSize)
        false

      case EndOfFile(_) =>
        true
    }
    resultBuffer.toArray should be(testBytes())
  }

  it should "allow reading multiple files in series" in {
    val reader = readerActor()
    reader ! InitFile(testFile)
    readTestFile(reader)

    reader ! InitFile(testFile)
    checkTestFileReadResult(readTestFile(reader))
  }
}
