/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.extract.metadata

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.io.{CloseHandlerActor, CloseRequest, CloseSupport, FileData}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.{MetaDataProcessingError, MetaDataProcessingSuccess, ProcessMetaDataFile}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object MetaDataExtractorWrapperActorSpec {
  /** File extension for supported files. */
  private val SupportedFileExtension = "mp3"

  /** File extension for unsupported files. */
  private val UnsupportedFileExtension = "txt"

  /** Test medium ID. */
  private val TestMediumID = MediumID("music://test", Some("test.settings"))

  /** Test ping message to check that an actor did not receive a message. */
  private val Ping = new Object

  /**
    * Generates the file size of a test file (based on its name).
    *
    * @param path the path to the test file
    * @return the file size
    */
  private def fileSize(path: String): Int = path.length * 367

  /**
    * Generates the path to a test file.
    *
    * @param name the file name
    * @param ext  the extension
    * @return the path to this test file
    */
  private def testPath(name: String, ext: String): String =
    s"/music/$name.$ext"

  /**
    * Generates the URI for a test file.
    *
    * @param name the file name
    * @param ext  the extension
    * @return the URI for this test file
    */
  private def testUri(name: String, ext: String): String =
    s"song://music/test/$name.$ext"

  /**
    * Creates a ''ProcessMetaDataFile'' message for a test file.
    *
    * @param name the name of the test file
    * @param ext  the extension
    * @return the process request message
    */
  private def createProcessRequest(name: String, ext: String): ProcessMetaDataFile = {
    val path = testPath(name, ext)
    val fileData = FileData(path, fileSize(path))
    val template = MetaDataProcessingSuccess(path, TestMediumID,
      testUri(name, ext), MediaMetaData())
    ProcessMetaDataFile(fileData, template)
  }

  /**
    * Creates a processing result object for a test file.
    *
    * @param name the name of the test file
    * @param ext  the extension
    * @return the processing result
    */
  private def createProcessingResult(name: String, ext: String): MetaDataProcessingSuccess = {
    val path = testPath(name, ext)
    val data = MediaMetaData(size = fileSize(path), title = Some(name),
      artist = Some("Artist for " + name), album = Some(name + " album"))
    MetaDataProcessingSuccess(path = path, uri = testUri(name, ext),
      mediumID = TestMediumID, metaData = data)
  }
}

/**
  * Test class for ''MetaDataExtractorWrapperActor''.
  */
class MetaDataExtractorWrapperActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import MetaDataExtractorWrapperActorSpec._

  def this() = this(ActorSystem("MetaDataExtractorWrapperActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MetaDataExtractorActor" should "return correct Props" in {
    val factory = mock[ExtractorActorFactory]
    val props = MetaDataExtractorWrapperActor(factory)

    classOf[MetaDataExtractorWrapperActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(factory))
  }

  it should "return a dummy result for an unsupported extension" in {
    val helper = new ExtractorActorTestHelper
    val TestName = "UnsupportedMediaFile"
    val expData = MediaMetaData(size = fileSize(testPath(TestName, UnsupportedFileExtension)))
    val expResult = createProcessingResult(TestName, UnsupportedFileExtension)
      .copy(metaData = expData)

    helper.postProcessRequest(TestName, UnsupportedFileExtension)
    expectMsg(expResult)
  }

  it should "delegate a process request to an extractor actor" in {
    val helper = new ExtractorActorTestHelper
    val TestName = "CoolSong"

    helper.postProcessRequest(TestName, SupportedFileExtension)
      .expectDelegationToExtractor(TestName, SupportedFileExtension)
  }

  it should "record the result of the extractor factory" in {
    val helper = new ExtractorActorTestHelper

    helper.postProcessRequest("file1", UnsupportedFileExtension)
      .postProcessRequest("file2", UnsupportedFileExtension)
    expectMsgType[MetaDataProcessingSuccess]
    expectMsgType[MetaDataProcessingSuccess]
    helper.verifyExtractorFactoryRequest(UnsupportedFileExtension)
  }

  it should "support multiple extractor actors" in {
    val probeExtractor2 = TestProbe()
    val props = Props(classOf[MetaDataExtractorWrapperActor], mock[ExtractorActorFactory])
    val File1 = "Song1"
    val File2 = "AnotherSong"
    val Ext2 = "ogg"
    val helper = new ExtractorActorTestHelper

    helper.expectChildCreation(props, probeExtractor2.ref)
      .addFileExtension(Ext2, Some(props))
      .postProcessRequest(File1, SupportedFileExtension)
      .postProcessRequest(File2, Ext2)
      .expectDelegationToExtractor(File1, SupportedFileExtension)
    probeExtractor2.expectMsg(createProcessRequest(File2, Ext2))
  }

  it should "handle files without an extension" in {
    val fileData = FileData("PathWithoutExtension", 28)
    val msg = ProcessMetaDataFile(fileData,
      MetaDataProcessingSuccess("a path", TestMediumID, "a URI", MediaMetaData()))
    val helper = new ExtractorActorTestHelper

    helper.addFileExtension("", None)
      .post(msg)
    expectMsgType[MetaDataProcessingSuccess]
  }

  it should "propagate a processing result to the caller" in {
    val File = "ProcessedMetaDataFile"
    val result = createProcessingResult(File, SupportedFileExtension)
    val helper = new ExtractorActorTestHelper

    helper.postProcessRequest(File, SupportedFileExtension)
      .post(result)
    expectMsg(result)
  }

  it should "handle multiple callers" in {
    val File1 = "mediaFile1"
    val File2 = "nextMediaFile"
    val result1 = createProcessingResult(File1, SupportedFileExtension)
    val result2 = createProcessingResult(File2, SupportedFileExtension)
    val probe = TestProbe()
    val helper = new ExtractorActorTestHelper

    helper.postProcessRequest(File1, SupportedFileExtension)
      .post(createProcessRequest(File2, SupportedFileExtension), probe.ref)
      .post(result1)
      .post(result2)
    expectMsg(result1)
    probe.expectMsg(result2)
  }

  it should "handle error processing results" in {
    val File = "errorFile"
    val errResult = MetaDataProcessingError(testPath(File, SupportedFileExtension), TestMediumID,
      testUri(File, SupportedFileExtension), new Exception("failed"))
    val helper = new ExtractorActorTestHelper

    helper.postProcessRequest(File, SupportedFileExtension)
      .post(errResult)
    expectMsg(errResult)
  }

  it should "ignore result messages for unknown requests" in {
    val helper = new ExtractorActorTestHelper

    helper send createProcessingResult("unknownFile", SupportedFileExtension)
  }

  it should "remove completed requests from the in-progress map" in {
    val File1 = "firstFile"
    val File2 = "secondMediaFile"
    val result1 = createProcessingResult(File1, SupportedFileExtension)
    val result2 = createProcessingResult(File2, SupportedFileExtension)
    val helper = new ExtractorActorTestHelper

    helper.postProcessRequest(File1, SupportedFileExtension)
      .postProcessRequest(File2, SupportedFileExtension)
      .post(result1)
      .post(result1)
      .post(result2)
    expectMsg(result1)
    expectMsg(result2)
  }

  it should "not add unsupported requests to the in-progress map" in {
    val helper = new ExtractorActorTestHelper
    val File = "media"
    helper.postProcessRequest(File, UnsupportedFileExtension)
    expectMsgType[MetaDataProcessingSuccess]

    helper send createProcessingResult(File, UnsupportedFileExtension)
    testActor ! Ping
    expectMsg(Ping)
  }

  it should "not create multiple extractor actors for same Props" in {
    val Ext2 = SupportedFileExtension toUpperCase Locale.ENGLISH
    val File1 = "mediaFile1"
    val File2 = "otherMediaFile"
    val helper = new ExtractorActorTestHelper

    helper.addFileExtension(Ext2)
      .postProcessRequest(File1, SupportedFileExtension)
      .postProcessRequest(File2, Ext2)
      .expectDelegationToExtractor(File1, SupportedFileExtension)
      .expectDelegationToExtractor(File2, Ext2)
  }

  it should "handle a close request" in {
    val probeExtractor2 = TestProbe()
    val props = Props(classOf[MetaDataExtractorWrapperActor], mock[ExtractorActorFactory])
    val Ext2 = "ogg"
    val helper = new ExtractorActorTestHelper

    helper.expectChildCreation(props, probeExtractor2.ref)
      .addFileExtension(Ext2, Some(props))
      .postProcessRequest("AbortedSong1", SupportedFileExtension)
      .postProcessRequest("AnotherAbortedSong", Ext2)

    helper.post(CloseRequest)
      .numberOfCloseRequests should be(1)
  }

  it should "no longer accept requests after it was canceled" in {
    val helper = new ExtractorActorTestHelper

    helper.post(CloseRequest)
      .send(createProcessRequest("file", SupportedFileExtension))
      .expectNoDelegationToExtractor()
  }

  it should "process a close complete message" in {
    val helper = new ExtractorActorTestHelper

    helper.post(CloseRequest)
      .send(CloseHandlerActor.CloseComplete)
      .numberOfCloseCompletes should be(1)
  }

  /**
    * A test helper class managing a test actor and its dependencies.
    */
  private class ExtractorActorTestHelper {
    /** A test Props instance returned by the factory mock. */
    private val extractorProps = Props(classOf[MetaDataExtractorWrapperActor],
      mock[ExtractorActorFactory])

    /** A mock for the extractor factory. */
    private val extractorFactory = createFactoryMock()

    /** Test probe for the meta data extractor actor. */
    private val probeExtractor = TestProbe()

    /** A map for storing information about expected child actor creations. */
    private val childActorMapping = new ConcurrentHashMap[Props, ActorRef]

    /** Stores the child actors created by the test actor. */
    private val createdChildActors = new AtomicReference(List.empty[ActorRef])

    /** A counter for close requests handled by the actor. */
    private val closeRequestCount = new AtomicInteger

    /** A counter for close complete invocations. */
    private val closeCompleteCount = new AtomicInteger

    /** The test actor reference. */
    private val testExtractor = createTestActor()

    /**
      * Posts a message to the test actor.
      *
      * @param msg    the message
      * @param sender the sender of the message
      * @return this test helper
      */
    def post(msg: Any, sender: ActorRef = testActor): ExtractorActorTestHelper = {
      testExtractor.tell(msg, sender)
      this
    }

    /**
      * Passes the specified message directly to the test actor's ''receive''
      * function.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): ExtractorActorTestHelper = {
      testExtractor receive msg
      this
    }

    /**
      * Posts a processing request to the test actor.
      *
      * @param name the name of the test file
      * @param ext  the extension
      * @return this test helper
      */
    def postProcessRequest(name: String, ext: String): ExtractorActorTestHelper =
      post(createProcessRequest(name, ext))

    /**
      * Adds the specified file extension to the set of extensions handled by
      * the mock extractor factory. The creation ''Props'' to be returned can
      * be specified.
      *
      * @param extension the file extension
      * @param props     the ''Props'' for this extension
      * @return this test helper
      */
    def addFileExtension(extension: String, props: Option[Props] = Some(extractorProps)):
    ExtractorActorTestHelper =
      expectExtractorRequest(extractorFactory, extension, props)

    /**
      * Expects that a processing request has been delegated to an extractor
      * actor.
      *
      * @param name the test file name
      * @param ext  the extension
      * @return this test helper
      */
    def expectDelegationToExtractor(name: String, ext: String): ExtractorActorTestHelper = {
      probeExtractor.expectMsg(createProcessRequest(name, ext))
      this
    }

    /**
      * Checks that the extractor actor did not receive a message.
      *
      * @return this test helper
      */
    def expectNoDelegationToExtractor(): ExtractorActorTestHelper = {
      probeExtractor.ref ! Ping
      probeExtractor.expectMsg(Ping)
      this
    }

    /**
      * Verifies that the extractor factory has been invoked once for the given
      * file extension.
      *
      * @param ext the file extension
      * @return this test helper
      */
    def verifyExtractorFactoryRequest(ext: String): ExtractorActorTestHelper = {
      verify(extractorFactory).extractorProps(ext, testExtractor)
      this
    }

    /**
      * Expects a child creation with the provided data.
      *
      * @param props the props for the child
      * @param child the child actor
      * @return this test helper
      */
    def expectChildCreation(props: Props, child: ActorRef): ExtractorActorTestHelper = {
      childActorMapping.put(props, child)
      this
    }

    /**
      * Returns the number of close requests handled by the test actor.
      *
      * @return the number of close requests
      */
    def numberOfCloseRequests: Int = closeRequestCount.get()

    /**
      * Returns the number of close complete calls from the test actor.
      *
      * @return the number of close complete calls
      */
    def numberOfCloseCompletes: Int = closeCompleteCount.get()

    /**
      * Creates a mock for the image factory. The mock returns test Props for
      * supported file extensions.
      *
      * @return the mock factory
      */
    private def createFactoryMock(): ExtractorActorFactory = {
      val factory = mock[ExtractorActorFactory]
      expectExtractorRequest(factory, SupportedFileExtension, Some(extractorProps))
      expectExtractorRequest(factory, UnsupportedFileExtension, None)
      factory
    }

    /**
      * Prepares the specified mock extractor to expect a request for the given
      * file extension and to answer with the provided Props.
      *
      * @param factory   the mock factory
      * @param extension the file extension
      * @param props     the ''Props'' to be returned by the factory
      * @return this test helper
      */
    private def expectExtractorRequest(factory: ExtractorActorFactory, extension: String,
                                       props: Option[Props]): ExtractorActorTestHelper = {
      when(factory.extractorProps(argEq(extension), any(classOf[ActorRef])))
        .thenAnswer(new Answer[Option[Props]] {
          override def answer(invocation: InvocationOnMock): Option[Props] = {
            invocation.getArguments()(1) should be(testExtractor)
            props
          }
        })
      this
    }

    /**
      * Creates a test actor instance.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[MetaDataExtractorWrapperActor] = {
      expectChildCreation(extractorProps, probeExtractor.ref)
      TestActorRef(Props(new MetaDataExtractorWrapperActor(extractorFactory)
        with ChildActorFactory with CloseSupport {
        override def createChildActor(p: Props): ActorRef = {
          val child = childActorMapping remove p
          p should not be null
          createdChildActors.set(child :: createdChildActors.get())
          child
        }

        override def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef], target:
        ActorRef, factory: ChildActorFactory, conditionState: => Boolean): Boolean = {
          subject should be(testExtractor)
          target should be(sender())
          factory should be(this)
          conditionState should be(true)
          deps should contain theSameElementsAs createdChildActors.get()
          closeRequestCount.incrementAndGet() < 2
        }

        /**
          * Notifies this object that the close operation is complete.
          */
        override def onCloseComplete(): Unit = closeCompleteCount.incrementAndGet()
      }))
    }
  }

}
