/*
 * Copyright 2015-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.stream

import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Succeeded}

/**
  * Test class for [[AttachableSink]].
  */
class AttachableSinkSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(classic.ActorSystem("AttachableSinkSpec"))

  /** A test kit for dealing with typed actors. */
  private val typedTestkit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestkit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "AttachableSink" should "ignore all data if in unattached mode" in :
    val source = Source(List(1, 2, 3, 4, 5, 6, 7, 8))
    val sink = AttachableSink[Int]("testAttachableSink")

    val controlActor = source.runWith(sink)

    val probeWatch = typedTestkit.createTestProbe()
    probeWatch.expectTerminated(controlActor)
    Succeeded
