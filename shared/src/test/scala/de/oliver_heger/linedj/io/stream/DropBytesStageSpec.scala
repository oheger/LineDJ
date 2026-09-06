/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.io.stream

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[DropBytesStage]].
  */
class DropBytesStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("DropBytesStageSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "A DropBytesStage" should "drop the specified number of bytes" in :
    val data = ByteString("Hello, World!")
    Source(List(data)).via(new DropBytesStage(7)).runWith(Sink.head).map:
      result => result should be(ByteString("World!"))

  it should "pass through all data if the offset is zero" in :
    val data = ByteString("Hello, World!")
    Source(List(data)).via(new DropBytesStage(0)).runWith(Sink.head).map:
      result => result should be(data)

  it should "drop all data if the offset equals the data size" in :
    val data = ByteString("Hello, World!")
    Source(List(data)).via(new DropBytesStage(13)).runWith(Sink.fold(ByteString.empty)(_ ++ _)).map:
      result => result should be(ByteString.empty)

  it should "handle multiple chunks" in :
    val chunk1 = ByteString("Hello, ")
    val chunk2 = ByteString("World!")
    Source(List(chunk1, chunk2)).via(new DropBytesStage(7)).runWith(Sink.head).map:
      result => result should be(ByteString("World!"))

  it should "drop bytes across chunk boundaries" in :
    val chunk1 = ByteString("Hello")
    val chunk2 = ByteString(", World!")
    Source(List(chunk1, chunk2)).via(new DropBytesStage(8)).runWith(Sink.head).map:
      result => result should be(ByteString("orld!"))
