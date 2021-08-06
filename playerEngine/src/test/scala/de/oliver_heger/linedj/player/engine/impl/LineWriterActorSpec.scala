/*
 * Copyright 2015-2021 The Developers Team.
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

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.player.engine.impl.LineWriterActor.{AudioDataWritten, WriteAudioData}
import javax.sound.sampled.SourceDataLine
import org.mockito.Matchers.{anyInt, eq => argEq}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''LineWriterActor''.
  */
class LineWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with Matchers with ImplicitSender with BeforeAndAfterAll with MockitoSugar {

  def this() = this(ActorSystem("LineWriterActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A LineWriterActor" should "handle a WriteAudioData message" in {
    val line = mock[SourceDataLine]
    val dataArray = FileTestHelper.testBytes()
    val data = ByteString(FileTestHelper.TestData)
    val actor = system.actorOf(Props[LineWriterActor]())

    actor ! WriteAudioData(line, data)
    val written = expectMsgType[AudioDataWritten]
    written.chunkLength should be(data.length)
    verify(line).write(dataArray, 0, dataArray.length)
  }

  it should "handle a DrainLine message" in {
    val line = mock[SourceDataLine]
    val actor = system.actorOf(Props[LineWriterActor]())

    actor ! LineWriterActor.DrainLine(line)
    expectMsg(LineWriterActor.LineDrained)
    verify(line).drain()
  }

  it should "measure the playback time" in {
    val line = mock[SourceDataLine]
    val dataArray = FileTestHelper.testBytes()
    val data = ByteString(FileTestHelper.TestData)
    val actor = system.actorOf(Props[LineWriterActor]())
    when(line.write(argEq(dataArray), anyInt(), anyInt())).thenAnswer((_: InvocationOnMock) => {
      Thread.sleep(50)
      dataArray.length
    })

    val startTime = System.nanoTime()
    actor ! WriteAudioData(line, data)
    val written = expectMsgType[AudioDataWritten]
    val endTime = System.nanoTime()
    val duration = endTime - startTime
    written.duration should be <= duration
    written.duration should be >= TimeUnit.MILLISECONDS.toNanos(45)
  }
}
