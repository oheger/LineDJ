package de.oliver_heger.splaya.playback

import java.util
import javax.sound.sampled.SourceDataLine

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.playback.LineWriterActor.{AudioDataWritten, WriteAudioData}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
 * Test class for ''LineWriterActor''.
 */
class LineWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
with Matchers with ImplicitSender with BeforeAndAfterAll with MockitoSugar {

  def this() = this(ActorSystem("LineWriterActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
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
    expectMsg(AudioDataWritten)
    verify(line).write(dataArray, 4, 42)
  }
}
