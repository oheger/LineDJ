/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.support

import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ClientContextSupport}
import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

object ActorClientSupportSpec:
  /** Constant for a test PING message. */
  private val MsgPing = new Object

  /** Constant for a test response message. */
  private val MsgPong = 42

  /** Duration for timeouts and wait calls. */
  private val TimeoutDuration = 10.seconds

  /** Default timeout to be applied. */
  implicit private val TimeoutValue: Timeout = Timeout(TimeoutDuration)

  /** The message bus registration ID. */
  private val RegistrationID = 20170222

  /**
    * A simple test actor which answers to a predefined message with a
    * predefined response and ignores all other messages.
    */
  class AskActor extends Actor:
    override def receive: Receive =
      case MsgPing => sender() ! MsgPong

/**
  * Test class for ''ActorClientSupport''.
  */
class ActorClientSupportSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import ActorClientSupportSpec._

  def this() = this(ActorSystem("ActorClientSupportSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Checks whether an actor ref points to the specified probe.
    *
    * @param ref   the ref to be checked
    * @param probe the expected probe
    */
  private def checkActorRef(ref: ActorRef, probe: TestProbe): Unit =
    ref ! MsgPing
    probe.expectMsg(MsgPing)

  "An ActorClientSupport" should "allow operations on Future objects" in:
    val helper = new ActorClientSupportTestHelper

    val future = helper.support.operateOnFuture(21)
    Await.result(future, 3.seconds) should be(42)

  it should "support querying an actor by name" in:
    val helper = new ActorClientSupportTestHelper
    val probe = TestProbe()
    val path = probe.ref.path.toStringWithoutAddress

    val futureRef = helper.support resolveActor path
    Await.ready(futureRef, TimeoutDuration).value match
      case Some(Success(ref)) =>
        checkActorRef(ref, probe)
      case e =>
        fail("Could not resolve reference: " + e)

  it should "handle an actor path which cannot be resolved" in:
    val helper = new ActorClientSupportTestHelper

    val futureRef = helper.support resolveActor "nonExistingPath"
    Await.ready(futureRef, TimeoutDuration).value match
      case Some(Failure(_)) => // this is fine
      case o => fail("Unexpected result: " + o)

  it should "support querying an actor by name in the UI thread" in:
    val helper = new ActorClientSupportTestHelper
    val probe = TestProbe()
    val path = probe.ref.path.toStringWithoutAddress
    val func = mock[Try[ActorRef] => Unit]

    helper.activate()
    helper.support.resolveActorUIThread(path)(func)
    val uiMsg = helper.expectUIMessage()
    verifyNoInteractions(func)
    helper sendToBusListener uiMsg
    val captor = ArgumentCaptor.forClass(classOf[Try[ActorRef]])
    verify(func).apply(captor.capture())
    captor.getValue match
      case Success(ref) =>
        checkActorRef(ref, probe)
      case e =>
        fail("Could not resolve reference: " + e)

  it should "complete a Future in the UI thread" in:
    val helper = new ActorClientSupportTestHelper
    val value = new AtomicInteger
    val func: Try[Int] => Unit =
      case Success(i) => value.set(i)
      case e => fail("Unexpected result: " + e)

    helper.activate().support.operateOnFutureInUIThread(5, func)
    val uiMsg = helper.expectUIMessage()
    value.get() should be(0)
    helper sendToBusListener uiMsg
    value.get() should be(10)

  it should "remove the message bus registration on deactivation" in:
    val helper = new ActorClientSupportTestHelper

    helper.activate().deactivate()
    verify(helper.clientContext.messageBus).removeListener(RegistrationID)

  it should "invoke the inherited activate() method" in:
    val helper = new ActorClientSupportTestHelper

    helper.activate().support.activateCount should be(1)

  it should "invoke the inherited deactivate() method" in:
    val helper = new ActorClientSupportTestHelper

    helper.deactivate().support.deactivateCount should be(1)

  it should "support querying an actor from the UI thread" in:
    val helper = new ActorClientSupportTestHelper
    helper.activate()
    val actor = system.actorOf(Props[AskActor](), "askActor")
    val refAnswer = new AtomicInteger

    val request = helper.support.actorRequest(actor, MsgPing)
    request executeUIThread { (t: Try[Int]) =>
      t match
        case Success(i) => refAnswer.set(i)
        case e => fail("Unexpected result: " + e)
    }
    refAnswer.get() should be(0)
    helper sendToBusListener helper.expectUIMessage()
    refAnswer.get() should be(MsgPong)

  it should "respect the timeout for an actor invocation" in:
    val helper = new ActorClientSupportTestHelper
    helper.activate()
    val actor = system.actorOf(Props[AskActor](), "askActorTimeout")
    val refAnswer = new AtomicReference[Try[Int]]
    val timeout = Timeout(100.millis)

    val request = helper.support.actorRequest(actor, MsgPong)(timeout)
    request executeUIThread refAnswer.set
    helper sendToBusListener helper.expectUIMessage()
    refAnswer.get() match
      case Failure(exception) =>
        exception shouldBe a[AskTimeoutException]
      case r => fail("Unexpected result: " + r)

  /**
    * Test helper class that manages a test instance and its dependencies.
    */
  private class ActorClientSupportTestHelper:
    /** The client application context. */
    val clientContext: ClientApplicationContext = createClientContext()

    /** The instance to be tested. */
    val support: SupportImpl = createTestInstance()

    /**
      * Stores the message bus listener that has been registered during
      * activation.
      */
    private var busListener: Actor.Receive = _

    /** A queue for retrieving messages published to the message bus. */
    private val messageQueue = new LinkedBlockingQueue[AnyRef]

    /**
      * Activates the test object. This also checks whether a registration
      * for the message bus is created.
      *
      * @return this test helper
      */
    def activate(): ActorClientSupportTestHelper =
      val componentContext = mock[ComponentContext]
      support activate componentContext
      verifyNoInteractions(componentContext)
      val captor = ArgumentCaptor.forClass(classOf[Actor.Receive])
      verify(clientContext.messageBus).registerListener(captor.capture())
      busListener = captor.getValue
      this

    /**
      * Calls ''deactivate()'' on the test object.
      *
      * @return this test helper
      */
    def deactivate(): ActorClientSupportTestHelper =
      val componentContext = mock[ComponentContext]
      support deactivate componentContext
      verifyNoInteractions(componentContext)
      this

    /**
      * Expects that a message has been passed to the message bus.
      *
      * @return the message
      */
    def expectUIMessage(): AnyRef =
      val msg = messageQueue.poll(TimeoutDuration.toMillis, TimeUnit.MILLISECONDS)
      msg should not be null
      msg

    /**
      * Sends the specified message to the registered message bus listener.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendToBusListener(msg: Any): ActorClientSupportTestHelper =
      busListener should not be null
      busListener.apply(msg)
      this

    /**
      * Creates a client application context object with some mock references.
      *
      * @return the context
      */
    private def createClientContext(): ClientApplicationContext =
      val context = mock[ClientApplicationContext]
      when(context.actorSystem).thenReturn(system)
      val bus = mock[MessageBus]
      when(context.messageBus).thenReturn(bus)
      when(bus.registerListener(any(classOf[Actor.Receive]))).thenReturn(RegistrationID)
      doAnswer((invocation: InvocationOnMock) => {
        messageQueue.put(invocation.getArguments.head)
        null
      }).when(bus).publish(any())
      context

    /**
      * Creates the test instance.
      *
      * @return the object to be tested
      */
    private def createTestInstance(): SupportImpl =
      val sup = new SupportImpl
      sup initClientContext clientContext
      sup


/**
  * A test implementation mixing in the trait to be tested.
  */
private class SupportImpl extends ClientContextSupport with SuperInvocationCheck
  with ActorClientSupport:
  /**
    * Checks whether an operation on a future is possible without having to
    * provide an executor context.
    *
    * @param x the input
    * @return the future result
    */
  def operateOnFuture(x: Int): Future[Int] =
    Future {
      x
    } map (_ * 2)

  /**
    * Tests the functionality to complete a Future in the UI tjread.
    *
    * @param x the input
    * @param f the callback to complete the function
    */
  def operateOnFutureInUIThread(x: Int, f: Try[Int] => Unit): Unit =
    operateOnFuture(x).onCompleteUIThread(f)
