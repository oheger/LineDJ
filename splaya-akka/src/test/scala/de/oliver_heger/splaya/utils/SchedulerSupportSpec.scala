package de.oliver_heger.splaya.utils

import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import org.mockito.Matchers.{eq => argEq}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

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
class SchedulerSupportSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
with Matchers with BeforeAndAfterAll with MockitoSugar {
  def this() = this(ActorSystem("SchedulerSupportSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
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
        import context.dispatcher
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
    receiver.expectNoMsg(1.second)
  }
}
