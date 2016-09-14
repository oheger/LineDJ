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

package de.oliver_heger.linedj.platform.bus

import akka.actor.Actor
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object UIBusSpec {
  /** A message indicating the start of a sync operation. */
  private val SyncStart = "SyncStart"

  /** A message indicating the end of a sync operation. */
  private val SyncEnd = "SyncEnd"

  /** Constant for a test message. */
  private val MessagePing = "Ping"

  /** Constant for another test message. */
  private val MessagePong = "PONG"

  /**
   * Creates a test receiver which can handle a specific message. If this
   * message is received, it is written into the protocol buffer.
   * @param msg the message to be processed
   * @param protocol the protocol buffer
   * @return the test receiver
   */
  private def createReceiver(msg: Any, protocol: StringBuilder): Actor.Receive = {
    case `msg` => protocol append msg
  }

  /**
   * Generates the expected protocol entry for a synchronized operation.
   * @param op the operation
   * @return the protocol entry for this operation
   */
  private def syncProt(op: String = ""): String = SyncStart + op + SyncEnd
}

/**
 * Test class for ''UIBus''.
 */
class UIBusSpec extends FlatSpec with Matchers with MockitoSugar {

  import UIBusSpec._

  /**
   * Creates a mock sync object. The mock directly executes passed in
   * runnable scheduled for asynchronous execution. It writes log information
   * into the given string builder so that it is possible to keep track what
   * happened in which (logical) thread.
   * @param protocol the string builder for writing logs
   * @return the mock sync object
   */
  private def createSync(protocol: StringBuilder): GUISynchronizer = {
    val sync = mock[GUISynchronizer]
    when(sync.asyncInvoke(any(classOf[Runnable]))).thenAnswer(new Answer[Unit] {
      override def answer(invocationOnMock: InvocationOnMock): Unit = {
        val task = invocationOnMock.getArguments()(0).asInstanceOf[Runnable]
        protocol append SyncStart
        task.run()
        protocol append SyncEnd
      }
    })
    sync
  }

  /**
   * Creates a test bus instance and a protocol buffer for checking
   * invocations.
   * @return a tuple with the bus and the protocol buffer
   */
  private def createBus(): (UIBus, StringBuilder) = {
    val protocol = StringBuilder.newBuilder
    (new UIBus(createSync(protocol)), protocol)
  }

  "A UIBus" should "work without listeners" in {
    val (bus, protocol) = createBus()

    bus publish MessagePing
    protocol.toString() should be(SyncStart + SyncEnd)
  }

  it should "handle listeners" in {
    val (bus, protocol) = createBus()
    val rec1 = createReceiver(MessagePing, protocol)
    val rec2 = createReceiver(MessagePong, protocol)

    val lid1 = bus registerListener rec1
    val lid2 = bus registerListener rec2
    lid1 should not be lid2
    bus publish MessagePing
    protocol.toString() should be(syncProt() + syncProt() + syncProt(MessagePing))
  }

  it should "support removing listeners" in {
    val (bus, protocol) = createBus()
    val rec1 = createReceiver(MessagePing, protocol)
    val rec2 = createReceiver(MessagePing, protocol)
    val lid = bus registerListener rec1
    bus registerListener rec2

    bus removeListener lid
    bus publish MessagePing
    protocol.toString() should be(syncProt() + syncProt() + syncProt() + syncProt(MessagePing))
  }
}
