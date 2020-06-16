/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3.processor

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.{DelayOverflowStrategy, KillSwitch}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.stream.CancelableStreamSupport
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.{MetaDataProcessingSuccess, ProcessMetaDataFile}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object Mp3MetaDataExtractorActorSpec {
  /** A tag size limit. */
  private val TagSizeLimit = 4096

  /** A test read chunk size. */
  private val ReadChunkSize = 2048

  /** A test file data object. */
  private val TestFileData = FileData("someMp3File.mp3", 2354875L)

  /** A test result template object. */
  private val ResultTemplate = MetaDataProcessingSuccess("somePath.mp3",
    MediumID("someMedium", Some("someSettings")), "someURI", null)

  /**
    * A data defining an expected child actor creation. An instance holds the
    * actor to be returned for the child and the expected file to be
    * processed.
    *
    * @param child    the child actor to be returned
    * @param fileData data about the file to be processed by the child actor
    */
  private case class ExpectedChildActorCreation(child: ActorRef, fileData: FileData)

}

/**
  * Test class for ''Mp3MetaDataExtractorActor''.
  */
class Mp3MetaDataExtractorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {

  import Mp3MetaDataExtractorActorSpec._

  def this() = this(ActorSystem("Mp3MetaDataExtractorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Copies the test file from class path resources to a temporary file. This
    * is necessary because the MP3 processing stream requires a valid ''Path''
    * object.
    *
    * @param name the name of the test file
    * @return the path under which the file can be accessed
    */
  private def copyTestFile(name: String): Path = {
    val path = createPathInDirectory(name)
    if (!Files.exists(path)) {
      val stream = getClass.getResourceAsStream("/" + name)
      val out = Files.newOutputStream(path)
      try {
        val buf = new Array[Byte](4096)
        var len = stream.read(buf)
        while (len >= 0) {
          out.write(buf, 0, len)
          len = stream.read(buf)
        }
      } finally {
        out.close()
      }
    }
    path
  }

  /**
    * Returns a basic partial function to be used when fishing for a message
    * sent to the mp3 file processor actor. This function takes all the
    * messages into account that should be sent during stream processing. It
    * can be used as fall-back function when searching for specific messages.
    *
    * @return the base fishing function
    */
  private def basicFishingFunc: PartialFunction[Any, Boolean] = {
    case Mp3StreamInit => false
    case _: ProcessMp3Data => false
    case _: ProcessID3FrameData => false
    case _: IncompleteID3Frame => false
    case _: ID3v1MetaData => false
    case Mp3StreamCompleted => true
  }

  "An Mp3MetaDataExtractorActor" should "create correct Props" in {
    val metaDataActor = TestProbe().ref
    val props = Mp3MetaDataExtractorActor(metaDataActor, TagSizeLimit, ReadChunkSize)

    classOf[Mp3MetaDataExtractorActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CancelableStreamSupport].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(metaDataActor, TagSizeLimit, ReadChunkSize))
  }

  it should "send an init message when processing a file" in {
    val helper = new Mp3MetaDataExtractorTestHelper

    helper.fishForStreamingMessage("test.mp3") {
      case Mp3StreamInit => true
    } should be(Mp3StreamInit)
  }

  it should "send messages to process MP3 frames" in {
    val chunkSize = new AtomicInteger
    val frameCount = new AtomicInteger
    val helper = new Mp3MetaDataExtractorTestHelper

    helper.fishForStreamingMessage("test2.mp3") {
      case mp3Data: ProcessMp3Data =>
        if (chunkSize.get() < mp3Data.data.length) {
          chunkSize.set(mp3Data.data.length)
        }
        frameCount.incrementAndGet()
        false
    } should be(Mp3StreamCompleted)
    chunkSize.get() should be(ReadChunkSize)
    frameCount.get() should be(53)
  }

  it should "send messages to process ID3v2 frames" in {
    val helper = new Mp3MetaDataExtractorTestHelper

    val msg = helper.fishForStreamingMessage("test.mp3") {
      case _: ProcessID3FrameData => true
    }.asInstanceOf[ProcessID3FrameData]
    msg.frameHeader.version should be(3)
  }

  it should "send messages to process ID3v1 frames" in {
    val helper = new Mp3MetaDataExtractorTestHelper

    val msg = helper.fishForStreamingMessage("testMP3id3v1.mp3") {
      case _: ID3v1MetaData => true
    }.asInstanceOf[ID3v1MetaData]
    msg.metaData.get.title should be(Some("Test Title"))
  }

  it should "send an error message if there is a processing failure" in {
    val helper = new Mp3MetaDataExtractorTestHelper

    helper.fishForStreamingMessage(FileData("non/existing/file.mp3", 42)) {
      case Mp3StreamFailure(_) => true
    } shouldBe a[Mp3StreamFailure]
  }

  it should "handle a cancel request if no stream is active" in {
    val helper = new Mp3MetaDataExtractorTestHelper

    helper.postMessage(CloseRequest)
      .expectCloseAck()
  }

  it should "cancel active streams on receiving a cancel request" in {
    val source = Source(List(ByteString("These are"), ByteString(" test "),
      ByteString("byte strings."))).delay(1.second, DelayOverflowStrategy.backpressure)
    val chunkCount = new AtomicInteger
    val helper = new Mp3MetaDataExtractorTestHelper(optStreamSource = Some(source))

    helper.fishForStreamingMessage("test.mp3") {
      case _: ProcessMp3Data =>
        chunkCount.incrementAndGet()
        false
    }
    helper.postMessage(CloseRequest)
    expectNoMessage(1.second)
    chunkCount.get() should be < 3
  }

  it should "remove kill switch registrations when processing is done" in {
    val helper = new Mp3MetaDataExtractorTestHelper

    helper.fishForStreamingMessage("test.mp3")(basicFishingFunc)
    helper.stopProcessingActors()
      .expectKillSwitchUnRegistration()
  }

  it should "send a CloseAck when all active streams are done" in {
    val helper = new Mp3MetaDataExtractorTestHelper
    helper.fishForStreamingMessage("test.mp3")(basicFishingFunc)
    helper.fishForStreamingMessage("testMP3id3v1.mp3")(basicFishingFunc)

    helper.postMessage(CloseRequest)
      .stopProcessingActors()
      .expectCloseAck()
  }

  it should "reset a pending close operation after it is done" in {
    val helper = new Mp3MetaDataExtractorTestHelper
    helper.fishForStreamingMessage("test.mp3")(basicFishingFunc)
    helper.postMessage(CloseRequest).stopProcessingActors().expectCloseAck()

    helper.fishForStreamingMessage("testMP3id3v1.mp3")(basicFishingFunc)
    helper.stopProcessingActors()
    expectNoMessage(1.second)
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class Mp3MetaDataExtractorTestHelper(optStreamSource: Option[Source[ByteString, Any]]
                                               = None) {
    /** A queue for keeping track on newly created child actors. */
    private val childActorQueue = new LinkedBlockingQueue[ExpectedChildActorCreation]

    /** Test probe for the meta data manager actor. */
    private val probeMetaDataManager = TestProbe()

    /** Stores the IDs of kill switches that have been registered. */
    private val registeredKillSwitches = new AtomicReference(Set.empty[Int])

    /** Stores the IDs of kill switches that have been removed. */
    private val removedKillSwitches = new AtomicReference(Set.empty[Int])

    /** The test actor instance. */
    private val mp3Extractor = createTestActor()

    /** Stores the child processor actors created by this helper. */
    private var processorActors = List.empty[ActorRef]

    /**
      * Starts a processing operation for the specified file name and fishes
      * for specific messages. The file is copied from the class path resources
      * if necessary.
      *
      * @param file the name of the test file to be processed
      * @param f    the fishing function (will be extended by the basic function)
      * @return the retrieved message
      */
    def fishForStreamingMessage(file: String)(f: PartialFunction[Any, Boolean]): Any = {
      val testPath = copyTestFile(file)
      val size = Files.size(testPath)
      fishForStreamingMessage(FileData(testPath.toString, size))(f)
    }

    /**
      * Starts a processing operation for the specified file and fishes for
      * specific messages. The file is passed as-is to the test actor.
      *
      * @param fileData the data object for the file to be processed
      * @param f        the fishing function (will be extended by the basic function)
      * @return the retrieved message
      */
    def fishForStreamingMessage(fileData: FileData)(f: PartialFunction[Any, Boolean]): Any = {
      val msgQueue = new LinkedBlockingQueue[AnyRef]
      val fishingFunc = f orElse basicFishingFunc
      val child = system.actorOf(Props(classOf[Mp3FileProcessorTestActor],
        msgQueue, fishingFunc))
      processorActors = child :: processorActors
      childActorQueue offer ExpectedChildActorCreation(child, fileData)

      mp3Extractor ! ProcessMetaDataFile(fileData, ResultTemplate)
      val foundMsg = msgQueue.poll(3, TimeUnit.SECONDS)
      foundMsg should not be null
      foundMsg
    }

    /**
      * Posts the specified message to the test actor.
      *
      * @param msg the message
      * @return this test helper
      */
    def postMessage(msg: Any): Mp3MetaDataExtractorTestHelper = {
      mp3Extractor ! msg
      this
    }

    /**
      * Expects a CloseAck message from the test actor sent to the implicit
      * sender.
      *
      * @return this test helper
      */
    def expectCloseAck(): Mp3MetaDataExtractorTestHelper = {
      expectMsg(CloseAck(mp3Extractor))
      this
    }

    /**
      * Stops all processing actors that have been created by this helper so
      * far.
      *
      * @return this test helper
      */
    def stopProcessingActors(): Mp3MetaDataExtractorTestHelper = {
      processorActors foreach system.stop
      processorActors = Nil
      this
    }

    /**
      * Checks that kill switches are un-registered after processing of their
      * file is done.
      *
      * @return this test helper
      */
    def expectKillSwitchUnRegistration(): Mp3MetaDataExtractorTestHelper = {
      registeredKillSwitches.get().size should be > 0
      awaitCond(removedKillSwitches.get() == registeredKillSwitches.get())
      this
    }

    /**
      * Creates a test actor instance.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[Mp3MetaDataExtractorActor] =
      TestActorRef(createTestProps())

    /**
      * Creates ''Props'' for creating a test instance.
      *
      * @return the creation ''Props''
      */
    private def createTestProps(): Props =
      Props(new Mp3MetaDataExtractorActor(probeMetaDataManager.ref,
        TagSizeLimit, ReadChunkSize) with ChildActorFactory with CancelableStreamSupport {
        /**
          * @inheritdoc This implementation checks the properties for the child
          *             actor and returns a test probe. The probe is also passed
          *             to the child actor queue.
          */
        override def createChildActor(p: Props): ActorRef = {
          val creation = childActorQueue.poll(3, TimeUnit.SECONDS)
          creation should not be null
          val refProps = Mp3FileProcessorActor(probeMetaDataManager.ref, TagSizeLimit,
            TestFileData, ResultTemplate)
          p.actorClass() should be(refProps.actorClass())
          p.args should have length refProps.args.length
          p.args.head should be(refProps.args.head)
          p.args(1) should be(refProps.args(1))
          p.args(3) should be(refProps.args(3))
          val collector = p.args(2).asInstanceOf[MetaDataPartsCollector]
          collector.file should be(creation.fileData)
          creation.child
        }

        /**
          * @inheritdoc Either returns the provided test source or calls the
          *             inherited method.
          */
        override private[processor] def createSource(file: FileData): Source[ByteString, Any] =
          optStreamSource getOrElse super.createSource(file)

        /**
          * @inheritdoc Records the registration.
          */
        override def registerKillSwitch(ks: KillSwitch): Int = {
          val regID = super.registerKillSwitch(ks)
          registeredKillSwitches.set(registeredKillSwitches.get() + regID)
          regID
        }

        /**
          * @inheritdoc Records the removal of the registration.
          */
        override def unregisterKillSwitch(ksID: Int): Unit = {
          val removedIDs = removedKillSwitches.get()
          removedIDs should not contain ksID
          removedKillSwitches.set(removedIDs + ksID)
          super.unregisterKillSwitch(ksID)
        }
      })
  }

}

/**
  * An actor class simulating an MP3 file processor actor. This class is used
  * to detect specific messages passed to the actor from the streaming
  * infrastructure. It also sends the required ACK messages.
  *
  * An instance is initialized with a partial function defined for expected
  * messages. If the function returns '''true''' for a message, this message
  * is put into a queue from where it can be obtained from test code. Messages
  * for which the partial function is not defined are also stored in the queue;
  * the test code can then detect an unexpected message.
  *
  * @param queue the queue for storing found messages
  * @param f     the partial function selecting the messages to look for
  */
class Mp3FileProcessorTestActor(queue: LinkedBlockingQueue[Any],
                                f: PartialFunction[Any, Boolean]) extends Actor {
  override def receive: Receive = {
    case m =>
      if (m == Mp3StreamInit || m.isInstanceOf[ProcessMp3Data]) {
        sender ! Mp3ChunkAck
      }
      if (!f.isDefinedAt(m) || f(m)) {
        queue offer m
      }
  }
}
