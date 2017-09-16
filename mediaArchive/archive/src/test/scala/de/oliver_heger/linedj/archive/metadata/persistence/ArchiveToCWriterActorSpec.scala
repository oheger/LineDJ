/*
 * Copyright 2015-2017 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.archive.metadata.persistence

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.config.ArchiveContentTableConfig
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

object ArchiveToCWriterActorSpec {
  /** A default config for writing the ToC file. */
  private val ToCConfig = ArchiveContentTableConfig(contentFile = Some(Paths.get("content.json")),
    descriptionRemovePrefix = "C:\\music\\", descriptionPathSeparator = "\\",
    descriptionUrlEncoding = false, rootPrefix = Some("/music/"),
    metaDataPrefix = Some("/meta/"))

  /** The line separator. */
  private val CR = System.lineSeparator()

  /** A list with default content for the archive. */
  private val DefaultContentList = generateContent()

  /**
    * Generates a default ToC.
    *
    * @param separator the separator to be used for URI paths
    * @return the default ToC text
    */
  private def DefaultToC(separator: Char): String =
    ("[" + CR +
      """{"mediumDescriptionPath":"/music/U 2|playlist.settings",""" +
      """"metaDataPath":"/meta/393839.mdt"},""" + CR +
      """{"mediumDescriptionPath":"/music/classics|my favorites|playlist.settings",""" +
      """"metaDataPath":"/meta/ccccc.mdt"},""" + CR +
      """{"mediumDescriptionPath":"/music/Prince|playlist.settings",""" +
      """"metaDataPath":"/meta/abc.mdt"}""" + CR + "]" + CR)
      .replace('|', separator)

  /**
    * Generates a test medium ID.
    *
    * @param name the name of the medium
    * @return the test medium ID
    */
  private def mid(name: String): MediumID =
    MediumID("uri:" + name, Some(ToCConfig.descriptionRemovePrefix + name + "\\playlist.settings"))

  /**
    * Generates a list of default test content contained in the archive.
    *
    * @return the list with test content
    */
  private def generateContent(): List[(MediumID, String)] =
    List((mid("U 2"), "393839"), (mid("classics\\my favorites"), "ccccc"),
      (mid("Prince"), "abc"))
}

/**
  * Test class for ''ArchiveToCWriterActor''.
  */
class ArchiveToCWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender
  with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("ArchiveToCWriterActorSpec"))

  import ArchiveToCWriterActorSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Extracts the text content from the specified source.
    *
    * @param source the source
    * @return the content of the source as String
    */
  private def readSource(source: Source[ByteString, Any]): String = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val futSource = source.runFold(ByteString.empty)(_ ++ _)
    Await.result(futSource, 3.seconds).utf8String
  }

  "An ArchiveToCWriterActor" should "write a the correct ToC file" in {
    val helper = new WriterActorTestHelper

    val op = helper.sendWriteRequest().nextWriteOperation()
    op.target should be(ToCConfig.contentFile.get)
  }

  it should "use a source that produces the expected content" in {
    val helper = new WriterActorTestHelper
    val op = helper.sendWriteRequest().nextWriteOperation()

    readSource(op.source) should be(DefaultToC('\\'))
  }

  it should "URL-encode description paths if enabled" in {
    val config = ToCConfig.copy(descriptionUrlEncoding = true)
    val expected = DefaultToC('/').replace(" ", "%20")
    val helper = new WriterActorTestHelper
    val op = helper.sendWriteRequest(config = config).nextWriteOperation()

    readSource(op.source) should be(expected)
  }

  it should "handle an undefined root prefix" in {
    val config = ToCConfig.copy(rootPrefix = None)
    val helper = new WriterActorTestHelper
    val op = helper.sendWriteRequest(config = config).nextWriteOperation()

    val result = readSource(op.source)
    result should include("\"U 2")
  }

  it should "handle an undefined meta data prefix" in {
    val config = ToCConfig.copy(metaDataPrefix = None)
    val helper = new WriterActorTestHelper
    val op = helper.sendWriteRequest(config = config).nextWriteOperation()

    val result = readSource(op.source)
    result should include("\"abc")
  }

  it should "filter out entries rejected by the URI mapper" in {
    val contentList = (MediumID("uri", Some("otherPrefix/playlist.settings")),
      "foo") :: DefaultContentList
    val helper = new WriterActorTestHelper
    val op = helper.sendWriteRequest(content = contentList).nextWriteOperation()

    readSource(op.source) should be(DefaultToC('\\'))
  }

  it should "filter out entries without a description file" in {
    val contentList = (MediumID("noDesc", None), "bar") :: DefaultContentList
    val helper = new WriterActorTestHelper
    val op = helper.sendWriteRequest(content = contentList).nextWriteOperation()

    readSource(op.source) should be(DefaultToC('\\'))
  }

  it should "do nothing in the result propagation method" in {
    val targetFile = createFileReference()
    val config = ToCConfig.copy(contentFile = Some(targetFile))
    val helper = new WriterActorTestHelper(mockWrite = false)

    helper.sendWriteRequest(config = config).awaitPropagation()
    val TestMsg = new Object
    testActor ! TestMsg
    expectMsg(TestMsg)
  }

  it should "not stop itself in case of a failure" in {
    val fileWrittenCount = new AtomicInteger
    val actor = system.actorOf(Props(new ArchiveToCWriterActor {
      override protected def propagateResult(client: ActorRef, result: Any): Unit = {
        fileWrittenCount.incrementAndGet()
        handleFailure(client, new Exception("Test exception"))
      }
    }))
    val config1 = ToCConfig.copy(contentFile = Some(createFileReference()))
    actor ! ArchiveToCWriterActor.WriteToC(config1, DefaultContentList)
    awaitCond(fileWrittenCount.get() == 1)

    val config2 = ToCConfig.copy(contentFile = Some(createFileReference()))
    actor ! ArchiveToCWriterActor.WriteToC(config2, DefaultContentList)
    awaitCond(fileWrittenCount.get() == 2)
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    *
    * @param mockWrite  flag whether a write operation is to be mocked
    * @param latchCount the initial count for the countdown latch
    */
  private class WriterActorTestHelper(mockWrite: Boolean = true,
                                      latchCount: Int = 1) {
    /** A queue for keeping track of write operations. */
    private val queueWrites = new LinkedBlockingQueue[WriteOperation]

    /** A latch for waiting until the result propagation method is called. */
    private val latchPropagation = new CountDownLatch(latchCount)

    /** The test actor instance. */
    private val writer = createTestActor()

    /**
      * Sends the test actor a request to write a ToC based on the provided
      * parameters.
      *
      * @param config  the ToC config
      * @param content a list with the actual content
      * @return this test helper
      */
    def sendWriteRequest(config: ArchiveContentTableConfig = ToCConfig,
                         content: List[(MediumID, String)] = DefaultContentList):
    WriterActorTestHelper = {
      writer ! ArchiveToCWriterActor.WriteToC(config, content)
      this
    }

    /**
      * Returns information about the next write operation executed by the
      * test actor.
      *
      * @return data about the next write operation
      */
    def nextWriteOperation(): WriteOperation = {
      val op = queueWrites.poll(3, TimeUnit.SECONDS)
      op should not be null
      op
    }

    /**
      * Waits for the invocation of the result propagation method.
      *
      * @return this test helper
      */
    def awaitPropagation(): WriterActorTestHelper = {
      latchPropagation.await(3, TimeUnit.SECONDS) shouldBe true
      this
    }

    /**
      * Creates the test actor instance.
      *
      * @return the test actor instance
      */
    private def createTestActor(): ActorRef =
      system.actorOf(Props(new ArchiveToCWriterActor {
        /**
          * @inheritdoc This implementation records this write operation. If
          *             configured, it invokes the base implementation.
          */
        override protected def writeFile(source: Source[ByteString, Any], target: Path,
                                         resultMsg: => Any, client: ActorRef): Unit = {
          if (!mockWrite) {
            super.writeFile(source, target, resultMsg, client)
          }
          queueWrites offer WriteOperation(source, target, resultMsg, client)
        }

        /**
          * @inheritdoc Records this invocation by decrementing a latch, so
          *             test code can wait for it.
          */
        override protected def propagateResult(client: ActorRef, result: Any): Unit = {
          super.propagateResult(client, result)
          latchPropagation.countDown()
        }
      }))
  }

}

/**
  * A data class storing information about a write operation triggered by the
  * test actor.
  *
  * @param source    the source to write
  * @param target    the target path
  * @param resultMsg the result message
  * @param client    the client actor
  */
private case class WriteOperation(source: Source[ByteString, Any], target: Path,
                                  resultMsg: Any, client: ActorRef)
