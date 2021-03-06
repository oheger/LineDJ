package de.oliver_heger.linedj.utils

import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

/**
 * Test class for ''SchedulerSupport''.
 *
 * This class tests the functionality of the trait in a real actor.
 * Unfortunately, mocking is not possible because of major dependencies to
 * other classes (e.g. an implicit execution context). Therefore, the test
 * cannot exactly test whether the correct arguments are passed for the initial
 * delay or the interval.
 */
class SchedulerSupportSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
with Matchers with BeforeAndAfterAll with MockitoSugar {
  def this() = this(ActorSystem("SchedulerSupportSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A SchedulerSupport object" should "correctly delegate to the scheduler" in {
    val delay = FiniteDuration(50, MILLISECONDS)
    val interval = FiniteDuration(100, MILLISECONDS)
    val receiver = TestProbe()
    val message = "Hello!"
    system.actorOf(Props(new SchedulerSupport {
      var cancellable: Cancellable = _
      var count = 0

      @throws[Exception](classOf[Exception])
      override def preStart(): Unit = {
        super.preStart()
        cancellable = scheduleMessage(delay, interval, self, message)
      }

      override def receive: Receive = {
        case s: String =>
          count += 1
          if (count > 2) cancellable.cancel()
          else receiver.ref ! s
      }
    }))

    receiver.expectMsg(message)
    receiver.expectMsg(message)
    receiver.expectNoMessage(1.second)
  }

  it should "support one-time schedules" in {
    val delay = FiniteDuration(50, MILLISECONDS)
    val receiver = TestProbe()
    val message = "Hello!"
    system.actorOf(Props(new SchedulerSupport {
      @throws[Exception](classOf[Exception])
      override def preStart(): Unit = {
        super.preStart()
        scheduleMessageOnce(delay, self, message)
      }

      override def receive: Receive = {
        case s: String => receiver.ref ! s
      }
    }))

    receiver.expectMsg(message)
    receiver.expectNoMessage(1.second)
  }
}
