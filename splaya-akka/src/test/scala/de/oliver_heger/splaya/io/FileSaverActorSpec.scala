package de.oliver_heger.splaya.io

import java.nio.file.Paths

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.splaya.FileTestHelper
import de.oliver_heger.splaya.utils.ChildActorFactory
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
 * Test class for ''FileSaverActor''.
 */
class FileSaverActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
with FlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfter with FileTestHelper {

  import FileTestHelper._

  /** A content string for a large test file. */
  private val largeFileContent = createLargeFileContent()

  def this() = this(ActorSystem("FileSaverActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  after {
    tearDownTestFile()
  }

  /**
   * Creates a content string for a large file whose size is bigger than the
   * chunk size used by the read actors involved.
   * @return the content string for the large test file
   */
  private def createLargeFileContent(): String = {
    val Size = 4096
    val buffer = new StringBuilder(Size + TestData.length)
    do {
      buffer append FileTestHelper.TestData
    } while (buffer.length < Size)
    buffer.toString()
  }

  /**
   * Returns a ''Props'' object for creating a ''FileSaverActor'' with a child
   * actor factory that returns the specified actor.
   * @param childActor the child actor to be returned by the mock factory
   * @return the ''Props''
   */
  private def propsWithMockChildActorFactory(childActor: ActorRef): Props = {
    Props(new FileSaverActor with ChildActorFactory {
      override def createChildActor(p: Props): ActorRef = {
        p.actorClass() should be(classOf[FileWriterActor])
        p.args shouldBe 'empty
        childActor
      }
    })
  }

  "A FileSaverActor" should "save a file in a single operation" in {
    val path = createPathInDirectory("test.dat")
    val saver = system.actorOf(FileSaverActor())

    saver ! FileSaverActor.SaveFile(path, toBytes(largeFileContent))
    expectMsg(FileSaverActor.FileSaved(path))
    readDataFile(path) should be(largeFileContent)
  }

  it should "stop write actors when an operation is complete" in {
    val writerActor = system.actorOf(Props[FileWriterActor])
    val saver = system.actorOf(propsWithMockChildActorFactory(writerActor))

    val probe = TestProbe()
    probe watch writerActor
    saver ! FileSaverActor.SaveFile(createPathInDirectory("test2.dat"), testBytes())
    expectMsgType[FileSaverActor.FileSaved]
    val termMsg = probe.expectMsgType[Terminated]
    termMsg.actor should be(writerActor)
  }

  it should "send a message about a failed write operation" in {
    val path = Paths.get(createPathInDirectory("dir").toString, "sub1", "sub2")
    val saver = system.actorOf(FileSaverActor())

    saver ! FileSaverActor.SaveFile(path, testBytes())
    val errMsg = expectMsgType[FileOperationActor.IOOperationError]
    errMsg.path should be(path)
  }
}
