/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.ext

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerFunction
import de.oliver_heger.linedj.shared.archive.metadata.MetaDataScanCompleted
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object MediaIfcExtensionSpec {
  /** A message that is handled in a special way. */
  private val Message = 42

  /** An answer for the special message. */
  private val Answer = "That's true."

  /**
    * Generates a string with output for a test consumer.
    *
    * @param idx  the index of this consumer
    * @param data the data passed to the consumer
    * @return the output of the consumer function
    */
  private def consumerOutput(idx: Int, data: String): String = s"$idx:$data"

  /**
    * Parsers a string produced by the test consumers and returns an array with
    * the single lines of output produced by each consumer. This is necessary
    * because the order in which consumers are invoked is not specified.
    *
    * @param s the string generated by test consumers
    * @return the string split into the outputs for single consumers
    */
  private def parseInvokedConsumers(s: String): Array[String] = s.trim.split(" ")

  /**
    * A function producing a message and counting how often it is invoked.
    * This is used to test whether invokeConsumers() uses non-strict
    * evaluation.
    *
    * @param count the counter
    * @return the string message
    */
  private def messageCreator(count: AtomicInteger): String = {
    count.incrementAndGet()
    Answer
  }
}

/**
  * Test class for ''MediaIfcExtension''.
  */
class MediaIfcExtensionSpec extends FlatSpec with Matchers with MockitoSugar {

  import MediaIfcExtensionSpec._

  /**
    * Creates an object for a consumer registration for the specified
    * parameters.
    *
    * @param idx  the index of the consumer
    * @param cons the consumer function
    * @return the test registration object
    */
  private def createRegistration(idx: Int, cons: MediaIfcExtension.ConsumerFunction[String]):
  MediaIfcExtension.ConsumerRegistration[String] = {
    val reg = mock[MediaIfcExtension.ConsumerRegistration[String]]
    when(reg.id).thenReturn(ComponentID())
    when(reg.callback).thenReturn(cons)
    reg
  }

  /**
    * Creates an object for a consumer registration. The object returns a
    * consumer ID with the given index and uses a consumer function which
    * adds the following text to the given string builder:
    * `consumerID:value` where ''consumerID'' is this consumer's ID and
    * value is the parameter passed to the function.
    *
    * @param idx the index of the consumer
    * @param buf an optional string builder used by the consumer function
    * @return the test registration object
    */
  private def createRegistration(idx: Int, buf:
  StringBuilder = new StringBuilder(32)): MediaIfcExtension.ConsumerRegistration[String] = {
    val cb: MediaIfcExtension.ConsumerFunction[String] = s => {
      buf ++= consumerOutput(idx, s) + " "
    }
    createRegistration(idx, cb)
  }

  "A MediaIfcExtension" should "return true for the first added consumer" in {
    val ext = new MediaIfcExtensionTestImpl

    ext addConsumer createRegistration(1) shouldBe true
  }

  it should "return false for further consumers added" in {
    val ext = new MediaIfcExtensionTestImpl
    ext addConsumer createRegistration(1)

    ext addConsumer createRegistration(2) shouldBe false
  }

  it should "correctly invoke the added consumer callback" in {
    val reg1 = createRegistration(1)
    val reg2 = createRegistration(2)
    val ext = new MediaIfcExtensionTestImpl

    ext addConsumer reg1
    ext addConsumer reg2
    ext.consumerAddedNotifications.get() should be(List((reg2.callback, false),
      (reg1.callback, true))) // list in reverse order
  }

  it should "support passing data to registered consumers" in {
    val buf = new StringBuilder(64)
    val ext = new MediaIfcExtensionTestImpl
    ext addConsumer createRegistration(1, buf)
    ext addConsumer createRegistration(2, buf)
    val data = "TestData"

    ext invokeConsumers data
    val output = parseInvokedConsumers(buf.toString())
    output should contain only(consumerOutput(1, data), consumerOutput(2, data))
  }

  it should "return true when removing the last consumer" in {
    val ext = new MediaIfcExtensionTestImpl
    val reg = createRegistration(1)
    ext addConsumer reg

    ext removeConsumer reg.id shouldBe true
  }

  it should "return false when consumers remain after the remove operation" in {
    val ext = new MediaIfcExtensionTestImpl
    val reg = createRegistration(1)
    ext addConsumer reg
    ext addConsumer createRegistration(2)

    ext removeConsumer reg.id shouldBe false
  }

  it should "remove a consumer from the internal map" in {
    val buf = new StringBuilder(32)
    val ext = new MediaIfcExtensionTestImpl
    val reg = createRegistration(1, buf)
    ext addConsumer reg
    ext addConsumer createRegistration(2, buf)

    ext removeConsumer reg.id
    val data = "some test data"
    ext invokeConsumers data
    buf.toString().trim should be(consumerOutput(2, data))
  }

  it should "correctly handle an unknown ID in removeConsumer()" in {
    val ext = new MediaIfcExtensionTestImpl

    ext removeConsumer ComponentID() shouldBe false
  }

  it should "correctly invoke the consumer removed callback" in {
    val reg1 = createRegistration(1)
    val reg2 = createRegistration(2)
    val ext = new MediaIfcExtensionTestImpl
    ext addConsumer reg1
    ext addConsumer reg2

    ext removeConsumer reg1.id
    ext removeConsumer reg2.id
    // list in reverse order
    ext.consumerRemovedNotifications.get() should be(List(true, false))
  }

  it should "deal with failed removals when invoking the consumer removed callback" in {
    val ext = new MediaIfcExtensionTestImpl

    ext removeConsumer ComponentID()
    ext.consumerRemovedNotifications.get() shouldBe 'empty
  }

