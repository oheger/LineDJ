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

package de.oliver_heger.linedj.platform.mediaifc.remote

import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''RemoteMediaFacadeFactory''.
  */
class RemoteMediaFacadeFactorySpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A RemoteMediaFacadeFactory" should "create a media facade" in:
    val actorSystem = mock[ActorSystem]
    val bus = mock[MessageBus]
    val actor = mock[ActorRef]
    val factory = new RemoteMediaFacadeFactory

    val facade = factory.createFacadeImpl(actor, actorSystem, bus).asInstanceOf[RemoteMediaFacade]
    facade.bus should be(bus)
    facade.relayActor should be(actor)
    facade.actorSystem should be(actorSystem)
