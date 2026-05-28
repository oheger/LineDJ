/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.shared.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import java.time.{Duration, Instant}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

object BackoffActorSpec:
  /** The timeout (in ms) when checking that no call was done. */
  private val NoCallTimeoutMs = 100

  /**
    * Returns a special task function that tracks the times when it is called
    * and stops after a given number of calls.
    *
    * @param taskTimeQueue the queue to track invocation times
    * @param maxCalls      the number of calls before cancelling
    * @return the task function
    */
  private def limitedTrackingTaskFunc(taskTimeQueue: LinkedBlockingQueue[Instant], maxCalls: Int):
  BackoffActor.TaskFunc =
    trackingTaskFuncWithIndex(taskTimeQueue): index =>
      if index >= maxCalls then
        BackoffActor.TaskResult.Cancel
      else
        BackoffActor.TaskResult.Backoff

  /**
    * Returns a special task function that tracks the time when it is called
    * and yields a result computed based on the index.
    *
    * @param taskTimeQueue the queue to track invocation times
    * @param f             the function to compute the task result
    * @return the task function
    */
  private def trackingTaskFuncWithIndex(taskTimeQueue: LinkedBlockingQueue[Instant])
                                       (f: Int => BackoffActor.TaskResult): BackoffActor.TaskFunc =
    trackingTaskFuncWithIndexFuture(taskTimeQueue): index =>
      Future.successful(f(index))

  /**
    * Returns a special task function that tracks the time when it is called
    * and yields a [[Future]] result based on the index.
    *
    * @param taskTimeQueue the queue to track invocation times
    * @param f             the function to compute the future task result
    * @return the task function
    */
  private def trackingTaskFuncWithIndexFuture(taskTimeQueue: LinkedBlockingQueue[Instant])
                                             (f: Int => Future[BackoffActor.TaskResult]): BackoffActor.TaskFunc =
    val invocationCounter = new AtomicInteger(0)
    () =>
      taskTimeQueue.offer(Instant.now())
      f(invocationCounter.incrementAndGet())
end BackoffActorSpec

/**
  * Test class for [[BackoffActor]].
  */
class BackoffActorSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll,
  Matchers:
  def this() = this(ActorSystem("BackoffActorSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import BackoffActorSpec.*

  /**
    * Reads the next call time from the given queue. Fails if no time is
    * available within a timeout.
    *
    * @param queue the queue storing call times
    * @return the next call time from this queue
    */
  private def nextCallTime(queue: LinkedBlockingQueue[Instant]): Instant =
    val time = queue.poll(3, TimeUnit.SECONDS)
    time should not be null
    time

  /**
    * Checks that no calls are recorded anymore at the given queue.
    *
    * @param queue the queue to check
    */
  private def expectNoCall(queue: LinkedBlockingQueue[Instant]): Unit =
    queue.poll(NoCallTimeoutMs, TimeUnit.MILLISECONDS) should be(null)

  /**
    * Checks whether a delay is close to an expected value.
    *
    * @param expected the expected delay
    * @param actual   the actual delay
    */
  private def checkDelay(expected: Duration, actual: Duration): Unit =
    actual.compareTo(expected) should be >= 0
    actual.minus(expected).toMillis should be < 50L

  /**
    * Checks whether the delay between invocations is correctly increased by
    * the given factor.
    *
    * @param queue           the queue to obtain the invocation times
    * @param incrementFactor the increment factor
    */
  private def checkDelaySequence(queue: LinkedBlockingQueue[Instant], incrementFactor: Double): Unit =
    val startTime = nextCallTime(queue)
    (1 to 4).foldLeft(startTime): (lastTime, index) =>
      val callTime = nextCallTime(queue)
      val delay = Duration.between(lastTime, callTime)
      val expectedDelay = Duration.ofMillis(25 * math.pow(incrementFactor, index - 1).toLong)
      withClue(s"Checking expected delay $expectedDelay in iteration $index."):
        checkDelay(expectedDelay, delay)
      callTime

  "A backoff actor" should "call the task function with standard increasing delays" in :
    val queue = new LinkedBlockingQueue[Instant]
    val params = BackoffActor.BackoffParameters(
      taskFunc = limitedTrackingTaskFunc(queue, maxCalls = 8),
      minBackoff = 25.millis,
      maxBackoff = 1.second
    )

    BackoffActor.newInstance(params, "standardIncrement")

    checkDelaySequence(queue, 2)

  it should "call the task function with an alternative increment factor" in :
    val Factor = 1.5
    val queue = new LinkedBlockingQueue[Instant]
    val params = BackoffActor.BackoffParameters(
      taskFunc = limitedTrackingTaskFunc(queue, maxCalls = 8),
      minBackoff = 25.millis,
      maxBackoff = 1.second,
      incrementFactor = Factor
    )

    BackoffActor.newInstance(params, "customIncrement")

    checkDelaySequence(queue, Factor)

  it should "correctly apply the maxBackoff parameter" in :
    val queue = new LinkedBlockingQueue[Instant]
    val params = BackoffActor.BackoffParameters(
      taskFunc = limitedTrackingTaskFunc(queue, maxCalls = 8),
      minBackoff = 25.millis,
      maxBackoff = 50.millis
    )

    BackoffActor.newInstance(params, "maxBackoff")

    val startTime = nextCallTime(queue)
    (1 to 5).foldLeft(startTime): (lastTime, index) =>
      val callTime = nextCallTime(queue)
      val delay = Duration.between(lastTime, callTime)
      withClue(s"Checking delay $delay in iteration $index."):
        delay.toMillis should be < 150L
      callTime

  it should "reset the invocation delay when requested" in :
    val ResetIndex = 4
    val queue = new LinkedBlockingQueue[Instant]
    val taskFunc = trackingTaskFuncWithIndex(queue):
      case ResetIndex => BackoffActor.TaskResult.Reset
      case i if i > ResetIndex => BackoffActor.TaskResult.Cancel
      case _ => BackoffActor.TaskResult.Backoff
    val params = BackoffActor.BackoffParameters(
      taskFunc = taskFunc,
      minBackoff = 25.millis,
      maxBackoff = 1.minute
    )

    BackoffActor.newInstance(params, "reset")

    (1 until ResetIndex).foreach(_ => nextCallTime(queue))
    val t1 = nextCallTime(queue)
    val t2 = nextCallTime(queue)
    checkDelay(Duration.ofMillis(params.minBackoff.toMillis), Duration.between(t1, t2))

  it should "stop calling the task after receiving a Cancel result" in :
    val CancelIndex = 3
    val queue = new LinkedBlockingQueue[Instant]
    val params = BackoffActor.BackoffParameters(
      taskFunc = limitedTrackingTaskFunc(queue, CancelIndex),
      minBackoff = 10.millis,
      maxBackoff = 1.hour,
      incrementFactor = 1.1
    )

    BackoffActor.newInstance(params, "cancel")

    (1 to CancelIndex).foreach(_ => nextCallTime(queue))
    expectNoCall(queue)

  it should "use the configured failure result if the task function fails" in :
    val FailureIndex = 4
    val queue = new LinkedBlockingQueue[Instant]
    val taskFunc = trackingTaskFuncWithIndexFuture(queue):
      case FailureIndex => Future.failed(new IllegalStateException("Test exception."))
      case i if i > FailureIndex => Future.successful(BackoffActor.TaskResult.Cancel)
      case _ => Future.successful(BackoffActor.TaskResult.Backoff)
    val params = BackoffActor.BackoffParameters(
      taskFunc = taskFunc,
      minBackoff = 25.millis,
      maxBackoff = 1.minute,
      failureResult = BackoffActor.TaskResult.Reset
    )

    BackoffActor.newInstance(params, "failure")

    (1 until FailureIndex).foreach(_ => nextCallTime(queue))
    val t1 = nextCallTime(queue)
    val t2 = nextCallTime(queue)
    checkDelay(Duration.ofMillis(params.minBackoff.toMillis), Duration.between(t1, t2))

  it should "stop itself when closing the handle" in :
    val ActorName = "reuse"
    val queue = new LinkedBlockingQueue[Instant]
    val params = BackoffActor.BackoffParameters(
      taskFunc = limitedTrackingTaskFunc(queue, 25),
      minBackoff = (NoCallTimeoutMs - 10).millis,
      maxBackoff = 1.second
    )

    val handle = BackoffActor.newInstance(params, ActorName)
    nextCallTime(queue)
    handle.close()

    expectNoCall(queue)
    // This would fail with a non-unique actor name if the old instance still existed.
    val handle2 = BackoffActor.newInstance(params, ActorName)
    handle2.close()
