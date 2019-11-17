/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.actors.impl

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object ManagementActorSpec {
  /** A test actor path prefix. */
  private val ActorPathPrefix = "someTestActorPrefix/"

  /** A test ping message. */
  private val PingMessage = "PING"

  /** A test pong message. */
  private val PongMessage = "PONG"
}

/**
  * Test class for ''RemoteManagementActor''.
  */
class ManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ManagementActorSpec._

  def this() = this(ActorSystem("ManagementActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Checks a Props object for the given actor class. It is checked whether
    * the actor class is compatible and also implements ChildActorFactory.
    * @param props the Props to be checked
    * @param actorClass the expected actor class
    * @return the same Props object
    */
  private def checkCreationProps(props: Props, actorClass: Class[_]): Props = {
    actorClass.isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props
  }

  "A ManagementActor" should "create a new child actor when it is configured" in {
    val helper = new RemoteManagementActorTestHelper

    val childData = helper.configure(ActorPathPrefix)
    childData.prefix should be(ActorPathPrefix)
  }

  it should "pass messages to the child actor" in {
    val helper = new RemoteManagementActorTestHelper
    val childData = helper.configure(ActorPathPrefix)

    helper.managementActor ! PingMessage
    childData.child.expectMsg(PingMessage)
  }

  it should "forward messages to the child actor" in {
    val childActor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case PingMessage =>
          sender ! PongMessage
      }
    } ))
    val helper = new RemoteManagementActorTestHelper(Some(childActor))
    helper.configure(ActorPathPrefix)

    helper.managementActor ! PingMessage
    expectMsg(PongMessage)
  }

  it should "ignore messages before an initial configuration message" in {
    val helper = new RemoteManagementActorTestHelper

    helper.managementActor receive "BOOM"
  }

  it should "stop and re-initialize the relay actor when the prefix is changed" in {
    val Prefix2 = ActorPathPrefix + "another/"
    val helper = new RemoteManagementActorTestHelper

    val childData1 = helper.configure(ActorPathPrefix)
    val childData2 = helper.configure(Prefix2)
    childData2.prefix should be(Prefix2)
    val watcher = TestProbe()
    watcher watch childData1.child.ref
    watcher.expectMsgType[Terminated].actor should be(childData1.child.ref)

    childData2.child.expectMsg(RelayActor.Activate(true))
    val message = "CHECK"
    helper.managementActor ! message
    childData2.child.expectMsg(message)
  }

  it should "produce correct creation Props" in {
    val bus = mock[MessageBus]

    val props = checkCreationProps(ManagementActor(bus), classOf[ManagementActor])
    props.args should have length 1
    props.args.head should be(bus)
  }

  /**
    * An internally used test helper class.
    * @param optChildActor an option for a child actor to be returned by the
    *                      mock child actor factory
    */
  private class RemoteManagementActorTestHelper(optChildActor: Option[ActorRef] = None) {
    /** A mock for the message bus. */
    private val messageBus = mock[MessageBus]

    /** A queue for transporting information about created child actors. */
    private val actorCreationQueue = new ArrayBlockingQueue[ChildActorCreationData](32)

    /** The test actor. */
    val managementActor = TestActorRef(createTestActorProps())

    /**
      * Obtains data for a created child actor.
      * @return the data object
      */
    def fetchChildCreationData(): ChildActorCreationData = {
      val data = actorCreationQueue.poll(5, TimeUnit.SECONDS)
      data should not be null
      data
    }

    /**
      * Sends a path prefix message to the test actor and retrieves data
      * about the newly created child actor.
      * @param path the path prefix for the actor
      * @return data about the new child actor
      */
    def configure(path: String): ChildActorCreationData = {
      managementActor ! ManagementActor.ActorPathPrefix(path)
      fetchChildCreationData()
    }

    /**
      * Returns a ''Props'' object for creating a test actor instance.
      * @return the ''Props'' for the test actor
      */
    private def createTestActorProps(): Props =
      Props(new ManagementActor(messageBus) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          checkCreationProps(p, classOf[RelayActor])
          p.args should have size 2
          p.args(1) should be(messageBus)
          val child = TestProbe()
          actorCreationQueue offer ChildActorCreationData(prefix = p.args.head
            .asInstanceOf[String], child = child)
          optChildActor getOrElse child.ref
        }
      })
  }

}

/**
  * A data class used by the test helper to propagate data about newly created
  * child actors.
  * @param prefix the actor path prefix passed as parameter
  * @param child the test probe representing the child actor
  */
private case class ChildActorCreationData(prefix: String, child: TestProbe)