  it should "invoke a notification method when the archive becomes available" in {
    val ext = new MediaIfcExtensionTestImpl

    ext receive MediaFacade.MediaArchiveAvailable
    ext.archiveAvailableNotifications.get() should be(List(false))
  }

  it should "set the correct parameter in the onArchiveAvailable() callback" in {
    val ext = new MediaIfcExtensionTestImpl
    ext addConsumer createRegistration(1)

    ext receive MediaFacade.MediaArchiveAvailable
    ext.archiveAvailableNotifications.get() should be(List(true))
  }

  it should "invoke a notification method when a scan is completed" in {
    val ext = new MediaIfcExtensionTestImpl

    ext receive MetaDataScanCompleted
    ext.scanCompletedNotifications.get() should be(List(false))
  }

  it should "set the correct parameter in the onScanCompleted() callback" in {
    val ext = new MediaIfcExtensionTestImpl
    ext addConsumer createRegistration(1)

    ext receive MetaDataScanCompleted
    ext.scanCompletedNotifications.get() should be(List(true))
  }

  it should "pass other messages to the specific receive function" in {
    val buf = new StringBuilder(32)
    val ext = new MediaIfcExtensionTestImpl
    ext addConsumer createRegistration(1, buf)

    ext receive Message
    ext.scanCompletedNotifications.get() shouldBe 'empty
    ext.archiveAvailableNotifications.get() shouldBe 'empty
    buf.toString().trim should be(consumerOutput(1, Answer))
  }

  it should "allow overriding default messages" in {
    val data = "Completed!"
    val ext = new MediaIfcExtensionTestImpl {
      override protected def receiveSpecific: Receive = {
        case MetaDataScanCompleted => invokeConsumers(data)
      }
    }
    val buf = new StringBuilder(32)
    ext addConsumer createRegistration(1, buf)

    ext receive MetaDataScanCompleted
    ext.scanCompletedNotifications.get() shouldBe 'empty
    buf.toString().trim should be(consumerOutput(1, data))
  }

  it should "ignore unknown messages" in {
    val ext = new MediaIfcExtensionTestImpl

    ext.receive.isDefinedAt(this) shouldBe false
  }

  it should "provide a default receiveSpecific() implementation" in {
    val ext = new MediaIfcExtension[String] {}

    ext.receive.isDefinedAt(MetaDataScanCompleted) shouldBe true
    ext.receive.isDefinedAt(this) shouldBe false
  }

  it should "evaluate the message passed to invokeConsumers lazily" in {
    val ext = new MediaIfcExtensionTestImpl
    val count = new AtomicInteger

    ext invokeConsumers messageCreator(count)
    count.get() should be(0)
  }

  it should "evaluate the message passed to invokeConsumers at most once" in {
    val ext = new MediaIfcExtensionTestImpl
    ext addConsumer createRegistration(1)
    ext addConsumer createRegistration(2)
    val count = new AtomicInteger

    ext invokeConsumers messageCreator(count)
    count.get() should be(1)
  }

  it should "support grouping of consumers" in {
    val buf = new StringBuilder(64)
    val ext = new MediaIfcExtensionTestImpl
    val key = "SpecialGroupingKey"
    ext addConsumer createRegistration(1, buf)
    ext.addConsumer(createRegistration(2, buf), key)
    ext.addConsumer(createRegistration(3, buf), key)
    val data = "TestData"

    ext.invokeConsumers(data, key)
    val output = parseInvokedConsumers(buf.toString())
    output should contain only(consumerOutput(2, data), consumerOutput(3, data))
  }

  /**
    * A test implementation for the trait to be tested.
    */
  private class MediaIfcExtensionTestImpl extends MediaIfcExtension[String] {
    /** A list reference for recording archive available notifications. */
    val archiveAvailableNotifications = createRecordList()

    /** A list reference for recording scan complete notifications. */
    val scanCompletedNotifications = createRecordList()

    /** A list reference for tracking consumer added notifications. */
    val consumerAddedNotifications =
    new AtomicReference[List[(ConsumerFunction[String], Boolean)]](List.empty)

    /** A list reference for tracking consumer removed notifications. */
    val consumerRemovedNotifications = createRecordList()

    /**
      * @inheritdoc Handles a special message.
      */
    override protected def receiveSpecific: Receive = {
      case Message => invokeConsumers(Answer)
    }

    /**
      * @inheritdoc Records this invocation.
      */
    override def onArchiveAvailable(hasConsumers: Boolean): Unit = {
      super.onArchiveAvailable(hasConsumers)
      archiveAvailableNotifications.set(hasConsumers :: archiveAvailableNotifications.get())
    }

    /**
      * @inheritdoc Records this invocation.
      */
    override def onMediaScanCompleted(hasConsumers: Boolean): Unit = {
      super.onMediaScanCompleted(hasConsumers)
      scanCompletedNotifications.set(hasConsumers :: scanCompletedNotifications.get())
    }

    /**
      * @inheritdoc Records this invocation.
      */
    override def onConsumerAdded(cons: ConsumerFunction[String], first: Boolean): Unit = {
      super.onConsumerAdded(cons, first)
      consumerAddedNotifications.set((cons, first) :: consumerAddedNotifications.get())
    }

    /**
      * @inheritdoc Records this invocation.
      */
    override def onConsumerRemoved(last: Boolean): Unit = {
      super.onConsumerRemoved(last)
      consumerRemovedNotifications.set(last :: consumerRemovedNotifications.get())
    }

    /**
      * Creates a list for recording callback invocations.
      *
      * @return the list reference
      */
    private def createRecordList(): AtomicReference[List[Boolean]] =
    new AtomicReference[List[Boolean]](List.empty)
  }

}
