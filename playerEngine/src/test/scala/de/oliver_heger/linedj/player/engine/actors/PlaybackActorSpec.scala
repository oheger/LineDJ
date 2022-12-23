package de.oliver_heger.linedj.player.engine.actors

import akka.actor.testkit.typed.scaladsl.ActorTestKit

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.actor.testkit.typed.scaladsl
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.actors.LineWriterActor.WriteAudioData
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.PlaybackActor._

import javax.sound.sampled.{AudioFormat, AudioSystem, LineUnavailableException, SourceDataLine}
import org.mockito.ArgumentMatchers.{eq => eqArg, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
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
    * contains some test data that is impacted by the given parameters.
    *
    * @param length    the length of the array
    * @param offset    the offset into the test array
    * @param increment a value to increment each byte
    * @return the array
    */
  private def dataArray(length: Int, offset: Int = 0, increment: Byte = 0): Array[Byte] = {
    val array = FileTestHelper.testBytes().slice(offset, offset + length)
    array map (b => (b + increment).toByte)
  }

  /**
    * Creates a ''BufferDataResult'' object with test data as content.
    *
    * @param data the array with test data
    * @return the ''BufferDataResult''
    */
  private def bufferResult(data: Array[Byte]) =
    BufferDataResult(ByteString(data))

  /**
    * Creates a configuration object with some default settings.
    *
    * @return the configuration object
    */
  private def createConfig(): PlayerConfig =
    PlayerConfig(inMemoryBufferSize = AudioBufferSize, playbackContextLimit = PlaybackContextLimit,
      actorCreator = null, mediaManagerActor = null)
}

/**
  * Test class for ''PlaybackActor''.
  */
class PlaybackActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar
  with EventTestSupport[PlayerEvent] {

  import PlaybackActorSpec._

  def this() = this(ActorSystem("PlaybackActorSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
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
  Option[ActorRef] = None, optEventMan: Option[scaladsl.TestProbe[PlayerEvent]] = None): Props =
    PlaybackActor(Config, fetchActorRef(optSource), fetchActorRef(optLineWriter),
      optEventMan.getOrElse(testKit.createTestProbe[PlayerEvent]()).ref)

  /**
    * Creates a playback context factory which creates context objects using a
    * ''SimulatedAudioStream''.
    *
    * @param optLine          an optional line mock
    * @param optStreamFactory an optional stream factory
    * @param optFormat        an optional audio format to be returned
    * @return the factory
    */
  private def mockPlaybackContextFactory(optLine: Option[SourceDataLine] = None,
                                         optStreamFactory: Option[SimulatedAudioStreamFactory] =
                                         None, optFormat: Option[AudioFormat] = None):
  PlaybackContextFactory = {
    val factory = mock[PlaybackContextFactory]
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString()))
      .thenAnswer((invocationOnMock: InvocationOnMock) => {
        createPlaybackContextFromMock(optLine, optStreamFactory, optFormat, invocationOnMock)
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
    val probeEvent = testKit.createTestProbe[PlayerEvent]()
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
    val eventMan = testKit.createTestProbe[PlayerEvent]()
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

    val dataMsg = bufferResult(dataArray(8))
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
    actor ! bufferResult(dataArray(64))
    expectMsg(GetAudioData(AudioBufferSize - 64))
    actor ! bufferResult(dataArray(128))
    expectMsg(GetAudioData(AudioBufferSize - 64 - 128))
    actor ! bufferResult(dataArray(128))

    actor ! bufferResult(dataArray(8))
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

    actor receive bufferResult(dataArray(PlaybackContextLimit))
    verify(factory).createPlaybackContext(any(classOf[InputStream]), eqArg(audioSource.uri))
    expectMsgType[GetAudioData]
  }

  it should "fetch audio data after the playback context was created if necessary" in {
    val factory = mock[PlaybackContextFactory]
    when(factory.createPlaybackContext(any(classOf[InputStream]), anyString()))
      .thenAnswer((invocation: InvocationOnMock) => {
        val stream = invocation.getArguments.head.asInstanceOf[InputStream]
        stream.read(new Array[Byte](AudioBufferSize - 1)) // read data from stream
        createPlaybackContextFromMock(None, None, None, invocation)
      })

    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(TestProbe().ref)))
    actor ! AddPlaybackContextFactory(factory)
    val audioSource = createSource(1)
    actor ! audioSource
    actor ! StartPlayback
    expectMsgType[GetAudioData]

    actor ! bufferResult(dataArray(AudioBufferSize))
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

    actor receive bufferResult(dataArray(PlaybackContextLimit))
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
    * @param actor     the playback actor
    * @param audioData the data chunks (as messages) to be sent to the actor
    * @return the reference to the actor
    */
  private def sendAudioData(actor: ActorRef, audioData: Any*): ActorRef = {
    for (data <- audioData) {
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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)))

    val writeMsg = lineWriter.expectMsgType[WriteAudioData]
    writeMsg.line should be(line)
    writeMsg.data.toArray should be(dataArray(LineChunkSize, increment = 1))
    expectMsgType[GetAudioData]
  }

  it should "request new audio data only if at least a chunk fits into the buffer" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    installMockPlaybackContextFactory(actor)
    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize + LineChunkSize - 2)))

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

    actor ! bufferResult(dataArray(request.length))
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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)),
      bufferResult(dataArray(16, increment = 1)))

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
      stream.write(playMsg.data.toArray, 0, playMsg.data.length)
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
  private def checkPlaybackOfFullSource(sourceSize: Int,
                                        optEventMan: Option[scaladsl.TestProbe[PlayerEvent]] = None,
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
    sendAudioData(actor, bufferResult(dataArray(sourceSize)), BufferDataComplete)

    gatherPlaybackDataWithLineDrain(actor, lineWriter, line, sourceSize,
      playbackDuration) should be(dataArray(sourceSize, increment = 1))
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
    val eventMan = testKit.createTestProbe[PlayerEvent]()
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
    sendAudioData(actor, BufferDataComplete)
    expectMsg(GetAudioSource)
    verifyNoInteractions(line)
  }

  it should "not play audio data if the in-memory buffer is almost empty" in {
    val lineWriter = TestProbe()
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref)))
    val line = installMockPlaybackContextFactory(actor)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! createSource(1)
    sendAudioData(actor, bufferResult(dataArray(PlaybackContextLimit)))
    gatherPlaybackData(actor, lineWriter, line, PlaybackContextLimit - LineChunkSize)
    expectMsgType[GetAudioData]
    lineWriter.expectNoMessage(100.milliseconds)
  }

  it should "generate playback progress events" in {
    val lineWriter = TestProbe()
    val eventMan = testKit.createTestProbe[PlayerEvent]()
    val Chunks = 5
    val SkipTime = 22
    val actor = system.actorOf(PlaybackActor(Config.copy(inMemoryBufferSize = 10 * LineChunkSize),
      testActor, lineWriter.ref, eventMan.ref))
    val line = installMockPlaybackContextFactory(actor)
    val source = createSource(1, skipTime = SkipTime)

    actor ! StartPlayback
    expectMsg(GetAudioSource)
    actor ! source
    val audioData = (1 to Chunks) map (i => bufferResult(dataArray(LineChunkSize, increment = i.toByte)))
    sendAudioData(actor, audioData: _*)
    expectEvent[AudioSourceStartedEvent](eventMan)
    gatherPlaybackData(actor, lineWriter, line, Chunks * LineChunkSize,
      TimeUnit.MILLISECONDS.toNanos(250))
    val event = expectEvent[PlaybackProgressEvent](eventMan)
    event.bytesProcessed should be((Chunks - 1) * LineChunkSize)
    event.playbackTime should be(SkipTime + 1)
    event.currentSource should be(source)

    sendAudioData(actor, bufferResult(dataArray(LineChunkSize, increment = 8)))
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
    val eventMan = testKit.createTestProbe[PlayerEvent]()
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
    val audioData = (1 to Chunks) map (i => bufferResult(dataArray(LineChunkSize, increment = i.toByte)))
    sendAudioData(actor, audioData: _*)
    expectEvent[AudioSourceStartedEvent](eventMan)
    gatherPlaybackData(actor, lineWriter, line, Chunks * LineChunkSize,
      TimeUnit.SECONDS.toNanos(2))
    expectMsgType[GetAudioData]
    val event = expectEvent[PlaybackProgressEvent](eventMan)
    event.playbackTime should be(1)
  }

  it should "reset progress counters when playback of a new source starts" in {
    val eventMan = testKit.createTestProbe[PlayerEvent]()
    val lineWriter = TestProbe()
    val (_, actor) = checkPlaybackOfFullSource(LineChunkSize, Some(eventMan),
      Some(lineWriter), TimeUnit.SECONDS.toNanos(2))
    expectEvent[AudioSourceStartedEvent](eventMan)
    expectEvent[PlaybackProgressEvent](eventMan)
    expectEvent[AudioSourceFinishedEvent](eventMan)
    val source = createSource(2)

    actor ! source
    sendAudioData(actor, bufferResult(dataArray(LineChunkSize, increment = 12)), BufferDataComplete)
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

    val eofMsg = BufferDataComplete
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
    sendAudioData(actor, bufferResult(dataArray(SkipSize, increment = 1)),
      bufferResult(dataArray(AudioBufferSize, increment = 2)), BufferDataComplete)

    gatherPlaybackDataWithLineDrain(actor, lineWriter, line,
      AudioBufferSize) should be(dataArray(AudioBufferSize, increment = 3))
    expectMsg(GetAudioSource)
  }

  it should "skip a chunk partially according to the source's skip property" in {
    val lineWriter = TestProbe()
    val srcActor = system.actorOf(Props(classOf[SimulatedSourceActor],
      List(createSource(1, skipBytes = 7)),
      List(bufferResult(dataArray(8)),
        bufferResult(dataArray(AudioBufferSize, offset = 8)), BufferDataComplete)))
    val actor = system.actorOf(propsWithMockLineWriter(optLineWriter = Some(lineWriter.ref),
      optSource = Some(srcActor)))
    val line = installMockPlaybackContextFactory(actor)
    actor ! StartPlayback

    val audioData = gatherPlaybackData(actor, lineWriter, line, AudioBufferSize + 1)
    audioData should be(dataArray(AudioBufferSize + 1, offset = 7, increment = 1))
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
    sendAudioData(actor, bufferResult(dataArray(LineChunkSize)),
      bufferResult(dataArray(PlaybackContextLimit, increment = 1)))
    val playMsg = lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    playMsg.line should be(line)
    playMsg.data.toArray should be(dataArray(LineChunkSize, increment = 1))

    actor ! SkipSource
    sendAudioData(actor, bufferResult(dataArray(LineChunkSize, increment = 3)), BufferDataComplete)
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

    sendAudioData(actor, bufferResult(dataArray(PlaybackContextLimit + 1)))
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    expectMsgType[GetAudioData]
    actor ! BufferDataComplete
    expectMsg(GetAudioSource)
    actor receive bufferResult(dataArray(PlaybackContextLimit + 1, increment = 1))
  }

  /**
    * Helper method for testing a failed creation of a playback context. It is
    * tested whether the current source is skipped afterwards.
    *
    * @param ctx   the playback context to be returned by the factory
    * @param evMan an optional event manager probe
    * @return the mock playback context factory
    */
  private def checkSkipAfterFailedPlaybackContextCreation(ctx: Option[PlaybackContext],
                                                          evMan: Option[scaladsl.TestProbe[PlayerEvent]] = None):
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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)),
      bufferResult(dataArray(PlaybackContextLimit, increment = 1)),
      BufferDataComplete)

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
    val eventMan = testKit.createTestProbe[PlayerEvent]()
    checkSkipAfterFailedPlaybackContextCreation(ctx = None, evMan = Some(eventMan))
    eventMan.expectMessageType[AudioSourceStartedEvent]

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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)))
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
    sendAudioData(actor, bufferResult(dataArray(PlaybackContextLimit)))
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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)))

    actor ! SkipSource
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize, increment = 2)), BufferDataComplete)
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
    sendAudioData(actor, bufferResult(dataArray(LineChunkSize)), BufferDataComplete)
    gatherPlaybackData(actor, lineWriter, line, LineChunkSize)
    lineWriter.expectMsg(LineWriterActor.DrainLine(line))
    val drainTime = System.nanoTime()
    actor.tell(LineWriterActor.LineDrained, lineWriter.ref)
    expectMsg(GetAudioSource)

    assertPlaybackContextClosed(line, streamFactory) should be > drainTime
  }

  it should "handle an exception when reading from the audio stream" in {
    val lineWriter = TestProbe()
    val eventMan = testKit.createTestProbe[PlayerEvent]()
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
    sendAudioData(actor, bufferResult(dataArray(PlaybackContextLimit)))
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
    actor.tell(LineWriterActor.AudioDataWritten(LineChunkSize, 0), lineWriter.ref)
    sendAudioData(actor, bufferResult(dataArray(LineChunkSize, increment = 1)), BufferDataComplete)
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
    sendAudioData(actor, bufferResult(dataArray(LineChunkSize)), BufferDataComplete)
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
    sendAudioData(actor, bufferResult(dataArray(PlaybackContextLimit)),
      bufferResult(dataArray(32, increment = 1)))
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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)))
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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)),
      bufferResult(dataArray(AudioBufferSize, increment = 1)))
    lineWriter.expectMsgType[LineWriterActor.WriteAudioData]
  }

  it should "skip the current finite source if no audio data can be read" in {
    val lineWriter = TestProbe()
    val eventMan = testKit.createTestProbe[PlayerEvent]()
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
    sendAudioData(actor, bufferResult(dataArray(AudioBufferSize)),
      bufferResult(dataArray(AudioBufferSize, increment = 1)),
      bufferResult(dataArray(AudioBufferSize, increment = 2)), BufferDataComplete)
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
    actor.underlyingActor should not be Symbol("playing")
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
    sendAudioData(actor, bufferResult(dataArray(PlaybackContextLimit)))
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
    sendAudioData(actor, bufferResult(dataArray(PlaybackContextLimit)))
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

  override protected val eventTimeExtractor: PlayerEvent => LocalDateTime = _.time
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
      if (currentSources.nonEmpty) {
        sender() ! currentSources.head
        currentSources = currentSources.tail
      }

    case GetAudioData(_) =>
      if (currentData.nonEmpty) {
        sender() ! currentData.head
        currentData = currentData.tail
      }
  }
}
