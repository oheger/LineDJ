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

package de.oliver_heger.linedj.platform.mediaifc.embedded

import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''EmbeddedMediaFacadeFactory''.
  */
class EmbeddedMediaFacadeFactorySpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "An EmbeddedMediaFacadeFactory" should "create a correct facade" in:
    val relayActor = mock[ActorRef]
    val actorSystem = mock[ActorSystem]
    val messageBus = mock[MessageBus]
    val factory = new EmbeddedMediaFacadeFactory

    val facade = factory.createFacadeImpl(relayActor, actorSystem, messageBus)
      .asInstanceOf[EmbeddedMediaFacade]
    facade.relayActor should be(relayActor)
    facade.actorSystem should be(actorSystem)
    facade.bus should be(messageBus)
