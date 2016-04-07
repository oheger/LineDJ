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

package de.oliver_heger.linedj.player.engine.impl

import java.util
import javax.sound.sampled.SourceDataLine

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.io.ChannelHandler.ArraySource
import de.oliver_heger.linedj.player.engine.impl.LineWriterActor.{AudioDataWritten, WriteAudioData}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
 * Test class for ''LineWriterActor''.
 */
class LineWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
with Matchers with ImplicitSender with BeforeAndAfterAll with MockitoSugar {

  def this() = this(ActorSystem("LineWriterActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A LineWriterActor" should "handle a WriteAudioData message" in {
    val line = mock[SourceDataLine]
    val data = mock[ArraySource]
    val dataArray = new Array[Byte](64)
    util.Arrays.fill(dataArray, 0.toByte)
    when(data.data).thenReturn(dataArray)
    when(data.length).thenReturn(42)
    when(data.offset).thenReturn(4)
    val actor = system.actorOf(Props[LineWriterActor])

    actor ! WriteAudioData(line, data)
    expectMsg(AudioDataWritten(42))
    verify(line).write(dataArray, 4, 42)
  }

  it should "handle a DrainLine message" in {
    val line = mock[SourceDataLine]
    val actor = system.actorOf(Props[LineWriterActor])

    actor ! LineWriterActor.DrainLine(line)
    expectMsg(LineWriterActor.LineDrained)
    verify(line).drain()
  }
}
