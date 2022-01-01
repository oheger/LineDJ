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

package de.oliver_heger.linedj.pleditor.ui.reorder

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''ReorderActor''.
  */
class ReorderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with MockitoSugar {
  def this() = this(ActorSystem("ReorderActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a sequence of test songs of the given length.
    * @param count the number of test songs to create
    * @return the sequence with test songs
    */
  def createSongs(count: Int): Seq[SongData] =
    (1 to count) map (_ => mock[SongData])

  "A ReorderActor" should "handle a reorder request" in {
    val reorder = mock[PlaylistReorderer]
    val songs = createSongs(8)
    val orderedSongs = createSongs(7)
    when(reorder.reorder(songs)).thenReturn(orderedSongs)
    val request = ReorderActor.ReorderRequest(songs, 42)
    val actor = system.actorOf(Props(classOf[ReorderActor], reorder))

    actor ! request
    expectMsg(ReorderActor.ReorderResponse(orderedSongs, request))
  }
}
