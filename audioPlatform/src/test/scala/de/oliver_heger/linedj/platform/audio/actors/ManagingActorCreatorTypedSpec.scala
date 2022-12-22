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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.platform.app.support.ActorManagement.ActorStopper
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.player.engine.PlayerEvent
import de.oliver_heger.linedj.player.engine.actors.EventManagerActor
import de.oliver_heger.linedj.player.engine.actors.EventManagerActor.EventManagerCommand
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[ManagingActorCreator]] that tests the creation of typed
  * actors.
  */
class ManagingActorCreatorTypedSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar {
  "ManagingActorCreator" should "create a typed actor" in {
    val ActorName = "MyTestTypedActor"
    val behavior = EventManagerActor[PlayerEvent]()
    val probe = testKit.createTestProbe[EventManagerCommand[PlayerEvent]]()
    val management = mock[ActorManagement]
    val factory = mock[ActorFactory]
    val context = mock[ClientApplicationContext]
    when(management.clientApplicationContext).thenReturn(context)
    when(context.actorFactory).thenReturn(factory)
    when(factory.createActor(behavior, ActorName)).thenReturn(probe.ref)

    val creator = new ManagingActorCreator(management)
    val actorRef = creator.createActor(behavior, ActorName, None)

    actorRef should be(probe.ref)
    verify(management, never()).registerActor(any(), any(), any())
  }

  it should "create a typed actor and register a stopper for a stop command" in {
    val ActorName = "MyTestTypedActor"
    val behavior = EventManagerActor[PlayerEvent]()
    val stopCommand = EventManagerActor.Stop[PlayerEvent]()
    val probe = testKit.createTestProbe[EventManagerCommand[PlayerEvent]]()
    val management = mock[ActorManagement]
    val factory = mock[ActorFactory]
    val context = mock[ClientApplicationContext]
    when(management.clientApplicationContext).thenReturn(context)
    when(context.actorFactory).thenReturn(factory)
    when(factory.createActor(behavior, ActorName)).thenReturn(probe.ref)

    val creator = new ManagingActorCreator(management)
    val actorRef = creator.createActor(behavior, ActorName, Some(stopCommand))

    actorRef should be(probe.ref)

    val captStopCommand = ArgumentCaptor.forClass(classOf[ActorStopper])
    verify(management).registerActor(eqArg(ActorName), captStopCommand.capture(), any())
    captStopCommand.getValue.stop()
    probe.expectMessage(stopCommand)
  }
}