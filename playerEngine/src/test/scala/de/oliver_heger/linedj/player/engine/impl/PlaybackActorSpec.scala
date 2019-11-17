package de.oliver_heger.linedj.player.engine.impl

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.io.ChannelHandler.ArraySource
import de.oliver_heger.linedj.io.FileReaderActor.{EndOfFile, ReadResult}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.impl.LineWriterActor.WriteAudioData
import de.oliver_heger.linedj.player.engine.impl.PlaybackActor._
import javax.sound.sampled.{AudioFormat, AudioSystem, LineUnavailableException, SourceDataLine}
import org.mockito.Matchers.{eq => eqArg, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

object PlaybackActorSpec {
  /** Constant for the maximum size of the audio buffer. */
  private val AudioBufferSize = 256

  /** Constant for the amount of data required for creating a playback context. */
  private val PlaybackContextLimit = 100

  /** The size of the chunks to be passed to the line writer actor. */
  private val LineChunkSize = 32

  /** A test audio format. */
  private val TestAudioFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1f, 8, 2, 16,
    AudioSystem.NOT_SPECIFIED, true)

  /** The configuration used by tests. */
  private val Config = createConfig()

  /**
    * Creates a test audio source whose properties are derived from the given
    * index value.
    *
    * @param idx       the index
    * @param skipBytes the number of bytes to be skipped at the beginning
    * @param skipTime  the playback time offset
    * @return the test audio source
    */
  private def createSource(idx: Int, skipBytes: Int = 0, skipTime: Int = 0): AudioSource =
    AudioSource(s"audiSource$idx.mp3", 3 * AudioBufferSize + 20 * (idx + 1), skipBytes, skipTime)

  /**
   * Creates a data array with test content and the given length. The array
   * contains the specified byte value in all elements.
    *
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
    *
    * @param byte the byte value for all array elements
   * @param length the length of the array
   * @return the ''ArraySource''
   */
  private def arraySource(byte: Int, length: Int): ArraySource = ReadResult(dataArray(byte,
    length), length)

  /**
    * Creates a configuration object with some default settings.
    *
    * @return the configuration object
    */
  private def createConfig(): PlayerConfig =
    PlayerConfig(inMemoryBufferSize = AudioBufferSize, playbackContextLimit = PlaybackContextLimit,
      actorCreator = (_, _) => null, mediaManagerActor = null)
}

/**
 * Test class for ''PlaybackActor''.
 */
class PlaybackActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
with ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar
with EventTestSupport {

  import PlaybackActorSpec._

  def this() = this(ActorSystem("PlaybackActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
   * Obtains an actor reference. If the specified option is defined, its value
   * is returned. Otherwise, the test actor is used.
    *
    * @param optActor an optional actor reference
   * @return the final actor reference to be used
   */
  private def fetchActorRef(optActor: Option[ActorRef]): ActorRef =
    optActor getOrElse testActor

  /**
    * Creates a ''Props'' object for creating a ''PlaybackActor''. The factory
    * for the line actor and the source actor can be provided optionally.
    *
    * @param optLineWriter the optional line writer actor
    * @param optSource     the optional source actor
    * @param optEventMan   the optional event manager actor test probe
    * @return the ''Props'' object
    */
  private def propsWithMockLineWriter(optLineWriter: Option[ActorRef] = None, optSource:
  Option[ActorRef] = None, optEventMan: Option[TestProbe] = None): Props =
    PlaybackActor(Config, fetchActorRef(optSource), fetchActorRef(optLineWriter),
      optEventMan.getOrElse(TestProbe()).ref)

  /**
   * Creates a playback context factory which creates context objects using a
   * ''SimulatedAudioStream''.
    *
    * @param optLine an optional line mock
   * @param optStreamFactory an optional stream factory
    * @param optFormat an optional audio format to be returned
   * @return the factory
   */
  private def mockPlaybackContextFactory(optLine: Option[SourceDataLine] = None,
                                         optStreamFactory: Option[SimulatedAudioStreamFactory] =
                                         None, optFormat: Option[AudioFormat] = None):
  PlaybackContextFactory = {
    val factory = mock[PlaybackContextFactory]
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenAnswer(new
        Answer[Option[PlaybackContext]] {
      override def answer(invocationOnMock: InvocationOnMock): Option[PlaybackContext] = {
        createPlaybackContextFromMock(optLine, optStreamFactory, optFormat, invocationOnMock)
      }
    })
    factory
  }

  /**
    * Creates a ''PlaybackContext'' from a mock factory that can be used by
    * tests.
    *
    * @param optLine          an optional line mock
    * @param optStreamFactory an optional stream factory
    * @param optFormat        an optional audio format
    * @param invocationOnMock the current mock invocation
    * @return an option with the context
    */
  private def createPlaybackContextFromMock(optLine: Option[SourceDataLine], optStreamFactory:
  Option[SimulatedAudioStreamFactory], optFormat: Option[AudioFormat],
  invocationOnMock: InvocationOnMock): Some[PlaybackContext]
  = {
    val factory = optStreamFactory getOrElse new SimulatedAudioStreamFactory
    val stream = factory createAudioStream invocationOnMock.getArguments()(0)
      .asInstanceOf[InputStream]
    val format = optFormat getOrElse TestAudioFormat
    val context = mock[PlaybackContext]
    when(context.stream).thenReturn(stream)
    when(context.bufferSize).thenReturn(LineChunkSize)
    when(context.line).thenReturn(optLine.getOrElse(mock[SourceDataLine]))
    when(context.format).thenReturn(format)
    Some(context)
  }

  "A PlaybackActor" should "create a correct Props object" in {
    val probeLine = TestProbe()
    val probeEvent = TestProbe()
    val props = PlaybackActor(Config, testActor, probeLine.ref, probeEvent.ref)
    val actor = TestActorRef[PlaybackActor](props)
    actor.underlyingActor shouldBe a[PlaybackActor]
    props.args should be(List(Config, testActor, probeLine.ref, probeEvent.ref))
  }

  it should "request data when it is passed an audio source" in {
    val actor = system.actorOf(propsWithMockLineWriter())
    actor ! createSource(1)
    expectMsg(GetAudioData(AudioBufferSize))
  }

  it should "fire an event when an audio source is started" in {
    val eventMan = TestProbe()
    val source = createSource(1)
    val actor = system.actorOf(propsWithMockLineWriter(optEventMan = Some(eventMan)))

    actor ! source
    expectMsgType[GetAudioData]
    val event = expectEvent[AudioSourceStartedEvent](eventMan)
    event.source should be(source)
  }

  it should "report a protocol violation if too many audio sources are sent" in {
    val actor = system.actorOf(propsWithMockLineWriter())
    actor ! createSource(1)
    expectMsgType[GetAudioData]

    actor ! createSource(2)
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(createSource(2))
    errMsg.errorText should include("AudioSource")
  }

  it should "report a protocol violation if receiving data without asking" in {
    val actor = system.actorOf(propsWithMockLineWriter())

    val dataMsg = arraySource(1, 8)
    actor ! dataMsg
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(dataMsg)
    errMsg.errorText should include("unexpected data")
  }

  it should "receive data until the buffer is full" in {
    val actor = system.actorOf(propsWithMockLineWriter())
    installMockPlaybackContextFactory(actor)

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
    val actor = system.actorOf(propsWithMockLineWriter(optSource = Some(probe.ref)))

    actor ! StartPlayback
    probe.expectMsg(GetAudioSource)
  }

  it should "create a playback context if sufficient data is available" in {
    val factory = mock[PlaybackContextFactory]
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenReturn(None)

    val actor = TestActorRef(propsWithMockLineWriter())
    actor ! AddPlaybackContextFactory(factory)
    val audioSource = createSource(1)
    actor ! audioSource
    expectMsgType[GetAudioData]

    actor receive arraySource(1, PlaybackContextLimit)
    verify(factory).createPlaybackContext(any(classOf[InputStream]), eqArg(audioSource.uri))
    expectMsgType[GetAudioData]
  }

  it should "fetch audio data after the playback context was created if necessary" in {
    val factory = mock[PlaybackContextFactory]
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenAnswer(new
        Answer[Option[PlaybackContext]] {
      override def answer(invocation: InvocationOnMock): Option[PlaybackContext] = {
        val stream = invocation.getArguments.head.asInstanceOf[InputStream]
        stream.read(new Array[Byte](AudioBufferSize - 1)) // read data from stream
        createPlaybackContextFromMock(None, None, None, invocation)
      }
    })

    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(TestProbe().ref)))
    actor ! AddPlaybackContextFactory(factory)
    val audioSource = createSource(1)
    actor ! audioSource
    actor ! StartPlayback
    expectMsgType[GetAudioData]

    actor ! arraySource(1, AudioBufferSize)
    expectMsgType[GetAudioData]
  }

  it should "open the line on a newly created playback context" in {
    val factory = mock[PlaybackContextFactory]
    val line = mock[SourceDataLine]
    val context = PlaybackContext(TestAudioFormat, new ByteArrayInputStream(new Array(1)), line)
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenReturn(Some
    (context))

    val actor = TestActorRef(propsWithMockLineWriter())
    actor ! AddPlaybackContextFactory(factory)
    val audioSource = createSource(1)
    actor ! audioSource
    expectMsgType[GetAudioData]

    actor receive arraySource(1, PlaybackContextLimit)
    verify(line).open(TestAudioFormat)
    verify(line).start()
    expectMsgType[GetAudioData]
  }

  /**
   * Installs a mock playback context factory in the test actor and returns the
   * mock data line used by this factory.
    *
    * @param actor the test actor
   * @return the mock for the current data line
   */
  private def installMockPlaybackContextFactory(actor: ActorRef): SourceDataLine = {
    val line = mock[SourceDataLine]
    actor ! AddPlaybackContextFactory(mockPlaybackContextFactory(Some(line)))
    line
  }

  /**
   * Handles the protocol to send chunks of audio data to the playback actor.
   * This method first expects the request for new audio data.
    *
    * @param actor the playback actor
   * @param audioData the data chunks (as messages) to be sent to the actor
   * @return the reference to the actor
   */
  private def sendAudioData(actor: ActorRef, audioData: Any*): ActorRef = {
    for(data <- audioData) {
      expectMsgType[GetAudioData]
      actor ! data
    }
    actor
  }

  it should "pass data to the line writer actor" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))

    val line = installMockPlaybackContextFactory(actor)
    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, arraySource(1, AudioBufferSize))

    val writeMsg = lineWriter.expectMsgType[WriteAudioData]
    writeMsg.line should be(line)
    writeMsg.data.data should be(dataArray(2, LineChunkSize))
    expectMsgType[GetAudioData]
  }

  it should "request new audio data only if at least a chunk fits into the buffer" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    installMockPlaybackContextFactory(actor)
    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, arraySource(1, AudioBufferSize + LineChunkSize - 2))

    lineWriter.expectMsgType[WriteAudioData]
    // make sure that no GetAudioData request is sent
    actor ! createSource(2)
    expectMsgType[PlaybackProtocolViolation]
  }

  it should "not send a data request if the buffer is full" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    installMockPlaybackContextFactory(actor)
    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    val request = expectMsgType[PlaybackActor.GetAudioData]

    actor ! arraySource(2, request.length)
    lineWriter.expectMsgType[WriteAudioData]
    expectMsgType[PlaybackActor.GetAudioData].length should be > 0
  }

  it should "report a protocol violation if audio data was played without a request" in {
    val actor = system.actorOf(propsWithMockLineWriter())

    actor ! LineWriterActor.AudioDataWritten(42, 0)
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(LineWriterActor.AudioDataWritten)
    errMsg.errorText should include("Unexpected AudioDataWritten")
  }

  it should "send only a single audio data chunk at a time" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, arraySource(1, AudioBufferSize), arraySource(2, 16))

    lineWriter.expectMsgType[WriteAudioData]
    lineWriter.expectNoMessage(1.seconds)
  }

  /**
    * Simulates a line writer actor which receives audio data for playback.
    * The data is collected in an array.
    *
    * @param playbackActor the playback actor
    * @param lineWriter    the line writer actor reference
    * @param expLine       the expected line
    * @param length        the length of audio data to be received
    * @param chunkDuration the duration to be set for a chunk of written data
    * @return an array with the received audio data
    */
  private def gatherPlaybackData(playbackActor: ActorRef, lineWriter: TestProbe, expLine:
  SourceDataLine, length: Int, chunkDuration: Long = 0): Array[Byte] = {
    val stream = new ByteArrayOutputStream(length)
    var currentLength = 0

    while (currentLength < length) {
      val playMsg = lineWriter.expectMsgType[WriteAudioData]
      playMsg.line should be(expLine)
      stream.write(playMsg.data.data, playMsg.data.offset + playMsg.offset,
        playMsg.data.length - playMsg.offset)
      currentLength += playMsg.data.length
      playbackActor.tell(LineWriterActor.AudioDataWritten(playMsg.data.length, chunkDuration),
        lineWriter.ref)
    }
    stream.toByteArray
  }

  /**
    * Simulates a line writer actor which receives audio data for playback and
    * a final message to drain the line. The data passed to the line writer
    * actor is collected and returned as an array.
    *
    * @param playbackActor the playback actor
    * @param lineWriter    the line writer actor reference
    * @param line          the expected line
    * @param sourceSize    the length of audio data to be received
    * @param chunkDuration the duration to be set for a chunk of written data
    * @return an array with the received audio data
    */
  private def gatherPlaybackDataWithLineDrain(playbackActor: ActorRef, lineWriter: TestProbe, line:
  SourceDataLine, sourceSize: Int, chunkDuration: Long = 0): Array[Byte] = {
    val data = gatherPlaybackData(playbackActor, lineWriter, line, sourceSize, chunkDuration)
    lineWriter.expectMsg(LineWriterActor.DrainLine(line))
    playbackActor.tell(LineWriterActor.LineDrained, lineWriter.ref)
    data
  }

  /**
    * Checks whether a full source can be played.
    *
    * @param sourceSize       the size of the source
    * @param optEventMan      an optional test probe for the event actor
    * @param optLineWriter    an optional test probe for the line writer actor
    * @param playbackDuration the playback duration of the source
    * @return the audio source that was played and the test actor
    */
  private def checkPlaybackOfFullSource(sourceSize: Int, optEventMan: Option[TestProbe] = None,
                                        optLineWriter: Option[TestProbe] = None,
                                        playbackDuration: Long = 0):
  (AudioSource, ActorRef) = {
    val lineWriter = optLineWriter getOrElse TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref),
      optEventMan = optEventMan))
    val line = installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    val source = createSource(1)
    actor ! source
    sendAudioData(actor, arraySource(1, sourceSize), EndOfFile(null))

    gatherPlaybackDataWithLineDrain(actor, lineWriter, line, sourceSize,
      playbackDuration) should be(dataArray(2, sourceSize))
    expectMsg(GetAudioSource)
    (source, actor)
  }

  it should "be able to play a complete audio source" in {
    checkPlaybackOfFullSource(PlaybackContextLimit - 10)
  }

  it should "handle a source with a specific size" in {
    checkPlaybackOfFullSource(2 * LineChunkSize)
  }

  it should "fire an event when the current source is completed" in {
    val eventMan = TestProbe()
    val (source, _) = checkPlaybackOfFullSource(PlaybackContextLimit - 10, Some(eventMan))

    expectEvent[AudioSourceStartedEvent](eventMan)
    val event = expectEvent[AudioSourceFinishedEvent](eventMan)
    event.source should be(source)
  }

  it should "handle a source with no data" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val line = installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor,  EndOfFile(null))
    expectMsg(GetAudioSource)
    verifyZeroInteractions(line)
  }

  it should "not play audio data if the in-memory buffer is almost empty" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val line = installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, arraySource(1, PlaybackContextLimit))
    gatherPlaybackData(actor, lineWriter, line, PlaybackContextLimit - LineChunkSize)
    expectMsgType[GetAudioData]
    lineWriter.expectNoMessage(100.milliseconds)
  }

  it should "generate playback progress events" in {
    val lineWriter = TestProbe()
    val eventMan = TestProbe()
    val Chunks = 5
    val SkipTime = 22
    val actor = system.actorOf(PlaybackActor(Config.copy(inMemoryBufferSize = 10 * LineChunkSize),
      testActor, lineWriter.ref, eventMan.ref))
    val line = installMockPlaybackContextFactory(actor)
    val source = createSource(1, skipTime = SkipTime)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! source
    val audioData = (1 to Chunks) map (i => arraySource(i.toByte, LineChunkSize))
    sendAudioData(actor, audioData: _*)
    expectEvent[AudioSourceStartedEvent](eventMan)
    gatherPlaybackData(actor, lineWriter, line, Chunks * LineChunkSize,
      TimeUnit.MILLISECONDS.toNanos(250))
    val event = expectEvent[PlaybackProgressEvent](eventMan)
    event.bytesProcessed should be((Chunks - 1) * LineChunkSize)
    event.playbackTime should be(SkipTime + 1)
    event.currentSource should be(source)

    sendAudioData(actor, arraySource(8, LineChunkSize))
    gatherPlaybackData(actor, lineWriter, line, LineChunkSize,
      TimeUnit.MILLISECONDS.toNanos(2250))
    val event2 = expectEvent[PlaybackProgressEvent](eventMan)
    event2.bytesProcessed should be((Chunks + 1) * LineChunkSize)
    event2.playbackTime should be(SkipTime + 3)
    event2.currentSource should be(source)
    expectMsgType[GetAudioData]
  }

  it should "determine the playback time from the audio format's properties" in {
    val lineWriter = TestProbe()
    val eventMan = TestProbe()
    val Chunks = 4
    val SkipTime = 42
    val actor = system.actorOf(PlaybackActor(Config.copy(inMemoryBufferSize = 10 * LineChunkSize),
      testActor, lineWriter.ref, eventMan.ref))
    val line = mock[SourceDataLine]
    val format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1f, 8, 2, 16,
      4, true)
    val factory = mockPlaybackContextFactory(optLine = Some(line), optFormat = Some(format))
    actor ! AddPlaybackContextFactory(factory)
    val source = createSource(1, skipTime = SkipTime)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! source
    val audioData = (1 to Chunks) map (i => arraySource(i.toByte, LineChunkSize))
    sendAudioData(actor, audioData: _*)
    expectEvent[AudioSourceStartedEvent](eventMan)
    gatherPlaybackData(actor, lineWriter, line, Chunks * LineChunkSize,
      TimeUnit.SECONDS.toNanos(2))
    expectMsgType[GetAudioData]
    val event = expectEvent[PlaybackProgressEvent](eventMan)
    event.playbackTime should be(1)
  }

  it should "reset progress counters when playback of a new source starts" in {
    val eventMan = TestProbe()
    val lineWriter = TestProbe()
    val (_, actor) = checkPlaybackOfFullSource(LineChunkSize, Some(eventMan),
      Some(lineWriter), TimeUnit.SECONDS.toNanos(2))
    expectEvent[AudioSourceStartedEvent](eventMan)
    expectEvent[PlaybackProgressEvent](eventMan)
    expectEvent[AudioSourceFinishedEvent](eventMan)
    val source = createSource(2)

    actor ! source
    sendAudioData(actor, arraySource(12, LineChunkSize), EndOfFile(null))
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, TimeUnit.SECONDS.toNanos(1)),
      lineWriter.ref)
    expectEvent[AudioSourceStartedEvent](eventMan)
    val event = expectEvent[PlaybackProgressEvent](eventMan)
    event.bytesProcessed should be(LineChunkSize)
    event.playbackTime should be(1)
    event.currentSource should be(source)
  }

  it should "report a protocol error when receiving an unexpected EoF message" in {
    val actor = system.actorOf(propsWithMockLineWriter())

    val eofMsg = EndOfFile(null)
    actor ! eofMsg
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(eofMsg)
    errMsg.errorText should include("unexpected data")
  }

  it should "skip a chunk according to the source's skip property" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val line = installMockPlaybackContextFactory(actor)
    val SkipSize = LineChunkSize

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1, skipBytes = SkipSize)
    sendAudioData(actor, arraySource(1, SkipSize), arraySource(2, AudioBufferSize), EndOfFile(null))

    gatherPlaybackDataWithLineDrain(actor, lineWriter, line, AudioBufferSize) should be(dataArray
    (3, AudioBufferSize))
    expectMsg(GetAudioSource)
  }

  it should "skip a chunk partially according to the source's skip property" in {
    val lineWriter = TestProbe()
    val srcActor = system.actorOf(Props(classOf[SimulatedSourceActor],
      List(createSource(1, skipBytes = 7)),
      List(arraySource(1, 8), arraySource(2, AudioBufferSize), EndOfFile(null))))
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref),
      optSource = Some(srcActor)))
    val line = installMockPlaybackContextFactory(actor)
    actor ! StartPlayback

    val audioData = gatherPlaybackData(actor, lineWriter, line, AudioBufferSize + 1)
    val buffer = ArrayBuffer.empty[Byte]
    buffer += 2
    buffer ++= dataArray(3, AudioBufferSize)
    audioData should be (buffer.toArray)
  }

  /**
    * Helper method for checking whether the current source can be skipped.
    *
    * @param src the source to be used
    */
  private def checkSkipOfCurrentSource(src: AudioSource): Unit = {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val line = installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! src
    sendAudioData(actor, arraySource(1, LineChunkSize), arraySource(2, PlaybackContextLimit))
    val playMsg = lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    playMsg.line should be(line)
    playMsg.data.data should be(dataArray(2, LineChunkSize))

    actor ! SkipSource
    sendAudioData(actor, arraySource(3, LineChunkSize), EndOfFile(null))
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    expectMsg(GetAudioSource)
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    lineWriter.expectMsgType[PlaybackProtocolViolation]
  }

  it should "allow skipping playback of the current source" in {
    checkSkipOfCurrentSource(createSource(1))
  }

  it should "allow skipping a source of infinite length" in {
    checkSkipOfCurrentSource(AudioSource.infinite("some infinite audio source"))
  }

  it should "handle an EoF message for an infinite source" in {
    val lineWriter = TestProbe()
    val actor = TestActorRef[PlaybackActor](propsWithMockLineWriter(optLineWriter =
      Some(lineWriter.ref)))
    installMockPlaybackContextFactory(actor)
    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! AudioSource.infinite("src://infinite")

    sendAudioData(actor, arraySource(1, PlaybackContextLimit+1))
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    expectMsgType[GetAudioData]
    actor ! EndOfFile(null)
    expectMsg(GetAudioSource)
    actor receive arraySource(2, PlaybackContextLimit + 1)
  }

  /**
    * Helper method for testing a failed creation of a playback context. It is
    * tested whether the current source is skipped afterwards.
    *
    * @param ctx the playback context to be returned by the factory
    * @param evMan an optional event manager probe
    * @return the mock playback context factory
    */
  private def checkSkipAfterFailedPlaybackContextCreation(ctx: Option[PlaybackContext],
                                                          evMan: Option[TestProbe] = None):
  PlaybackContextFactory = {
    val mockContextFactory = mock[PlaybackContextFactory]
    when(mockContextFactory.createPlaybackContext(any(classOf[InputStream]), anyString()))
      .thenReturn(ctx)
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref),
      optEventMan = evMan))
    actor ! AddPlaybackContextFactory(mockContextFactory)
    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, arraySource(1, AudioBufferSize), arraySource(2, PlaybackContextLimit),
      EndOfFile(null))

    expectMsg(GetAudioSource)
    actor.tell(LineWriterActor.AudioDataWritten(1, 0), lineWriter.ref)
    lineWriter.expectMsgType[PlaybackProtocolViolation]
    mockContextFactory
  }

  it should "skip a source if no playback context can be created" in {
    checkSkipAfterFailedPlaybackContextCreation(None)
  }

  it should "skip a source if the line cannot be opened" in {
    val line = mock[SourceDataLine]
    doThrow(new LineUnavailableException).when(line).open(any(classOf[AudioFormat]))
    checkSkipAfterFailedPlaybackContextCreation(Some(PlaybackContext(TestAudioFormat, null, line)))
  }

  it should "generate a failure event if no playback context can be created" in {
    val eventMan = TestProbe()
    checkSkipAfterFailedPlaybackContextCreation(ctx = None, evMan = Some(eventMan))
    eventMan.expectMsgType[AudioSourceStartedEvent]

    val event = expectEvent[PlaybackContextCreationFailedEvent](eventMan)
    event.source should be(createSource(1))
  }

  it should "skip an infinite source if no playback context can be created" in {
    val mockContextFactory = mock[PlaybackContextFactory]
    when(mockContextFactory.createPlaybackContext(any(classOf[InputStream]), anyString()))
      .thenReturn(None)
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    actor ! AddPlaybackContextFactory(mockContextFactory)
    actor ! StartPlayback
    expectMsg(GetAudioSource)

    actor ! AudioSource.infinite("some infinite source URI")
    sendAudioData(actor, arraySource(1, AudioBufferSize))
    actor ! StartPlayback
    expectMsg(GetAudioSource)
  }

  it should "try only once to create a playback context for a source" in {
    val factory = checkSkipAfterFailedPlaybackContextCreation(None)

    verify(factory).createPlaybackContext(any(classOf[InputStream]), anyString())
  }

  it should "ignore a skip message if no source is currently open" in {
    val actor = TestActorRef[PlaybackActor](propsWithMockLineWriter())

    actor receive PlaybackActor.SkipSource
  }

  it should "allow stopping playback" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val contextFactory = mockPlaybackContextFactory()
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(25)
    actor ! StopPlayback
    sendAudioData(actor, arraySource(1, PlaybackContextLimit))
    expectMsgType[GetAudioData]
    actor.tell(LineWriterActor.AudioDataWritten(1, 0), lineWriter.ref)
    lineWriter.expectMsgType[PlaybackProtocolViolation]
    verify(contextFactory).createPlaybackContext(any(classOf[InputStream]), anyString())
  }

  it should "allow skipping a source even if playback is not enabled" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(25)
    actor ! StopPlayback
    sendAudioData(actor, arraySource(1, AudioBufferSize))

    actor ! SkipSource
    sendAudioData(actor, arraySource(3, AudioBufferSize), EndOfFile(null))
    expectMsg(GetAudioSource)
    actor.tell(LineWriterActor.AudioDataWritten(1, 0), lineWriter.ref)
    lineWriter.expectMsgType[PlaybackProtocolViolation]
  }

  /**
    * Checks that a playback context has been closed.
    *
    * @param line          the data line
    * @param streamFactory the stream factory
    * @return the time when the playback context was closed
    */
  private def assertPlaybackContextClosed(line: SourceDataLine, streamFactory:
  SimulatedAudioStreamFactory): Long = {
    val closingTime = streamFactory.latestStream.closedAt
    closingTime should be > 0L
    verify(line).close()
    closingTime
  }

  it should "close the playback context when a source is complete" in {
    val line = mock[SourceDataLine]
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val streamFactory = new SimulatedAudioStreamFactory
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory),
      optLine = Some(line))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(25)
    sendAudioData(actor, arraySource(1, LineChunkSize), EndOfFile(null))
    gatherPlaybackData(actor, lineWriter, line, LineChunkSize)
    lineWriter.expectMsg(LineWriterActor.DrainLine(line))
    val drainTime = System.nanoTime()
    actor.tell(LineWriterActor.LineDrained, lineWriter.ref)
    expectMsg(GetAudioSource)

    assertPlaybackContextClosed(line, streamFactory) should be > drainTime
  }

  it should "handle an exception when reading from the audio stream" in {
    val lineWriter = TestProbe()
    val eventMan = TestProbe()
    val source = createSource(1)
    val streamFactory = mock[SimulatedAudioStreamFactory]
    val audioStream = mock[InputStream]
    when(audioStream.read(any(classOf[Array[Byte]]))).thenReturn(LineChunkSize)
      .thenThrow(new ArrayIndexOutOfBoundsException).thenReturn(LineChunkSize)
    when(streamFactory.createAudioStream(any(classOf[InputStream]))).thenReturn(audioStream)
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref),
      optEventMan = Some(eventMan)))
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! source
    expectEvent[AudioSourceStartedEvent](eventMan)
    sendAudioData(actor, arraySource(1, PlaybackContextLimit))
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    sendAudioData(actor, arraySource(2, LineChunkSize), EndOfFile(null))
    lineWriter.expectNoMessage(100.millis)
    expectMsg(GetAudioSource)
    expectEvent[PlaybackErrorEvent](eventMan).source should be(source)
    expectEvent[AudioSourceFinishedEvent](eventMan)
  }

  it should "handle an exception of the audio stream when it already has been completed" in {
    val lineWriter = TestProbe()
    val source = createSource(1)
    val streamFactory = mock[SimulatedAudioStreamFactory]
    val audioStream = mock[InputStream]
    when(audioStream.read(any(classOf[Array[Byte]]))).thenReturn(LineChunkSize / 2)
      .thenThrow(new ArrayIndexOutOfBoundsException)
    when(streamFactory.createAudioStream(any(classOf[InputStream]))).thenReturn(audioStream)
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! source
    sendAudioData(actor, arraySource(1, LineChunkSize), EndOfFile(null))
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    lineWriter.expectNoMessage(100.millis)
    expectMsg(GetAudioSource)
  }

  it should "ignore empty read results for infinite sources" in {
    val lineWriter = TestProbe()
    val streamFactory = mock[SimulatedAudioStreamFactory]
    val audioStream = mock[InputStream]
    when(audioStream.read(any(classOf[Array[Byte]]))).thenReturn(0, 32)
    when(streamFactory.createAudioStream(any(classOf[InputStream]))).thenReturn(audioStream)
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! AudioSource.infinite("infiniteURI")
    sendAudioData(actor, arraySource(1, PlaybackContextLimit), arraySource(2, 32))
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    expectMsgType[GetAudioData]
  }

  it should "not terminate an infinite source on a smaller read result" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! AudioSource.infinite("src://infinite.org")
    sendAudioData(actor, arraySource(1, AudioBufferSize))
    expectMsgType[GetAudioData]
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize - 1, 0), lineWriter.ref)
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
  }

  it should "flush the in-memory buffer if playback hangs" in {
    val lineWriter = TestProbe()
    val streamFactory = mock[SimulatedAudioStreamFactory]
    val audioStream = mock[InputStream]
    when(audioStream.read(any(classOf[Array[Byte]]))).thenReturn(0, 32)
    when(streamFactory.createAudioStream(any(classOf[InputStream]))).thenReturn(audioStream)
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! AudioSource.infinite("infiniteURI")
    sendAudioData(actor, arraySource(1, AudioBufferSize), arraySource(2, AudioBufferSize))
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
  }

  it should "skip the current finite source if no audio data can be read" in {
    val lineWriter = TestProbe()
    val eventMan = TestProbe()
    val streamFactory = mock[SimulatedAudioStreamFactory]
    val audioStream = mock[InputStream]
    when(audioStream.read(any(classOf[Array[Byte]]))).thenReturn(0)
    when(streamFactory.createAudioStream(any(classOf[InputStream]))).thenReturn(audioStream)
    val source = createSource(1)
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref),
      optEventMan = Some(eventMan)))
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! source
    sendAudioData(actor, arraySource(1, AudioBufferSize), arraySource(2, AudioBufferSize),
      arraySource(3, AudioBufferSize), EndOfFile(null))
    expectMsg(GetAudioSource)
    actor.tell(LineWriterActor.AudioDataWritten(1, 0), lineWriter.ref)
    lineWriter.expectMsgType[PlaybackProtocolViolation]
    expectEvent[AudioSourceStartedEvent](eventMan)
    expectEvent[PlaybackErrorEvent](eventMan).source should be(source)
    expectEvent[AudioSourceFinishedEvent](eventMan).source should be(source)
  }

  it should "ignore exceptions when closing a playback context" in {
    val stream = mock[InputStream]
    val line = mock[SourceDataLine]
    doThrow(new IOException()).when(stream).close()
    val context = PlaybackContext(TestAudioFormat, stream, line)
    val dataSource = TestProbe()
    val actor = TestActorRef[PlaybackActor](propsWithMockLineWriter(optSource = Some(dataSource.ref)))

    actor.underlyingActor.closePlaybackContext(context)
    verify(stream).close()
    verify(line).close()
  }

  it should "handle a close request if there is no playback context" in {
    val actor = TestActorRef[PlaybackActor](propsWithMockLineWriter())
    installMockPlaybackContextFactory(actor)
    actor ! StartPlayback
    expectMsg(GetAudioSource)

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    actor.underlyingActor should not be 'playing
  }

  it should "ignore messages after receiving a close request" in {
    val actor = system.actorOf(propsWithMockLineWriter())
    installMockPlaybackContextFactory(actor)

    actor ! CloseRequest
    expectMsgType[CloseAck]
    actor ! StartPlayback
    expectNoMessage(200.milliseconds)
  }

  it should "handle a close request if a playback context is active" in {
    val line = mock[SourceDataLine]
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val streamFactory = new SimulatedAudioStreamFactory
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory),
      optLine = Some(line))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(2)
    sendAudioData(actor, arraySource(1, PlaybackContextLimit))
    gatherPlaybackData(actor, lineWriter, line, PlaybackContextLimit - LineChunkSize)
    expectMsgType[GetAudioData]

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    assertPlaybackContextClosed(line, streamFactory)
  }

  it should "handle a close request while audio data is currently played" in {
    val line = mock[SourceDataLine]
    val lineWriter = TestProbe()
    val actor = TestActorRef[PlaybackActor](propsWithMockLineWriter(
      optLineWriter = Some(lineWriter.ref)))
    val streamFactory = new SimulatedAudioStreamFactory
    val contextFactory = mockPlaybackContextFactory(optStreamFactory = Some(streamFactory),
      optLine = Some(line))
    actor ! AddPlaybackContextFactory(contextFactory)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, arraySource(1, PlaybackContextLimit))
    expectMsgType[GetAudioData]
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]

    actor ! CloseRequest
    actor receive createSource(4)
    verify(line, never()).close()
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    expectMsg(CloseAck(actor))
    assertPlaybackContextClosed(line, streamFactory)
  }

  it should "support removing a playback context factory" in {
    val factory1 = mock[PlaybackContextFactory]
    val factory2 = mock[PlaybackContextFactory]
    val actor = TestActorRef[PlaybackActor](propsWithMockLineWriter())
    actor receive PlaybackActor.AddPlaybackContextFactory(factory1)
    actor receive PlaybackActor.AddPlaybackContextFactory(factory2)

    actor receive PlaybackActor.RemovePlaybackContextFactory(factory1)
    actor.underlyingActor.combinedPlaybackContextFactory.subFactories should contain only factory2
  }
}

