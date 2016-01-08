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

package de.oliver_heger.linedj.client

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._

object ActorSystemTestHelper {
  /**
    * Waits until the specified actor system is terminated. Note: This
    * method does not itself terminate the actor system.
    * @param actorSystem the actor system in question
    */
  def waitForShutdown(actorSystem: ActorSystem): Unit = {
    actorSystem.awaitTermination(10.seconds)
  }

  /**
    * Creates a test actor system which uses a dummy configuration. This
    * actor system can be safely used within tests; it does not use the
    * default (production) configuration.
    * @param name the name of the actor system
    * @return the new actor system
    */
  def createActorSystem(name: String): ActorSystem =
    ActorSystem(name, createDummyConfig())

  /**
    * Creates a dummy configuration for an actor system. This configuration
    * does not contain any meaningful keys.
    * @return the dummy configuration
    */
  private def createDummyConfig(): Config =
    ConfigFactory.parseString("{}")
}

/**
  * A trait that can be used by test classes which need an actor system.
  *
  * This trait creates an actor system that uses the test configuration, not
  * the production configuration. There is also a method which shutsdown the
  * actor system; this should be called at test end.
  */
trait ActorSystemTestHelper {
  import ActorSystemTestHelper._

  /** The name of the actor system. Must be defined by subclasses. */
  val actorSystemName: String

  /** The test actor system. */
  private var testActorSystemVar: ActorSystem = _

  /**
    * Returns the test actor system managed by this trait. It is created
    * on demand.
    * @return the test actor system
    */
  def testActorSystem: ActorSystem = {
    if (testActorSystemVar == null) {
      testActorSystemVar = createActorSystem(actorSystemName)
    }
    testActorSystemVar
  }

  /**
    * Shuts down the actor system. This method should be called after all tests
    * were run.
    */
  def shutdownActorSystem(): Unit = {
    if (testActorSystemVar != null) {
      testActorSystem.shutdown()
      waitForShutdown(testActorSystem)
      testActorSystemVar = null
    }
  }
}
