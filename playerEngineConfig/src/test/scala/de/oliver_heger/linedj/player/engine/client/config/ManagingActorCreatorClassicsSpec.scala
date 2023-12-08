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

package de.oliver_heger.linedj.player.engine.client.config

import de.oliver_heger.linedj.utils.ActorManagement.ActorStopper
import de.oliver_heger.linedj.utils.{ActorFactory, ActorManagement}
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq => eqArg}
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
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ManagingActorCreatorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  "ManagingActorCreator" should "create and register a classic actor" in:
    val ActorName = "MyLineWriterActor"
    val props = Props[TestActorImpl]()
    val factory = mock[ActorFactory]
    val management = mock[ActorManagement]
    val probe = TestProbe()
    when(factory.createActor(props, ActorName)).thenReturn(probe.ref)

    val creator = new ManagingActorCreator(factory, management)
    val actorRef = creator.createClassicActor(props, ActorName)

    actorRef should be(probe.ref)
    verify(management).registerActor(ActorName, probe.ref)

  it should "create and register a classic actor with a custom stop command" in:
    val ActorName = "MyCustomStopActor"
    val StopCommand = "Please, stop now!"
    val props = Props[TestActorImpl]()
    val factory = mock[ActorFactory]
    val management = mock[ActorManagement]
    val probe = TestProbe()
    when(factory.createActor(props, ActorName)).thenReturn(probe.ref)

    val creator = new ManagingActorCreator(factory, management)
    val actorRef = creator.createClassicActor(props, ActorName, Some(StopCommand))

    actorRef should be(probe.ref)
    val captStopper = ArgumentCaptor.forClass(classOf[ActorStopper])
    verify(management).registerActor(eqArg(ActorName), captStopper.capture(), eqArg(Some(probe.ref)))

    captStopper.getValue.stop()
    probe.expectMsg(StopCommand)

/**
  * A test actor implementation that can be used to test the creation of
  * classic actors.
  */
private class TestActorImpl extends Actor:
  override def receive: Receive = Actor.emptyBehavior