/**
 * A simple stream class which should simulate an audio stream. This stream
 * simply reads from a wrapped stream. The byte that was read is incremented by
 * one to simulate a modification.
  *
  * @param wrappedStream the wrapped input stream
 */
private class SimulatedAudioStream(val wrappedStream: InputStream) extends InputStream {
  /** Records the time when this stream was closed. */
  var closedAt = 0L

  override def read(): Int = {
    val result = wrappedStream.read()
    if (result < 0) -1
    else result + 1
  }

  override def close(): Unit = {
    closedAt = System.nanoTime()
    super.close()
  }
}

/**
 * A simple class that allows creating a simulated audio stream and querying
 * the created instance.
 */
private class SimulatedAudioStreamFactory {
  /** The latest stream created by the factory. */
  var latestStream: SimulatedAudioStream = _

  /**
   * Creates a new simulated audio stream which wraps the passed in stream.
    *
    * @param wrapped the underlying stream
   * @return the simulated audio stream
   */
  def createAudioStream(wrapped: InputStream): InputStream = {
    latestStream = new SimulatedAudioStream(wrapped)
    latestStream
  }
}

/**
  * An actor class simulating a data source for a playback actor. This actor
  * implementation reacts on ''GetAudioSource'' and ''GetAudioData'' messages.
  * The responses on these messages are specified by constructor arguments.
  *
  * @param sources a list with audio sources to be returned
  * @param data    a list with messages to be sent for data requests
  */
private class SimulatedSourceActor(sources: List[AudioSource], data: List[Any]) extends Actor {
  /** The current list of audio sources. */
  private var currentSources = sources

  /** The current list of data messages. */
  private var currentData = data

  override def receive: Receive = {
    case GetAudioSource =>
      if(currentSources.nonEmpty) {
        sender ! currentSources.head
        currentSources = currentSources.tail
      }

    case GetAudioData(_) =>
      if(currentData.nonEmpty) {
        sender ! currentData.head
        currentData = currentData.tail
      }
  }
}
