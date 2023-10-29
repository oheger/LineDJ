/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.download

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.shared.archive.media.{DownloadData, DownloadDataResult}
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._

object TempFileActorManagerSpec {
  /** Test chunk size for download data requests. */
  private val DownloadChunkSize = 8192

  /** A timeout value used within tests. */
  private val TestTimeout = 3.seconds

  /** A default request for download data. */
  private val DefaultRequest = DownloadData(DownloadChunkSize)

  /**
    * Generates a path for a download file.
    *
    * @param idx the index of the file
    * @return the generated path
    */
  private def createTempPath(idx: Int): Path = Paths get s"downloadPath$idx.tmp"

  /**
    * Generates a response object for a write operation.
    *
    * @param idx the index of the download file affected
    * @return the response object
    */
  private def createWriteResponse(idx: Int): WriteChunkActor.WriteResponse =
    WriteChunkActor.WriteResponse(WriteChunkActor.WriteRequest(createTempPath(idx),
      null, idx))

  /**
    * Generates a download data result object.
    *
    * @param data the data for the result
    * @return the result object
    */
  private def createDownloadResult(data: String = FileTestHelper.TestData): DownloadDataResult =
    DownloadDataResult(ByteString(data))
}

/**
  * Test class for ''TempFileActorManager''.
  */
class TempFileActorManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("TempFileActorManagerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import TempFileActorManagerSpec._

  "A TempFileActorManager" should "not handle a request if there is no data" in {
    val helper = new TempFileManagerTestHelper

    helper.initiateClientRequest(expectedResult = false)
  }

  it should "handle a request if a write operation is pending" in {
    val helper = new TempFileManagerTestHelper

    helper.pendingWriteOperation(1)
      .initiateClientRequest(expectedResult = true)
  }

  it should "create a child actor to handle a request" in {
    val helper = new TempFileManagerTestHelper

    helper.pendingWriteOperation(1)
      .tempFileWritten(1)
      .initiateClientRequest(expectedResult = true)
    val creationData = helper.nextChildActor()
    creationData.path should be(createTempPath(1))
    helper.expectReadActorRequest(
      creationData.actorIdx) should be(DownloadData(DownloadChunkSize))
  }

  it should "send a read result to the client actor" in {
    val helper = new TempFileManagerTestHelper
    helper.pendingWriteOperation(1)
      .tempFileWritten(1)
      .initiateClientRequest(expectedResult = true)
    val result = createDownloadResult()

    helper.passDownloadResult(result)
      .expectClientMsg(result)
  }

  it should "return None for an unexpected download completed message" in {
    val helper = new TempFileManagerTestHelper

    helper.passDownloadCompleted() should be(None)
    helper.verifyNoActorCreation()
  }

  it should "handle a download completed message if there is no more data" in {
    val helper = new TempFileManagerTestHelper
    helper.pendingWriteOperation(1)
      .tempFileWritten(1)
      .initiateClientRequest(expectedResult = true)

    helper.passAndCheckDownloadCompleted(canHandle = false)
      .initiateClientRequest(expectedResult = false)
  }

  it should "ignore an unexpected download result message" in {
    val helper = new TempFileManagerTestHelper

    helper.passDownloadResult(createDownloadResult())
  }

  it should "not reset the reader actor for an unexpected download completed message" in {
    val helper = new TempFileManagerTestHelper
    helper.pendingWriteOperation(1).tempFileWritten(1)
      .pendingWriteOperation(2).tempFileWritten(2)
      .initiateClientRequest(expectedResult = true).nextChildActor()

    helper.passDownloadResult(createDownloadResult("First result"))
      .passDownloadCompleted()
    helper.initiateClientRequest(expectedResult = true)
      .verifyNoActorCreation()
  }

  it should "manage multiple files" in {
    val helper = new TempFileManagerTestHelper
    helper.pendingWriteOperation(1).tempFileWritten(1)
    val result1 = createDownloadResult()
    val result2 = createDownloadResult("more data")

    val data1 = helper.pendingWriteOperation(2).tempFileWritten(2)
      .initiateClientRequest(expectedResult = true).nextChildActor()
    helper.expectReadActorRequest(data1.actorIdx)
    helper.passDownloadResult(result1).expectClientMsg(result1)
      .initiateClientRequest(expectedResult = true)
      .expectReadActorRequest(data1.actorIdx)
    helper.passDownloadCompleted().get.pendingRequest should be(None)

    val data2 = helper.nextChildActor()
    helper.expectReadActorRequest(data2.actorIdx)
    helper.passDownloadResult(result2).expectClientMsg(result2)
  }

  it should "ignore a request if one is already in progress" in {
    val helper = new TempFileManagerTestHelper
    val creationData = helper.pendingWriteOperation(1).tempFileWritten(1)
      .initiateClientRequest(expectedResult = true).nextChildActor()
    helper.expectReadActorRequest(creationData.actorIdx)

    helper.initiateClientRequest(expectedResult = true)
    helper.expectNoReadActorRequest()
  }

  it should "send an initiated request when the temporary file becomes available" in {
    val helper = new TempFileManagerTestHelper

    val creationData = helper.pendingWriteOperation(1)
      .initiateClientRequest(expectedResult = true)
      .tempFileWritten(1)
      .nextChildActor()
    helper.expectReadActorRequest(creationData.actorIdx) should be(DownloadData(DownloadChunkSize))
  }

  it should "not create a reader actor for a later temporary file" in {
    val helper = new TempFileManagerTestHelper

    helper.pendingWriteOperation(1)
      .initiateClientRequest(expectedResult = true)
      .pendingWriteOperation(2)
      .tempFileWritten(2)
      .verifyNoActorCreation()
  }

  it should "use the correct order for temporary files" in {
    val helper = new TempFileManagerTestHelper

    helper.pendingWriteOperation(1).pendingWriteOperation(2).pendingWriteOperation(3)
      .initiateClientRequest(expectedResult = true)
      .tempFileWritten(2).tempFileWritten(3).tempFileWritten(1)
    val creationData = helper.nextChildActor()
    creationData.path should be(createTempPath(1))
  }

  it should "not return temp paths before files have been created" in {
    val helper = new TempFileManagerTestHelper

    helper.tempPaths.isEmpty shouldBe true
  }

  it should "return pending temp paths" in {
    val expPaths = List(createTempPath(1), createTempPath(2))
    val helper = new TempFileManagerTestHelper
    helper.pendingWriteOperation(1).pendingWriteOperation(2)
      .tempFileWritten(1).tempFileWritten(2)

    helper.tempPaths should contain theSameElementsAs expPaths
  }

  it should "include a path as pending that is currently read" in {
    val expPaths = List(createTempPath(1), createTempPath(2))
    val helper = new TempFileManagerTestHelper
    helper.pendingWriteOperation(1).tempFileWritten(1)
      .pendingWriteOperation(2).tempFileWritten(2)
      .initiateClientRequest(expectedResult = true)

    helper.tempPaths should contain theSameElementsAs expPaths
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class TempFileManagerTestHelper {
    /** Test probe for the owning download actor. */
    private val probeDownloadActor = TestProbe()

    /** Test probe for a client actor. */
    private val probeClient = TestProbe()

    /** A queue to store child actors whose creation is expected. */
    private val childActors = new LinkedBlockingQueue[ReadOperationCreationData]

    /** A counter to generate indices for child actors. */
    private val childActorCount = new AtomicInteger

    /**
      * Stores the current read operation for the test implementation of the
      * ''TempReadOperationHolder'' trait.
      */
    private var readOperation: Option[TempReadOperation] = None

    /** Instance to be tested. */
    private val manager = new TempFileActorManager(probeDownloadActor.ref, createReadOperationHolder())

    /**
      * Queries the pending temp paths from the test instance.
      *
      * @return the pending temp paths
      */
    def tempPaths: Iterable[Path] = manager.pendingTempPaths

    /**
      * Invokes the method to initiate a request on the test instance.
      *
      * @param expectedResult the expected result
      * @return this test helper
      */
    def initiateClientRequest(expectedResult: Boolean): TempFileManagerTestHelper = {
      manager.initiateClientRequest(probeClient.ref,
        DefaultRequest) shouldBe expectedResult
      this
    }

    /**
      * Notifies the test instance about a pending write operation.
      *
      * @param seqNo the index number of the download file
      * @return this test helper
      */
    def pendingWriteOperation(seqNo: Int): TempFileManagerTestHelper = {
      manager pendingWriteOperation seqNo
      this
    }

    /**
      * Returns information about the next child actor that has been
      * created by the test instance.
      *
      * @return data about a child actor
      */
    def nextChildActor(): ReadOperationCreationData = {
      val data = childActors.poll(TestTimeout.toSeconds, TimeUnit.SECONDS)
      data should not be null
      data
    }

    /**
      * Checks that no child actor has been created.
      *
      * @return this test helper
      */
    def verifyNoActorCreation(): TempFileManagerTestHelper = {
      childActors shouldBe empty
      this
    }

    /**
      * Notifies the test instance that a temporary file has been written.
      *
      * @param seqNo the index number of the download file
      * @return this test helper
      */
    def tempFileWritten(seqNo: Int): TempFileManagerTestHelper = {
      manager tempFileWritten createWriteResponse(seqNo)
      this
    }

    /**
      * Passes the specified download result to the test instance.
      *
      * @param result the result object
      * @return this test helper
      */
    def passDownloadResult(result: DownloadDataResult): TempFileManagerTestHelper = {
      manager downloadResultArrived result
      this
    }

    /**
      * Passes a download completed message to the test instance and returns
      * the result.
      *
      * @return the result returned by the test instance
      */
    def passDownloadCompleted(): Option[CompletedTempReadOperation] =
      manager.downloadCompletedArrived()

    /**
      * Passes a download completed notification to the test instance and
      * checks the return value.
      *
      * @param canHandle flag whether the test instance can handle the request
      * @param pathIdx   the index of the path that was read
      * @return this test helper
      */
    def passAndCheckDownloadCompleted(canHandle: Boolean, pathIdx: Int = 1):
    TempFileManagerTestHelper = {
      val result = if (canHandle) None
      else Some(DownloadRequestData(DefaultRequest, probeClient.ref))
      val opData = manager.downloadCompletedArrived().get
      opData.pendingRequest shouldBe result
      opData.operation.path should be(createTempPath(pathIdx))
      opData.operation.reader should be(nextChildActor().actor)
      this
    }

    /**
      * Expects that a message has been sent to one of the child reader actors
      * and returns it.
      *
      * @param actorIdx the index of the child actor
      * @return the message received
      */
    def expectReadActorRequest(actorIdx: Int): Any = {
      val msg = probeDownloadActor.expectMsgType[FileReadMsg]
      msg.actorIdx should be(actorIdx)
      msg.msg
    }

    /**
      * Expects that the specified message was sent to the client actor.
      *
      * @param msg the message
      * @return this test helper
      */
    def expectClientMsg(msg: Any): TempFileManagerTestHelper = {
      probeClient.expectMsg(msg)
      this
    }

    /**
      * Checks that no message was sent to a reader actor.
      *
      * @return this test helper
      */
    def expectNoReadActorRequest(): TempFileManagerTestHelper =
      expectNoActorMsg(probeDownloadActor)

    /**
      * Checks that the specified actor does not receive any more messages.
      *
      * @param actor the actor in question
      * @return this test helper
      */
    private def expectNoActorMsg(actor: TestProbe): TempFileManagerTestHelper = {
      val msg = new Object
      actor.ref ! msg
      actor.expectMsg(msg)
      this
    }

    /**
      * Creates a dummy ''TempReadOperationHolder'' instance to be passed to
      * the test actor. The holder returns a special stub reader actor and
      * records the actor creation.
      *
      * @return the test ''TempReadOperationHolder''
      */
    private def createReadOperationHolder(): TempReadOperationHolder =
      new TempReadOperationHolder {
        override def currentReadOperation: Option[TempReadOperation] = readOperation

        override def getOrCreateCurrentReadOperation(optPath: => Option[Path]): Option[TempReadOperation] = {
          readOperation orElse {
            optPath map { path =>
              val actorIdx = childActorCount.incrementAndGet()
              val actor = system.actorOf(Props(classOf[SimulatedFileReaderActor], actorIdx))
              val creationData = ReadOperationCreationData(actorIdx, actor, path)
              childActors offer creationData
              val op = TempReadOperation(actor, path)
              readOperation = Some(op)
              op
            }
          }
        }

        override def resetReadOperation(): Unit = {
          readOperation = None
        }
      }
  }
}

/**
  * A simple data class storing information about an actor that was created on
  * behalf of the test instance.
  *
  * @param actorIdx the index of the test actor
  * @param actor    the reference to the new child actor
  * @param path     the path to the file to read
  */
private case class ReadOperationCreationData(actorIdx: Int, actor: ActorRef,
                                             path: Path)

/**
  * A message class processed by the actors simulating file reader actors.
  *
  * @param msg      the original message
  * @param actorIdx the index of the file reader actor
  */
private case class FileReadMsg(msg: Any, actorIdx: Int)

/**
  * An actor class that simulates a file reader actor. This class is used to
  * simulate message forwarding: Messages sent to such an actor should have
  * the download actor as sender. Therefore, a simple test probe cannot be
  * used.
  *
  * @param idx an index to identify the actor
  */
class SimulatedFileReaderActor(idx: Int) extends Actor {
  override def receive: Receive = {
    case m => sender() ! FileReadMsg(m, idx)
  }
}
