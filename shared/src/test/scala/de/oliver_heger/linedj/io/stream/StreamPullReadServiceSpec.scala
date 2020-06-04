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

package de.oliver_heger.linedj.io.stream

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object StreamPullReadServiceSpec {
  /** Constant for the request size for data requests. */
  private val DataRequestSize = FileTestHelper.TestData.length

  /** Constant for a test data chunk. */
  private val TestString = ByteString(FileTestHelper.TestData)

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: StreamPullReadServiceImpl.StateUpdate[A],
                             oldState: StreamPullState = StreamPullReadServiceImpl.InitialState):
  (StreamPullState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: StreamPullReadServiceImpl.StateUpdate[Unit],
                          oldState: StreamPullState = StreamPullReadServiceImpl.InitialState): StreamPullState = {
    val (next, _) = updateState(s, oldState)
    next
  }
}

/**
  * Test class for ''StreamPullReadService'' and related classes.
  */
class StreamPullReadServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("StreamPullReadServiceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import StreamPullReadServiceSpec._

  "StreamPullNotifications" should "handle an undefined receiver of data" in {
    val notifications = StreamPullNotifications(dataReceiver = None, data = ByteString("foo"), ack = None,
      error = None)

    notifications.sendData(_.utf8String)("")
  }

  it should "send a portion of data to the receiver" in {
    val Data = "MyTestData"
    val receiver = TestProbe()
    val notifications = StreamPullNotifications(dataReceiver = Some(receiver.ref),
      data = ByteString(Data), ack = None, error = None)

    notifications.sendData(_.utf8String)(throw new UnsupportedOperationException("Unexpected evaluation"))
    receiver.expectMsg(Data)
  }

  it should "send an end-of-stream message to the receiver if appropriate" in {
    val EndOfStream = "End reached"
    val receiver = TestProbe()
    val notifications = StreamPullNotifications(dataReceiver = Some(receiver.ref),
      data = ByteString.empty, ack = None, error = None)

    notifications.sendData(_ => throw new UnsupportedOperationException("Unexpected evaluation"))(EndOfStream)
    receiver.expectMsg(EndOfStream)
  }

  it should "handle an undefined ACK receiver" in {
    val notifications = StreamPullNotifications(ack = None, data = ByteString.empty, dataReceiver = None, error = None)

    notifications.sendAck(throw new UnsupportedOperationException("Unexpected evaluation"))
  }

  it should "send an ACK message to the corresponding receiver" in {
    val AckMsg = "ACK"
    val ackActor = TestProbe()
    val notifications = StreamPullNotifications(ack = Some(ackActor.ref), dataReceiver = None, data = ByteString.empty,
      error = None)

    notifications.sendAck(AckMsg)
    ackActor.expectMsg(AckMsg)
  }

  it should "handle an undefined error actor" in {
    val notifications = StreamPullNotifications(error = None, ack = None, data = ByteString.empty, dataReceiver = None)

    notifications.sendError(throw new UnsupportedOperationException("Unexpected evaluation"))
  }

  it should "send an error message to the corresponding receiver" in {
    val ErrMsg = "ERROR!"
    val errActor = TestProbe()
    val notifications = StreamPullNotifications(error = Some(errActor.ref), ack = None, dataReceiver = None,
      data = ByteString.empty)

    notifications.sendError(ErrMsg)
    errActor.expectMsg(ErrMsg)
  }

  "StreamPullReadServiceImpl" should "define a constant for the initial state" in {
    StreamPullReadServiceImpl.InitialState.requestClient should be(None)
    StreamPullReadServiceImpl.InitialState.dataAvailable should be(None)
    StreamPullReadServiceImpl.InitialState.requestClient should be(None)
    StreamPullReadServiceImpl.InitialState.ackPending should be(None)
    StreamPullReadServiceImpl.InitialState.errorClient should be(None)
    StreamPullReadServiceImpl.InitialState.streamComplete shouldBe false
  }

  it should "update the state for a data request if no data is available" in {
    val ExpState = StreamPullReadServiceImpl.InitialState
      .copy(requestClient = Some(testActor), requestSize = DataRequestSize)

    val update = StreamPullReadServiceImpl.dataRequested(testActor, DataRequestSize)
    val nextState = modifyState(update)
    nextState should be(ExpState)
  }

  it should "update the state for a data request if data is available" in {
    val state = StreamPullReadServiceImpl.InitialState.copy(dataAvailable = Some(TestString))
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      dataToSend = Some(TestString))

    val update = StreamPullReadServiceImpl.dataRequested(testActor, DataRequestSize)
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "update the state for a data request if too much data is available" in {
    val AdditionalData = "More data that is available"
    val ackActor = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(ackPending = Some(ackActor),
      dataAvailable = Some(ByteString(FileTestHelper.TestData + AdditionalData)))
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      dataToSend = Some(TestString), dataAvailable = Some(ByteString(AdditionalData)),
      ackPending = Some(ackActor))

    val update = StreamPullReadServiceImpl.dataRequested(testActor, DataRequestSize)
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "update the state for a data request if too much data is available and the stream is complete" in {
    val AdditionalData = "The final chunk of data in the stream"
    val state = StreamPullReadServiceImpl.InitialState.copy(streamComplete = true,
      dataAvailable = Some(ByteString(FileTestHelper.TestData + AdditionalData)))
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      dataToSend = Some(TestString), dataAvailable = Some(ByteString(AdditionalData)),
      streamComplete = true)

    val update = StreamPullReadServiceImpl.dataRequested(testActor, DataRequestSize)
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "update the state for a data request if the stream is fully read" in {
    val state = StreamPullReadServiceImpl.InitialState.copy(streamComplete = true)
    val ExpState = state.copy(dataToSend = Some(ByteString.empty), requestClient = Some(testActor),
      streamComplete = true)

    val update = StreamPullReadServiceImpl.dataRequested(testActor, DataRequestSize)
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "update the state for incoming data if no request is pending" in {
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(ackPending = Some(testActor),
      dataAvailable = Some(TestString))

    val update = StreamPullReadServiceImpl.nextData(TestString, testActor)
    val nextState = modifyState(update)
    nextState should be(ExpState)
  }

  it should "update the state for incoming data if a request is pending" in {
    val AdditionalData = "+"
    val dataSender = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      requestSize = DataRequestSize)
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      dataToSend = Some(TestString), dataAvailable = Some(ByteString(AdditionalData)),
      ackPending = Some(dataSender))

    val update = StreamPullReadServiceImpl.nextData(ByteString(FileTestHelper.TestData + AdditionalData), dataSender)
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "update the state for an end-of-stream message if no request is pending" in {
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(streamComplete = true)

    val update = StreamPullReadServiceImpl.endOfStream()
    val nextState = modifyState(update)
    nextState should be(ExpState)
  }

  it should "update the state for an end-of-stream message if a request is pending" in {
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      requestSize = DataRequestSize)
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(dataToSend = Some(ByteString.empty),
      requestClient = Some(testActor), streamComplete = true)

    val update = StreamPullReadServiceImpl.endOfStream()
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "update the state for an end-of-stream message if data is still available" in {
    val state = StreamPullReadServiceImpl.InitialState.copy(dataAvailable = Some(TestString))
    val ExpState = state.copy(streamComplete = true)

    val update = StreamPullReadServiceImpl.endOfStream()
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "calculate notifications if no messages are to be sent" in {
    val ExpNotifications = StreamPullNotifications(None, ByteString.empty, None, None)

    val update = StreamPullReadServiceImpl.evalNotifications()
    val (nextState, notifications) = updateState(update)
    nextState should be(StreamPullReadServiceImpl.InitialState)
    notifications should be(ExpNotifications)
  }

  it should "calculate notifications if a message and an ACK can be sent" in {
    val ackActor = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      ackPending = Some(ackActor), dataToSend = Some(TestString))
    val ExpNotifications = StreamPullNotifications(dataReceiver = Some(testActor), data = TestString,
      ack = Some(ackActor), error = None)

    val update = StreamPullReadServiceImpl.evalNotifications()
    val (nextState, notifications) = updateState(update, state)
    nextState should be(StreamPullReadServiceImpl.InitialState)
    notifications should be(ExpNotifications)
  }

  it should "calculate notifications if a message, but no ACK can be sent" in {
    val RemainingData = Some(ByteString("remaining"))
    val ackActor = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      ackPending = Some(ackActor), dataToSend = Some(TestString), dataAvailable = RemainingData)
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(dataAvailable = RemainingData,
      ackPending = Some(ackActor))
    val ExpNotifications = StreamPullNotifications(dataReceiver = Some(testActor), data = TestString,
      ack = None, error = None)

    val update = StreamPullReadServiceImpl.evalNotifications()
    val (nextState, notifications) = updateState(update, state)
    nextState should be(ExpState)
    notifications should be(ExpNotifications)
  }

  it should "detect illegal concurrent requests" in {
    val client = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(client),
      requestSize = DataRequestSize)
    val ExpState = state.copy(errorClient = Some(testActor))

    val update = StreamPullReadServiceImpl.dataRequested(testActor, DataRequestSize - 1)
    val nextState = modifyState(update, state)
    nextState should be(ExpState)
  }

  it should "add an illegal client to the notifications if no other message is sent" in {
    val requestClient = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(requestClient),
      requestSize = 42, errorClient = Some(testActor))
    val ExpState = state.copy(errorClient = None)
    val ExpNotifications = StreamPullNotifications(dataReceiver = None, data = ByteString.empty,
      ack = None, error = Some(testActor))

    val update = StreamPullReadServiceImpl.evalNotifications()
    val (nextState, notifications) = updateState(update, state)
    nextState should be(ExpState)
    notifications should be(ExpNotifications)
  }

  it should "add an illegal client to the notifications if a message can be sent" in {
    val ackActor = TestProbe().ref
    val errActor = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      ackPending = Some(ackActor), dataToSend = Some(TestString), errorClient = Some(errActor))
    val ExpNotifications = StreamPullNotifications(dataReceiver = Some(testActor), data = TestString,
      ack = Some(ackActor), error = Some(errActor))

    val update = StreamPullReadServiceImpl.evalNotifications()
    val (nextState, notifications) = updateState(update, state)
    nextState should be(StreamPullReadServiceImpl.InitialState)
    notifications should be(ExpNotifications)
  }

  it should "handle a data request" in {
    val ackActor = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(dataAvailable = Some(TestString),
      ackPending = Some(ackActor))
    val ExpNotifications = StreamPullNotifications(dataReceiver = Some(testActor), data = TestString,
      ack = Some(ackActor), error = None)

    val update = StreamPullReadServiceImpl.handleDataRequest(testActor, DataRequestSize)
    val (nextState, notifications) = updateState(update, state)
    nextState should be(StreamPullReadServiceImpl.InitialState)
    notifications should be(ExpNotifications)
  }

  it should "handle another chunk of data" in {
    val ackActor = TestProbe().ref
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      requestSize = DataRequestSize)
    val ExpNotifications = StreamPullNotifications(dataReceiver = Some(testActor), data = TestString,
      ack = Some(ackActor), error = None)

    val update = StreamPullReadServiceImpl.handleNextData(TestString, ackActor)
    val (nextState, notifications) = updateState(update, state)
    nextState should be(StreamPullReadServiceImpl.InitialState)
    notifications should be(ExpNotifications)
  }

  it should "handle an end-of-stream notification" in {
    val state = StreamPullReadServiceImpl.InitialState.copy(requestClient = Some(testActor),
      requestSize = DataRequestSize)
    val ExpState = StreamPullReadServiceImpl.InitialState.copy(streamComplete = true)
    val ExpNotifications = StreamPullNotifications(dataReceiver = Some(testActor), data = ByteString.empty,
      ack = None, error = None)

    val update = StreamPullReadServiceImpl.handleEndOfStream()
    val (nextState, notifications) = updateState(update, state)
    nextState should be(ExpState)
    notifications should be(ExpNotifications)
  }
}
