/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine

import de.oliver_heger.linedj.player.engine.PlayerConfigSpec.TestPlayerConfig
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object PlayerConfigSpec:
  /** A test player configuration that can be used by all tests. */
  final val TestPlayerConfig = PlayerConfig(inMemoryBufferSize = 10000,
    playbackContextLimit = 24000,
    bufferFileSize = 65536,
    bufferChunkSize = 16384,
    bufferFilePrefix = "Buffer",
    bufferFileExtension = ".buf",
    bufferTempPath = None,
    bufferTempPathParts = List(".lineDj", "temp"),
    downloadInProgressNotificationDelay = 3.minutes,
    downloadInProgressNotificationInterval = 2.minutes,
    timeProgressThreshold = 500.millis,
    blockingDispatcherName = None,
    mediaManagerActor = null,
    actorCreator = null)

/**
  * Test class for ''PlayerConfig''.
  */
class PlayerConfigSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "PlayerConfig" should "return the same source if no blocking dispatcher is defined" in:
    val source = Source.single("test")
    val config = TestPlayerConfig.copy(mediaManagerActor = mock[ActorRef], actorCreator = mock[ActorCreator])

    val modifiedSource = config.applyBlockingDispatcher(source)

    modifiedSource should be theSameInstanceAs source

  it should "apply the blocking dispatcher to a source" in:
    val BlockingDispatcher = "IO-Dispatcher"
    val source = Source.single("blockingTest")
    val config = TestPlayerConfig.copy(blockingDispatcherName = Some(BlockingDispatcher),
      mediaManagerActor = mock[ActorRef],
      actorCreator = mock[ActorCreator])

    val modifiedSource = config.applyBlockingDispatcher(source)

    modifiedSource.getAttributes.get[ActorAttributes.Dispatcher] match
      case Some(value) =>
        value.dispatcher should be(BlockingDispatcher)
      case None => fail("No dispatcher attribute found.")
