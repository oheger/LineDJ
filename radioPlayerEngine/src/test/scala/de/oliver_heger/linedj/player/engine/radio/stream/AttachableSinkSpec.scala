/*
 * Copyright 2015-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.stream

import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Succeeded}

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Future

object AttachableSinkSpec:
  /**
    * Creates a [[Sink]] that collects all received elements in a (reversed)
    * list.
    *
    * @tparam T the type of data to be received
    * @return the collecting sink
    */
  private def foldSink[T](): Sink[T, Future[List[T]]] =
    Sink.fold[List[T], T](Nil) { (lst, e) =>
      e :: lst
    }
end AttachableSinkSpec

/**
  * Test class for [[AttachableSink]].
  */
class AttachableSinkSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(classic.ActorSystem("AttachableSinkSpec"))

  /** A test kit for dealing with typed actors. */
  private val typedTestkit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestkit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import AttachableSinkSpec.*

  "AttachableSink" should "ignore all data if in unattached mode" in :
    val source = Source(List(1, 2, 3, 4, 5, 6, 7, 8))
    val sink = AttachableSink[Int]("testAttachableSink")

    val controlActor = source.runWith(sink)

    val probeWatch = typedTestkit.createTestProbe()
    probeWatch.expectTerminated(controlActor)
    Succeeded

  it should "support attaching a consumer" in :
    val source = Source.queue[Int](8)
    val sink = AttachableSink[Int]("testAttachableSink")
    val (queue, controlActor) = source.toMat(sink)(Keep.both).run()
    queue.offer(1)

    AttachableSink.attachConsumer(controlActor).map { consumerSource =>
      val resultQueue = new LinkedBlockingQueue[Int]
      val resultSink = Sink.foreach[Int](resultQueue.offer)
      consumerSource.runWith(resultSink)

      queue.offer(2)
      queue.offer(3)
      queue.complete()

      resultQueue.poll(3, TimeUnit.SECONDS) should be(2)
      resultQueue.poll(3, TimeUnit.SECONDS) should be(3)
    }

  it should "complete the attached source" in :
    val elements = List(1, 2, 3, 5, 7, 11, 13, 17)
    val source = Source.queue[Int](8)
    val sink = AttachableSink[Int]("sinkToBeCompleted")
    val (queue, controlActor) = source.toMat(sink)(Keep.both).run()

    AttachableSink.attachConsumer(controlActor).flatMap { consumerSource =>
      val consumerSink = foldSink[Int]()
      val futConsumerResult = consumerSource.runWith(consumerSink)
      elements.foreach(queue.offer)
      queue.complete()
      futConsumerResult
    }.map { result =>
      result should contain theSameElementsInOrderAs elements.reverse
    }

  it should "fail the attached source" in :
    val exception = new IllegalStateException("test exception")
    val source = Source.queue[Int](8)
    val sink = AttachableSink[Int]("sinkToBeFailed")
    val (queue, controlActor) = source.toMat(sink)(Keep.both).run()

    AttachableSink.attachConsumer(controlActor).flatMap { consumerSource =>
      val consumerSink = foldSink[Int]()
      val futConsumerResult = consumerSource.runWith(consumerSink)
      queue.offer(42)
      queue.fail(exception)
     
      recoverToExceptionIf[IllegalStateException](futConsumerResult).map(ex => ex should be(exception))
    }
