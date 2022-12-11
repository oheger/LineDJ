/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.actors

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.player.engine.actors.LineWriterActor
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[ManagingActorCreator]] that tests the creation of classic
  * actors.
  */
class ManagingActorCreatorClassicsSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("ManagingActorCreatorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  "ManagingActorCreator" should "create and register a classic actor" in {
    val ActorName = "MyLineWriterActor"
    val props = Props[LineWriterActor]()
    val management = mock[ActorManagement]
    val probe = TestProbe()
    when(management.createAndRegisterActor(props, ActorName)).thenReturn(probe.ref)

    val creator = new ManagingActorCreator(management)
    val actorRef = creator.createActor(props, ActorName)

    actorRef should be(probe.ref)
  }
}
