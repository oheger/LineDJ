package de.oliver_heger.splaya.utils

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.splaya.io.FileReaderActor
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
 * Test class for ''ChildActorFactory''.
 *
 * This test class implements a real-life test: An actor is created of a class
 * which makes use of child actors (file reader actors are used in this
 * example). It is checked whether communication with the child actor works as
 * expected.
 */
class ChildActorFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll {
  def this() = this(ActorSystem("ChildActorFactorySpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A ChildActorFactory" should "create a correct child actor" in {
    val actor = system.actorOf(Props(new ActorWithChild with ChildActorFactory))

    actor ! ReadData(42)
    expectMsgType[EndOfFile]
  }
}

/**
 * A test actor class that creates a child actor.
 */
class ActorWithChild extends Actor {
  this: ChildActorFactory =>

  /** The child actor. */
  private var child: ActorRef = _

  /** The sending actor. */
  private var client: ActorRef = _

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    child = createChildActor(Props[FileReaderActor])
  }

  override def receive: Receive = {
    case r: ReadData =>
      child ! r
      client = sender()

    case eof: EndOfFile =>
      client ! eof
  }
}
