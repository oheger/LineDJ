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

package de.oliver_heger.linedj.pleditor.ui.playlist.export

import java.io.IOException
import java.nio.file.{Files, Paths}

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.client.ActorSystemTestHelper
import de.oliver_heger.linedj.{FileTestHelper, SupervisionTestActor}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
 * Test class for ''RemoveFileActor''.
 */
class RemoveFileActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with BeforeAndAfterAll with BeforeAndAfter with Matchers with
FileTestHelper {
  def this() = this(ActorSystemTestHelper createActorSystem "RemoveFileActorSpec")

  override protected def afterAll(): Unit = {
    system.shutdown()
    ActorSystemTestHelper waitForShutdown system
  }

  after {
    tearDownTestFile()
  }

  /**
   * Creates an instance of the test actor.
   * @return the test actor ref
   */
  private def createActor(): ActorRef = system.actorOf(Props(classOf[RemoveFileActor]))

  "A RemoveFileActor" should "remove a file successfully" in {
    val file = createDataFile()
    val actor = createActor()

    actor ! RemoveFileActor.RemoveFile(file)
    expectMsg(RemoveFileActor.FileRemoved(file))
    Files exists file shouldBe false
  }

  it should "throw an exception in case of an error" in {
    val strategy = OneForOneStrategy() {
      case _: IOException => Stop
    }
    val supervisor = SupervisionTestActor(system, strategy, Props(classOf[RemoveFileActor]))
    val probe = TestProbe()
    val actor = supervisor.underlyingActor.childActor
    probe watch actor

    actor ! RemoveFileActor.RemoveFile(Paths get "non existing file")
    probe.expectMsgType[Terminated].actor should be(actor)
  }
}
