package de.oliver_heger.splaya.playback

import java.io.{ByteArrayOutputStream, InputStream}
import java.util
import javax.sound.sampled.SourceDataLine

import akka.actor.{ActorContext, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadResult}
import de.oliver_heger.splaya.playback.LineWriterActor.WriteAudioData
import de.oliver_heger.splaya.playback.PlaybackActor._
import org.mockito.Matchers.{eq => eqArg, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object PlaybackActorSpec {
  /** Constant for the maximum size of the audio buffer. */
  private val AudioBufferSize = 256

  /** Constant for the amount of data required for creating a playback context. */
  private val PlaybackContextLimit = 100

  /** The size of the chunks to be passed to the line writer actor. */
  private val LineChunkSize = 32

  /**
   * Creates a test audio source whose properties are derived from the given
   * index value.
   * @param idx the index
   * @return the test audio source
   */
  private def createSource(idx: Int): AudioSource =
    AudioSource(s"audiSource$idx.mp3", idx, 20 * (idx + 1), 0, 0)

  /**
   * Creates a data array with test content and the given length. The array
   * contains the specified byte value in all elements.
   * @param byte the byte value for all array elements
   * @param length the length of the array
   * @return the array
   */
  private def dataArray(byte: Int, length: Int): Array[Byte] = {
    val array = new Array[Byte](length)
    util.Arrays.fill(array, byte.toByte)
    array
  }

  /**
   * Creates an ''ArraySource'' object with a test array as content. The array
   * contains a single value in all its elements.
   * @param byte the byte value for all array elements
   * @param length the length of the array
   * @return the ''ArraySource''
   */
  private def arraySource(byte: Int, length: Int): ArraySource = ReadResult(dataArray(byte,
    length), length)
}

/**
 * Test class for ''PlaybackActor''.
 */
class PlaybackActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
with ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import de.oliver_heger.splaya.playback.PlaybackActorSpec._

  def this() = this(ActorSystem("PlaybackActorSpec",
    ConfigFactory.parseString( s"""
                                  |splaya {
                                  | playback {
                                  |   audioBufferSize = ${PlaybackActorSpec.AudioBufferSize}
        |   playbackContextLimit = ${PlaybackActorSpec.PlaybackContextLimit}
        | }
        |}
    """.stripMargin)))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Obtains an actor reference. If the specified option is defined, its value
   * is returned. Otherwise, the test actor is used.
   * @param optActor an optional actor reference
   * @return the final actor reference to be used
   */
  private def fetchActorRef(optActor: Option[ActorRef]): ActorRef =
    optActor getOrElse testActor

  /**
   * Creates a dummy ''LineWriterActorFactory'' which returns a specific actor.
   * Either the actor is directly specified or the test actor is used.
   * @param optActor an option for the actor to be returned by the factory
   * @return the line writer actor factory
   */
  private def createLineWriterActorFactory(optActor: Option[ActorRef] = None):
  LineWriterActorFactory =
    new LineWriterActorFactory {
      /**
       * Creates a new line writer actor using the specified ''ActorContext''.
       * @param context the ''ActorContext''
       * @return the new line writer actor
       */
      override def createLineWriterActor(context: ActorContext): ActorRef = fetchActorRef(optActor)
    }

  /**
   * Creates a ''Props'' object for creating a ''PlaybackActor''. The factory
   * for the line actor and the source actor can be provided optionally.
   * @param optFactory the optional line writer factory
   * @param optSource the optional source actor
   * @return the ''Props'' object
   */
  private def propsWithMockFactory(optFactory: Option[LineWriterActorFactory] = None, optSource:
  Option[ActorRef] = None): Props =
    Props(classOf[PlaybackActor], optFactory getOrElse createLineWriterActorFactory(),
      fetchActorRef(optSource))

  /**
   * Creates a playback context factory which creates context objects using a
   * ''SimulatedAudioStream''.
   * @param optLine an optional line mock
   * @return the factory
   */
  private def mockPlaybackContextFactory(optLine: Option[SourceDataLine] = None):
  PlaybackContextFactory = {
    val factory = mock[PlaybackContextFactory]
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenAnswer(new
        Answer[Option[PlaybackContext]] {
      override def answer(invocationOnMock: InvocationOnMock): Option[PlaybackContext] = {
        val stream = new SimulatedAudioStream(invocationOnMock.getArguments()(0)
          .asInstanceOf[InputStream])
        val context = mock[PlaybackContext]
        when(context.stream).thenReturn(stream)
        when(context.bufferSize).thenReturn(LineChunkSize)
        when(context.line).thenReturn(optLine.getOrElse(mock[SourceDataLine]))
        Some(context)
      }
    })
    factory
  }

  "A PlaybackActor" should "create a correct LineWriterActorFactory" in {
    val actor = TestActorRef[PlaybackActor](Props(classOf[PlaybackActor], testActor))
    actor.underlyingActor.lineWriterActorFactory shouldBe a[LineWriterActorFactory]
  }

  it should "request data when it is passed an audio source" in {
    val actor = system.actorOf(propsWithMockFactory())
    actor ! createSource(1)
    expectMsg(GetAudioData(AudioBufferSize))
  }

  it should "report a protocol violation if too many audio sources are sent" in {
    val actor = system.actorOf(propsWithMockFactory())
    actor ! createSource(1)
    expectMsgType[GetAudioData]

    actor ! createSource(2)
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(createSource(2))
    errMsg.errorText should include("AudioSource")
  }

  it should "report a protocol violation if receiving data without asking" in {
    val actor = system.actorOf(propsWithMockFactory())

    val dataMsg = arraySource(1, 8)
    actor ! dataMsg
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(dataMsg)
    errMsg.errorText should include("unexpected data")
  }

  it should "receive data until the buffer is full" in {
    val actor = system.actorOf(propsWithMockFactory())

    actor ! createSource(1)
    expectMsgType[GetAudioData]
    actor ! arraySource(1, 64)
    expectMsg(GetAudioData(AudioBufferSize - 64))
    actor ! arraySource(2, 128)
    expectMsg(GetAudioData(AudioBufferSize - 64 - 128))
    actor ! arraySource(3, 128)

    actor ! arraySource(4, 8)
    expectMsgType[PlaybackProtocolViolation]
  }

  it should "request an audio source when started initially" in {
    val probe = TestProbe()
    val actor = system.actorOf(propsWithMockFactory(optSource = Some(probe.ref)))

    actor ! StartPlayback
    probe.expectMsg(GetAudioSource)
  }

  it should "create a playback context if sufficient data is available" in {
    val factory = mock[PlaybackContextFactory]
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenReturn(None)

    val actor = TestActorRef(propsWithMockFactory())
    actor ! AddPlaybackContextFactory(factory)
    val audioSource = createSource(1)
    actor ! audioSource
    expectMsgType[GetAudioData]

    actor receive arraySource(1, PlaybackContextLimit)
    verify(factory).createPlaybackContext(any(classOf[InputStream]), eqArg(audioSource.uri))
    expectMsgType[GetAudioData]
  }

  /**
   * Installs a mock playback context factory in the test actor and returns the
   * mock data line used by this factory.
   * @param actor the test actor
   * @return the mock for the current data line
   */
  private def installMockPlaybackContextFactory(actor: ActorRef): SourceDataLine = {
    val line = mock[SourceDataLine]
    actor ! AddPlaybackContextFactory(mockPlaybackContextFactory(Some(line)))
    line
  }

  it should "pass data to the line writer actor" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockFactory(optFactory = Some
      (createLineWriterActorFactory(Some(lineWriter.ref)))))

    val line = installMockPlaybackContextFactory(actor)
    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    expectMsgType[GetAudioData]
    actor ! arraySource(1, AudioBufferSize)

    val writeMsg = lineWriter.expectMsgType[WriteAudioData]
    writeMsg.line should be(line)
    writeMsg.data.data should be(dataArray(2, LineChunkSize))
    expectMsgType[GetAudioData]
  }

  it should "report a protocol violation if audio data was played without a request" in {
    val actor = system.actorOf(propsWithMockFactory())

    actor ! LineWriterActor.AudioDataWritten
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(LineWriterActor.AudioDataWritten)
    errMsg.errorText should include("Unexpected AudioDataWritten")
  }

  it should "send only a single audio data chunk at a time" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockFactory(optFactory = Some
      (createLineWriterActorFactory(Some(lineWriter.ref)))))
    installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    expectMsgType[GetAudioData]
    actor ! arraySource(1, AudioBufferSize)
    expectMsgType[GetAudioData]
    actor ! arraySource(2, 16)
    expectMsgType[GetAudioData]

    lineWriter.expectMsgType[WriteAudioData]
    lineWriter.expectNoMsg(1.seconds)
  }

  /**
   * Simulates a line writer actor which receives audio data for playback.
   * The data is collected in an array.
   * @param playbackActor the playback actor
   * @param lineWriter the line writer actor reference
   * @param expLine the expected line
   * @param length the length of audio data to be received
   * @return an array with the received audio data
   */
  private def gatherPlaybackData(playbackActor: ActorRef, lineWriter: TestProbe, expLine:
  SourceDataLine, length: Int): Array[Byte] = {
    val stream = new ByteArrayOutputStream(length)
    var currentLength = 0

    while (currentLength < length) {
      val playMsg = lineWriter.expectMsgType[WriteAudioData]
      playMsg.line should be(expLine)
      stream.write(playMsg.data.data, playMsg.data.offset, playMsg.data.length)
      currentLength += playMsg.data.length
      playbackActor.tell(LineWriterActor.AudioDataWritten, lineWriter.ref)
    }
    stream.toByteArray
  }

  /**
   * Checks whether a full source can be played.
   * @param sourceSize the size of the surce
   */
  private def checkPlaybackOfFullSource(sourceSize: Int): Unit = {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockFactory(optFactory = Some
      (createLineWriterActorFactory(Some(lineWriter.ref)))))
    val line = installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    expectMsgType[GetAudioData]
    actor ! arraySource(1, sourceSize)
    expectMsgType[GetAudioData]
    actor ! EndOfFile(null)

    gatherPlaybackData(actor, lineWriter, line, sourceSize) should be(dataArray(2, sourceSize))
    lineWriter.expectNoMsg(1.seconds)
    expectMsg(GetAudioSource)
  }

  it should "be able to play a complete audio source" in {
    checkPlaybackOfFullSource(PlaybackContextLimit - 10)
  }

  it should "handle a source with a specific size" in {
    checkPlaybackOfFullSource(2 * LineChunkSize)
  }

  it should "report a protocol error when receiving an unexpected EoF message" in {
    val actor = system.actorOf(propsWithMockFactory())

    val eofMsg = EndOfFile(null)
    actor ! eofMsg
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(eofMsg)
    errMsg.errorText should include("unexpected data")
  }
}

/**
 * A simple stream class which should simulate an audio stream. This stream
 * simply reads from a wrapped stream. The byte that was read is incremented by
 * one to simulate a modification.
 * @param wrappedStream the wrapped input stream
 */
private class SimulatedAudioStream(val wrappedStream: InputStream) extends InputStream {
  override def read(): Int = {
    val result = wrappedStream.read()
    if (result < 0) -1
    else result + 1
  }
}
