/*
 * Copyright 2015-2021 The Developers Team.
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

import java.io.IOException

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object StreamPullModeratorActorSpec {
  /** The size of a data request. */
  private val DataSize = 16

  /** A message to request a new chunk of data from the test actor. */
  case object DataRequest

  /**
    * A message to send a data chunk to the client.
    *
    * @param data the data to be sent
    */
  case class DataChunk(data: ByteString)

  /** A message indicating that all data has been read. */
  case object DataEnd

  /**
    * A message to trigger a stream read operation.
    *
    * @param pullActor the actor to be pulled to read the stream
    */
  case class ReadStream(pullActor: ActorRef)

  /**
    * A message to pass the result of a stream read operation to the test
    * method.
    *
    * @param data the data that was read from the stream
    */
  case class ReadStreamResult(data: String)

  /**
    * Generates a source that produces a stream of test data.
    *
    * @return the source producing test data
    */
  private def createTestSource(): Source[ByteString, NotUsed] =
    Source(ByteString(FileTestHelper.TestData).grouped(DataSize * 2 - 1).toList)
}

/**
  * Test class for ''StreamPullModeratorActor''.
  */
class StreamPullModeratorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("StreamPullModeratorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import StreamPullModeratorActorSpec._

  /**
    * Returns a concrete test implementation of the stream pull moderator
    * actor that processes the stream produced by the passed in source.
    *
    * @param source the stream source
    * @return the actor that allows pulling from the stream
    */
  private def createTestActor(source: Source[ByteString, NotUsed]): ActorRef =
    system.actorOf(Props(new StreamPullModeratorActor {
      override protected def createSource(): Source[ByteString, Any] = source

      override protected def customReceive: Receive = {
        case DataRequest =>
          dataRequested(DataSize)

        case Status.Failure(exception) =>
          testActor ! ReadStreamResult(exception.getMessage)
      }

      override protected def dataMessage(data: ByteString): Any = DataChunk(data)

      override protected def endOfStreamMessage: Any = DataEnd

      override protected def concurrentRequestMessage: Any = DataChunk(ByteString.empty)
    }))

  "StreamPullModeratorActor" should "support pulling data from a stream" in {
    val pullActor = createTestActor(createTestSource())
    val readerActor = system.actorOf(Props(new Actor {
      private var pullActor: ActorRef = _

      private var client: ActorRef = _

      private var data: ByteString = ByteString.empty

      override def receive: Receive = {
        case ReadStream(actor) =>
          pullActor = actor
          client = sender()
          actor ! DataRequest

        case DataChunk(chunk) =>
          sender() should be(pullActor)
          data = data ++ chunk
          pullActor ! DataRequest

        case DataEnd =>
          client ! ReadStreamResult(data.utf8String)
      }
    }))

    readerActor ! ReadStream(pullActor)
    expectMsg(ReadStreamResult(FileTestHelper.TestData))
  }

  it should "handle concurrent data requests" in {
    val firstChunk = FileTestHelper.TestData.substring(0, DataSize)
    val otherClient = TestProbe()
    val source = createTestSource().delay(500.millis)
    val pullActor = createTestActor(source)
    pullActor ! DataRequest

    otherClient.send(pullActor, DataRequest)
    otherClient.expectMsg(DataChunk(ByteString.empty))
    expectMsg(DataChunk(ByteString(firstChunk)))
  }

  it should "handle failures of the stream" in {
    val exception = new IOException("Stream exception")
    val source = Source.failed[ByteString](exception)
    createTestActor(source)

    expectMsg(ReadStreamResult(exception.getMessage))
  }
}
