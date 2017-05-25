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

package de.oliver_heger.linedj.archivecommon.stream

import akka.actor.{ActorSystem, Props}
import akka.stream._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Test class for ''AbstractStreamProcessingActor''.
  */
class AbstractStreamProcessingActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("AbstractStreamProcessingActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An AbstractStreamProcessingActor" should "execute a stream" in {
    val source = Source(1 to 10)
    val actor = system.actorOf(Props(classOf[StreamProcessingActorImpl], source))

    actor ! ProcessStream
    expectMsg(ProcessingResult(55))
  }

  it should "handle a failed stream correctly" in {
    val source = Source.failed[Int](new Exception("Boom"))
    val actor = system.actorOf(Props(classOf[StreamProcessingActorImpl], source))

    actor ! ProcessStream
    expectMsg(ProcessingResult(-1))
  }

  it should "support stream cancellation" in {
    val source = Source(1 to 10).delay(500.milliseconds, DelayOverflowStrategy.backpressure)
    val actor = system.actorOf(Props(classOf[StreamProcessingActorImpl], source))

    actor ! ProcessStream
    actor ! AbstractStreamProcessingActor.CancelStreams
    val result = expectMsgType[ProcessingResult]
    result.sum should be < 55
  }

  it should "remove registrations for kill switches" in {
    val killSwitch = mock[KillSwitch]
    val source = Source.single(42)
    val actor = TestActorRef[StreamProcessingActorImpl](
      Props(new StreamProcessingActorImpl(source) {
        override protected def materializeStream(): (KillSwitch, Future[Int]) = {
          val (ks, futRes) = super.materializeStream()
          (killSwitch, futRes)
        }
      }))
    actor ! ProcessStream
    expectMsg(ProcessingResult(42))

    actor receive AbstractStreamProcessingActor.CancelStreams
    verify(killSwitch, never()).shutdown()
  }
}

/**
  * A message telling the test actor to start stream processing.
  */
case object ProcessStream

/**
  * The result message produced by the test actor. It contains the sum of the
  * processed integer stream.
  *
  * @param sum the sum
  */
case class ProcessingResult(sum: Int)

/**
  * A test actor implementation that simulates stream processing.
  *
  * @param src the source to be processed
  */
class StreamProcessingActorImpl(src: Source[Int, Any]) extends AbstractStreamProcessingActor
  with CancelableStreamSupport {
  /**
    * The custom receive function. Here derived classes can provide their own
    * message handling.
    *
    * @return the custom receive method
    */
  override protected def customReceive: Receive = {
    case ProcessStream =>
      runStream()
  }

  /**
    * Materializes the stream returning the future result and a kill switch.
    *
    * @return the stream result and a kill switch
    */
  protected def materializeStream(): (KillSwitch, Future[Int]) = {
    val sink = Sink.fold[Int, Int](0)(_ + _)
    src.viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink)(Keep.both)
      .run()
  }

  /**
    * Executes the stream.
    */
  private def runStream(): Unit = {
    val (ks, futRes) = materializeStream()
    processStreamResult(futRes map ProcessingResult, ks) { f =>
      val res = if (f.exception.getMessage == "Boom") -1 else 42
      ProcessingResult(res)
    }
  }
}
