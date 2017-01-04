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

package de.oliver_heger.linedj.io

import akka.actor.ActorSystem
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.FileWriterActor.WriteResult
import org.scalatest.{Matchers, BeforeAndAfterAll, FlatSpecLike}
import scala.concurrent.duration._

object CopyStateSpec {
  /** Constant for the read chunk size. */
  private val ReadChunkSize = 32768

  /** The read request expected in all state objects. */
  private val ReadRequest = FileReaderActor.ReadData(ReadChunkSize)

  /**
   * Creates a read result to be used in tests.
   * @return the read result
   */
  private def createReadResult(): FileReaderActor.ReadResult =
    FileReaderActor.ReadResult(FileTestHelper.testBytes(), FileTestHelper.TestData.length)

  /**
   * Creates a write result to be used in tests.
   * @return the write result
   */
  private def createWriteResult(): WriteResult =
    FileWriterActor.WriteResult(FileWriterActor.WriteResultStatus.Ok, ReadChunkSize)
}

/**
 * Test class for ''CopyState''.
 */
class CopyStateSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender with
FlatSpecLike with BeforeAndAfterAll with Matchers {

  import CopyStateSpec._

  def this() = this(ActorSystem("CopyStateSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Checks that the specified message is ignored; this means that the returned
   * transition is a dummy.
   * @param state the state
   * @param msg the message
   */
  private def checkIgnoreMessage(state: CopyState, msg: Any): Unit = {
    val transition = state update msg
    transition.nextState should be theSameInstanceAs state
    transition.readerMessage shouldBe 'empty
    transition.writerMessage shouldBe 'empty
  }

  "A CopyState" should "allow the creation of an initial transition" in {
    val transition = CopyState.init(ReadChunkSize)
    val state = transition.nextState

    state.readPending shouldBe true
    state.writePending shouldBe false
    state.readDone shouldBe false
    state.writeDone shouldBe false
    state.pendingResult shouldBe 'empty
    state.readRequest should be(FileReaderActor.ReadData(ReadChunkSize))
    transition.readerMessage.get should be(state.readRequest)
    transition.writerMessage shouldBe 'empty
  }

  it should "invoke actors with the messages in a transition" in {
    val probeReader, probeWriter = TestProbe()
    val transition = CopyState.init(ReadChunkSize).copy(writerMessage = Some("A writer message"))

    transition.sendMessages(reader = probeReader.ref, writer = probeWriter.ref)
    probeReader.expectMsg(transition.readerMessage.get)
    probeWriter.expectMsg(transition.writerMessage.get)
  }

  it should "deal with undefined messages in a transition" in {
    val probe = TestProbe()
    val transition = CopyState.init(ReadChunkSize).copy(readerMessage = None)

    transition.sendMessages(probe.ref, probe.ref)
    // check that no messages are received
    val testMsg = "Ping"
    probe.ref ! testMsg
    probe.expectMsg(testMsg)
  }

  it should "handle a read result if the writer is currently idle" in {
    val state = CopyState.init(ReadChunkSize).nextState
    val msg = createReadResult()

    val transition = state update msg
    transition.nextState should be(CopyState(readDone = false, writeDone = false,
      readPending = true, writePending = true, pendingResult = None, readRequest = ReadRequest))
    transition.readerMessage.get should be(ReadRequest)
    transition.writerMessage.get should be(msg)
  }

  it should "handle a read result if the writer is currently busy" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(writePending = true)
    val msg = createReadResult()

    val transition = state update msg
    transition.nextState should be(CopyState(readDone = false, writeDone = false,
      readPending = false, writePending = true, pendingResult = Some(msg), readRequest =
        ReadRequest))
    transition.readerMessage shouldBe 'empty
    transition.writerMessage shouldBe 'empty
  }

  it should "ignore a read result if no read is pending" in {
    checkIgnoreMessage(CopyState.init(ReadChunkSize).nextState.copy(readPending = false),
      createReadResult())
  }

  it should "handle an EoF message if the writer is currently idle" in {
    val state = CopyState.init(ReadChunkSize).nextState

    val transition = state update FileReaderActor.EndOfFile(null)
    transition.nextState should be(CopyState(readDone = true, writeDone = true,
      readPending = false, writePending = true, pendingResult = None, readRequest = ReadRequest))
    transition.readerMessage shouldBe 'empty
    transition.writerMessage shouldBe Some(CloseRequest)
  }

  it should "handle an EoF message if the writer is currently busy" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(writePending = true)

    val transition = state update FileReaderActor.EndOfFile(null)
    transition.nextState should be(CopyState(readDone = true, writeDone = false,
      readPending = false, writePending = true, pendingResult = None, readRequest = ReadRequest))
    transition.readerMessage shouldBe 'empty
    transition.writerMessage shouldBe 'empty
  }

  it should "ignore a read result if the reader is already done" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(readDone = true)
    checkIgnoreMessage(state, createReadResult())
  }

  it should "ignore an EoF message if no read is pending" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(readPending = false)
    checkIgnoreMessage(state, FileReaderActor.EndOfFile(null))
  }

  it should "ignore an EoF message if the reader is already done" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(readDone = true)
    checkIgnoreMessage(state, FileReaderActor.EndOfFile(null))
  }

  it should "handle a write result if the reader is currently busy" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(writePending = true)

    val transition = state update createWriteResult()
    transition.nextState should be(CopyState(readDone = false, writeDone = false,
      readPending = true, writePending = false, pendingResult = None, readRequest = ReadRequest))
    transition.readerMessage shouldBe 'empty
    transition.writerMessage shouldBe 'empty
  }

  it should "handle a write result if the reader is currently idle" in {
    val result = createReadResult()
    val state = CopyState.init(ReadChunkSize).nextState.copy(writePending = true,
      readPending = false, pendingResult = Some(result))

    val transition = state update createWriteResult()
    transition.nextState should be(CopyState(readDone = false, writeDone = false,
      readPending = true, writePending = true, pendingResult = None, readRequest = ReadRequest))
    transition.readerMessage.get shouldBe ReadRequest
    transition.writerMessage.get shouldBe result
  }

  it should "ignore a write result if no write is pending" in {
    checkIgnoreMessage(CopyState.init(ReadChunkSize).nextState, createWriteResult())
  }

  it should "handle a write result if the reader is already done" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(writePending = true, readDone = true,
      readPending = false)

    val transition = state update createWriteResult()
    transition.nextState should be(CopyState(readDone = true, writeDone = true,
      readPending = false, writePending = true, pendingResult = None, readRequest = ReadRequest))
    transition.readerMessage shouldBe 'empty
    transition.writerMessage shouldBe Some(CloseRequest)
  }

  it should "ignore a write result if writing is already done" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(writeDone = true)
    checkIgnoreMessage(state, createWriteResult())
  }

  it should "handle a close ack message if writing is done" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(writePending = true, readDone = true,
      writeDone = true, readPending = false)

    val transition = state update CloseAck(null)
    transition.nextState should be(CopyState(readDone = true, writeDone = true,
      readPending = false, writePending = false, pendingResult = None, readRequest = ReadRequest))
    transition.readerMessage shouldBe 'empty
    transition.writerMessage shouldBe 'empty
  }

  it should "ignore a close ack message if writing is not done" in {
    checkIgnoreMessage(CopyState.init(ReadChunkSize).nextState, CloseAck(null))
  }

  it should "ignore a close ack message if no write is pending" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(writeDone = true)
    checkIgnoreMessage(state, CloseAck(null))
  }

  it should "not be done in its initial state" in {
    val state = CopyState.init(ReadChunkSize).nextState

    state.done shouldBe false
  }

  it should "not be done before the writer is closed" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(readPending = false, readDone =
      true, writeDone = true, writePending = true)

    state should not be 'done
  }

  it should "be done if the final state is reached" in {
    val state = CopyState.init(ReadChunkSize).nextState.copy(readPending = false, readDone =
      true, writeDone = true)

    state shouldBe 'done
  }
}
