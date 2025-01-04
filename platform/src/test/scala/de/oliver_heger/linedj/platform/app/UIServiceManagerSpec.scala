/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.app

import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.Application
import org.apache.pekko.actor.Actor.Receive
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''UIServiceManager''.
  */
class UIServiceManagerSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A UIServiceManager" should "use typed messages" in:
    val service = mock[Runnable]
    val addMsg: UIServiceManager.AddService[_] =
      UIServiceManager.AddService(classOf[Runnable], service, None)

    val optMsg = addMsg as classOf[Runnable]
    optMsg shouldBe defined
    val runnable: Runnable = optMsg.get.service
    runnable should be(service)
    addMsg.as(classOf[ClientApplicationContext]) shouldBe empty

  it should "allow adding a new service" in:
    val app = mock[Application]
    val helper = new UIServiceManagerTestHelper

    helper.manager addService app
    helper.manager.services should contain only app

  it should "add a new service in the UI thread" in:
    val helper = new UIServiceManagerTestHelper
    helper.enableBus = false

    helper.manager addService mock[Application]
    helper.manager.services should have size 0

  it should "ignore add messages for other service types" in:
    val helper = new UIServiceManagerTestHelper

    helper receive UIServiceManager.AddService(classOf[Runnable], mock[Runnable], None)
    helper.manager.services should have size 0

  it should "support a manipulation of the added service in the UI thread" in:
    val app1 = mock[Application]
    val app2 = mock[Application]
    val func: Application => Application =
      case `app1` => app2
      case app => app
    val helper = new UIServiceManagerTestHelper

    helper.manager.addService(app1, Some(func))
    helper.manager.services should contain only app2

  it should "allow removing a service" in:
    val app = mock[Application]
    val helper = new UIServiceManagerTestHelper
    helper.manager addService app

    helper.manager removeService app
    helper.manager.services should have size 0

  it should "remove a service in the UI thread" in:
    val app = mock[Application]
    val helper = new UIServiceManagerTestHelper
    helper.manager addService app

    helper.enableBus = false
    helper.manager removeService app
    helper.manager.services should contain only app

  it should "ignore a remove message for another service type" in:
    val app = mock[ClientApplication]
    val helper = new UIServiceManagerTestHelper
    helper.manager addService app

    val msg = UIServiceManager.RemoveService(classOf[ClientApplication], app)
    helper receive msg
    helper.manager.services should contain only app

  it should "allow processing services" in:
    val app1 = mock[Application]
    val app2 = mock[Application]
    val func = mock[UIServiceManager.ProcessFunc[Application]]
    when(func.apply(any(classOf[Iterable[Application]]))).thenReturn(None)
    val helper = new UIServiceManagerTestHelper
    helper.manager addService app1
    helper.manager addService app2

    helper.manager processServices func
    val captor = ArgumentCaptor.forClass(classOf[Iterable[Application]])
    verify(func).apply(captor.capture())
    captor.getValue should contain only(app1, app2)

  it should "invoke the processing function on the UI thread" in:
    val func = mock[UIServiceManager.ProcessFunc[Application]]
    val helper = new UIServiceManagerTestHelper
    helper.manager addService mock[Application]
    helper.enableBus = false

    helper.manager processServices func
    verifyNoInteractions(func)

  it should "ignore a processing message for another service type" in:
    val func = mock[UIServiceManager.ProcessFunc[Runnable]]
    val helper = new UIServiceManagerTestHelper
    helper.manager addService mock[Application]

    helper receive UIServiceManager.ProcessServices(classOf[Runnable], func)
    verifyNoInteractions(func)

  it should "publish a processing result on the message bus" in:
    val Message = "ProcessingResult"
    val func = mock[UIServiceManager.ProcessFunc[Application]]
    when(func.apply(any(classOf[Iterable[Application]]))).thenReturn(Some(Message))
    val helper = new UIServiceManagerTestHelper

    helper.manager processServices func
    verify(helper.messageBus).publish(Message)

  it should "remove its message bus listener on shutdown" in:
    val helper = new UIServiceManagerTestHelper

    helper.manager.shutdown()
    verify(helper.messageBus).removeListener(helper.MessageBusRegistrationID)

  /**
    * A test helper class managing the dependencies of an instance under test.
    */
  private class UIServiceManagerTestHelper:
    /** Registration ID for the message bus listener. */
    val MessageBusRegistrationID = 20161217

    /** The mock for the message bus. */
    val messageBus: MessageBus = createMessageBus()

    /** The manager to be tested. */
    val manager: UIServiceManager[Application] =
      UIServiceManager[Application](classOf[Application], messageBus)

    /**
      * A flag whether the message should process messages. By setting this
      * flag to '''false''', processing of messages can be disabled
      * temporarily.
      */
    var enableBus: Boolean = true

    /** The message bus listener registered by the test manager. */
    private lazy val listener = fetchMessageBusListener()

    /**
      * Sends the specified message directly to the manager's
      * receive function.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def receive(msg: Any): UIServiceManagerTestHelper =
      listener(msg)
      this

    /**
      * Creates a mock for the message bus. If the bus is enabled, it is
      * prepared to pass messages to the manager's receive() method.
      *
      * @return the mock message bus
      */
    private def createMessageBus(): MessageBus =
      val bus = mock[MessageBus]
      doAnswer((invocation: InvocationOnMock) => {
        if enableBus then {
          val msg = invocation.getArguments.head
          if listener isDefinedAt msg then {
            listener(msg)
          }
        }
        null
      }).when(bus).publish(any())
      when(bus.registerListener(any(classOf[Receive]))).thenReturn(MessageBusRegistrationID)
      bus

    /**
      * Obtains the message bus listener registered by the test manager.
      *
      * @return the message bus listener
      */
    private def fetchMessageBusListener(): Receive =
      val captor = ArgumentCaptor.forClass(classOf[Receive])
      verify(messageBus).registerListener(captor.capture())
      captor.getValue

