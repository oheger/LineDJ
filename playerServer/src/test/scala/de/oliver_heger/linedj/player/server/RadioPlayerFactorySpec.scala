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

package de.oliver_heger.linedj.player.server

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[RadioPlayerFactory]].
  */
class RadioPlayerFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("RadioPlayerFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  "RadioPlayerFactory" should "create a correct radio player" in {
    val creator = ServerConfigTestHelper.actorCreator(system)
    val config = ServerConfigTestHelper.defaultServerConfig(creator)

    val factory = new RadioPlayerFactory
    factory.createRadioPlayer(config) map { player =>
      creator.actorManagement.managedActorNames.size should be > 0
      creator.actorManagement.stopActors()

      player.config should be(config.radioPlayerConfig)
    }
  }
