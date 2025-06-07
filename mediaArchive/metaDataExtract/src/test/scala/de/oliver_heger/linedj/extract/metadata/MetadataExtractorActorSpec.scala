/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.io.*
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingResult}
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.duration.*

object MetadataExtractorActorSpec:
  /**
    * Generates a processing request for a number of test files.
    *
    * @param fileNames the names of the test files
    * @return the request to process these test files
    */
  private def createProcessRequest(fileNames: List[String]): ProcessMediaFiles =
    val files = fileNames.map { name =>
      FileData(ExtractorTestHelper.toPath(name), name.length)
    }
    ProcessMediaFiles(ExtractorTestHelper.TestMediumID, files, ExtractorTestHelper.uriForPath)
end MetadataExtractorActorSpec

/**
  * Test class for ''MetaDataExtractionActor''.
  */
class MetadataExtractorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import ExtractorTestHelper.*
  import MetadataExtractorActorSpec.*

  def this() = this(ActorSystem("MetaDataExtractorActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Creates an instance of the test actor.
    *
    * @param manager the metadata manager actor
    * @param timeout the timeout when calling extractor actors
    * @return the test actor reference
    */
  private def createExtractorActor(manager: ActorRef = testActor,
                                   timeout: FiniteDuration = 1.minute): ActorRef =
    val extractorProvider = createExtractorFunctionProvider()
    val props = MetadataExtractorActor(manager, extractorProvider, timeout)
    system.actorOf(props)

  /**
    * Obtains a number of [[MetadataProcessingResult]] objects from the test
    * actor. All results received are returned in a list (in reverse order)
    *
    * @param count the number of result objects to retrieve
    * @return the list with result messages received (in reverse order)
    */
  private def fetchProcessingResults(count: Int): List[MetadataProcessingResult] =
    (1 to count).foldLeft(List.empty[MetadataProcessingResult])((lst, _) =>
      expectMsgType[MetadataProcessingResult] :: lst)

  /**
    * Expects that (success) processing results are generated for the given
    * list of test files.
    *
    * @param fileNames the names of the test files
    */
  private def expectProcessingResults(fileNames: List[String]): Unit =
    val expectedResults = fileNames.map(successResultFor)
    val results = fetchProcessingResults(fileNames.length)
    results should contain theSameElementsAs expectedResults

  "A MetadataExtractionActor" should "process a number of media files" in :
    val fileNames = List("audioFile1.mp3", "foo.mp3", "bar.mp3")
    val actor = createExtractorActor()

    actor ! createProcessRequest(fileNames)

    expectProcessingResults(fileNames)

  it should "handle a timeout when processing a stream" in :
    val timeoutFile = delayedFile(500)
    val fileNames = List("audioFile1.mp3", timeoutFile, "audioFile2.mp3")
    val actor = createExtractorActor(timeout = 100.millis)

    actor ! createProcessRequest(fileNames)

    val results = fetchProcessingResults(fileNames.length)
    val errorResults = results.filter(_.isInstanceOf[MetadataProcessingError])
    errorResults should have size 1
    errorResults.head.uri should be(uriForName(timeoutFile))

  it should "handle multiple requests one after the other" in :
    val fileNames1 = List("file1_1.mp3", "file1_2.mp3")
    val fileNames2 = List("file2_1.mp3", "file2_2.mp3", "file2_3.mp3")
    val actor = createExtractorActor()

    actor ! createProcessRequest(fileNames1)
    actor ! createProcessRequest(fileNames2)

    expectProcessingResults(fileNames1)
    expectProcessingResults(fileNames2)

  it should "support cancellation of stream processing" in :
    val resultQueue = new LinkedBlockingQueue[MetadataProcessingResult]
    val managerActor = system.actorOf(Props(new Actor:
      override def receive: Receive =
        case result: MetadataProcessingResult =>
          resultQueue.offer(result)
    ))

    val Count = 100
    val fileNames = (1 to Count).map(idx => delayedFile(50 + idx)).toList
    val actor = createExtractorActor(manager = managerActor)
    actor ! createProcessRequest(fileNames)

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    resultQueue.size() should be < Count

  it should "no longer accept requests after a close request" in :
    val receiver = TestProbe()
    val actor = createExtractorActor(manager = receiver.ref)

    actor ! CloseRequest
    actor ! createProcessRequest(List("afterClose.mp3"))

    expectMsg(CloseAck(actor))
    receiver.expectNoMessage(500.millis)
