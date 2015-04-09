package de.oliver_heger.splaya.playback

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.FileReaderActor.EndOfFile
import de.oliver_heger.splaya.io.{CloseAck, CloseRequest, FileReaderActor}
import de.oliver_heger.splaya.playback.PlaybackActor.{GetAudioData, GetAudioSource}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object SourceReaderActorSpec {
  /**
   * Creates a test audio source with parameters derived from the given index.
   * @param index the index
   * @param length the optional length of the audio source
   * @return the test audio source
   */
  private def audioSource(index: Int, length: Int = 10000): AudioSource =
    AudioSource(s"testSource$index.mp3", index, length, 0, 0)
}

/**
 * Test class for ''SourceReaderActor''.
 */
class SourceReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import de.oliver_heger.splaya.playback.SourceReaderActorSpec._

  def this() = this(ActorSystem("SourceReaderActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Creates a ''Props'' object for instantiating a test actor. Optionally,
   * references to collaboration actors can be passed in. If unspecified, the
   * implicit test actor is used.
   * @param optBufferActor an optional buffer actor
   * @return the ''Props'' for creating a test actor
   */
  private def propsForTestActor(optBufferActor: Option[ActorRef] = None): Props = {
    def fetchActor(optActor: Option[ActorRef]): ActorRef = optActor getOrElse testActor

    Props(classOf[SourceReaderActor], fetchActor(optBufferActor))
  }

  /**
   * Convenience method for creating a test reader actor with optional
   * dependencies.
   * @param optBufferActor an optional buffer actor
   * @return the test reader actor
   */
  private def readerActor(optBufferActor: Option[ActorRef] = None): ActorRef =
    system.actorOf(propsForTestActor(optBufferActor))

  "A SourceReaderActor" should "request a reader actor when it is started" in {
    readerActor()
    expectMsg(LocalBufferActor.ReadBuffer)
  }

  it should "pass an audio source to the playback actor" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(1)

    reader ! source
    reader ! PlaybackActor.GetAudioSource
    expectMsg(source)
  }

  it should "reject a data request if there is no current source" in {
    val reader = readerActor()
    expectMsg(LocalBufferActor.ReadBuffer)
    val msgData = PlaybackActor.GetAudioData(42)

    reader ! msgData
    val msgErr = expectMsgType[PlaybackProtocolViolation]
    msgErr.msg should be(msgData)
    msgErr.errorText should include("No current AudioSource")
  }

  it should "answer an audio source request when a source becomes available" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(2)

    reader ! PlaybackActor.GetAudioSource
    reader ! source
    expectMsg(source)
  }

  it should "reject a second audio source request while the source is still processed" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(3)
    reader ! source
    reader ! PlaybackActor.GetAudioSource
    expectMsg(source)

    reader ! PlaybackActor.GetAudioSource
    val msgErr = expectMsgType[PlaybackProtocolViolation]
    msgErr.msg should be(PlaybackActor.GetAudioSource)
    msgErr.errorText should include("is still processed")
  }

  it should "support multiple audio sources in the playlist" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(1)

    reader ! source
    reader ! audioSource(2)
    reader ! PlaybackActor.GetAudioSource
    expectMsg(source)
  }

  it should "reject a buffer reader message if no reader was requested" in {
    val buffer = TestProbe()
    val fileReader = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))

    reader ! LocalBufferActor.BufferReadActor(fileReader.ref)
    val unexpected = LocalBufferActor.BufferReadActor(fileReader.ref)
    reader ! unexpected
    val msgErr = expectMsgType[PlaybackProtocolViolation]
    msgErr.msg should be(unexpected)
    msgErr.errorText should include("Unexpected BufferReadActor message")
  }

  /**
   * Creates a mock array source with the specified length. (Only the length is
   * important; other properties are not evaluated.)
   * @param length the length of the source
   * @return the mock array source
   */
  private def arraySourceMock(length: Int): ArraySource = {
    val source = mock[ArraySource]
    when(source.length).thenReturn(length)
    source
  }

  /**
   * Executes a request for audio data on the test actor. This method simulates
   * the interaction with the involved actors.
   * @param reader the test source reader actor
   * @param fileReader the file reader actor serving the request
   * @param requestedLength the requested length of audio data
   * @param readLength the length passed to the file reader actor
   * @param result the result from the file reader actor
   * @return the result message from the file reader actor
   */
  private def audioDataRequest(reader: ActorRef, fileReader: TestProbe, requestedLength: Int,
                               readLength: Int, result: Any): Any = {
    reader ! GetAudioData(requestedLength)
    fileReader.expectMsg(FileReaderActor.ReadData(readLength))
    reader ! result
    result
  }

  /**
   * Creates a ''TestProbe'' for a file reader actor and passes it to the
   * test source reader actor.
   * @param actor the test source reader actor
   * @return the probe for the file reader actor
   */
  private def installFileReaderActor(actor: ActorRef): TestProbe = {
    val fileReader = TestProbe()
    actor ! LocalBufferActor.BufferReadActor(fileReader.ref)
    fileReader
  }

  it should "serve a GetAudioData request from an available read actor" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(1)
    val length = 64
    val readData = arraySourceMock(length)
    val fileReader = installFileReaderActor(reader)

    reader ! GetAudioSource
    reader ! source
    expectMsg(source)
    audioDataRequest(reader, fileReader, length, length, readData)
    expectMsg(readData)
  }

  /**
   * Simulates the processing of an audio source. The source is read in two
   * chunks. It is checked whether then an end-of-file message is sent.
   * @param reader the test reader actor
   * @param fileReader the actor for reading from a file
   * @param sourceLength the length of the test source
   * @param chunkSize the chunk size (must be less than the source length)
   */
  private def processSource(reader: ActorRef, fileReader: TestProbe, sourceLength: Int,
                            chunkSize: Int): Unit = {
    expectMsg(audioDataRequest(reader, fileReader, chunkSize, chunkSize, arraySourceMock
      (chunkSize)))
    expectMsg(audioDataRequest(reader, fileReader, chunkSize, sourceLength - chunkSize,
      arraySourceMock(sourceLength - chunkSize)))
    reader ! GetAudioData(chunkSize)
    expectMsg(EndOfFile(null))
  }

  it should "reject unexpected read data" in {
    val reader = readerActor()
    expectMsg(LocalBufferActor.ReadBuffer)
    val source = mock[ArraySource]

    reader ! source
    val msgErr = expectMsgType[PlaybackProtocolViolation]
    msgErr.msg should be(source)
    msgErr.errorText should include("Unexpected read results")
  }

  it should "read audio data until the size of the source is reached" in {
    val SourceLength = 100
    val ChunkSize = 64
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val fileReader = installFileReaderActor(reader)
    reader ! GetAudioSource
    reader ! audioSource(1, SourceLength)
    expectMsgType[AudioSource]

    processSource(reader, fileReader, SourceLength, ChunkSize)
  }

  it should "reject a duplicated GetAudioData message" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    reader ! GetAudioSource
    reader ! audioSource(1)
    expectMsgType[AudioSource]

    reader ! GetAudioData(42)
    val msgDuplicated = GetAudioData(100)
    reader ! msgDuplicated
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(msgDuplicated)
    errMsg.errorText should include("Unexpected GetAudioData")
  }

  it should "handle multiple audio sources in a sequence" in {
    val SourceLength1 = 100
    val SourceLength2 = 80
    val ChunkSize = 64
    val source1 = audioSource(1, SourceLength1)
    val source2 = audioSource(2, SourceLength2)
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val fileReader = installFileReaderActor(reader)
    reader ! source1
    reader ! source2

    reader ! GetAudioSource
    expectMsg(source1)
    processSource(reader, fileReader, SourceLength1, ChunkSize)
    reader ! GetAudioSource
    expectMsg(source2)
    processSource(reader, fileReader, SourceLength2, ChunkSize)
  }

  /**
   * Checks whether a termination message for a file reader actor was received.
   * @param watcher the watcher test probe
   * @param fileReader the test probe for the file reader
   */
  private def checkFileReaderTermination(watcher: TestProbe, fileReader: TestProbe): Unit = {
    val termMsg = watcher.expectMsgType[Terminated]
    termMsg.actor should be(fileReader.ref)
  }

  it should "deal with an EndOfFile message from the read actor" in {
    val ChunkSize = 32
    val buffer, watcher = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val fileReader = installFileReaderActor(reader)
    watcher watch fileReader.ref
    buffer.expectMsg(LocalBufferActor.ReadBuffer)
    reader ! audioSource(1)
    reader ! GetAudioSource
    expectMsgType[AudioSource]

    expectMsg(audioDataRequest(reader, fileReader, ChunkSize, ChunkSize, arraySourceMock
      (ChunkSize)))
    expectMsg(audioDataRequest(reader, fileReader, ChunkSize, ChunkSize, arraySourceMock
      (ChunkSize / 2)))
    audioDataRequest(reader, fileReader, ChunkSize, ChunkSize, EndOfFile(null))
    checkFileReaderTermination(watcher, fileReader)
    buffer.expectMsg(LocalBufferActor.ReadBuffer)
    val nextFileReader = installFileReaderActor(reader)
    expectMsg(audioDataRequest(reader, nextFileReader, ChunkSize, ChunkSize, arraySourceMock
      (ChunkSize)))
  }

  it should "reject an unexpected EndOfFile message" in {
    val reader = readerActor()
    expectMsg(LocalBufferActor.ReadBuffer)
    val eofMsg = EndOfFile(null)

    reader ! eofMsg
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(eofMsg)
    errMsg.errorText should include("Unexpected EndOfFile")
  }

  it should "ack a closing request" in {
    val reader = readerActor(Some(TestProbe().ref))

    reader ! CloseRequest
    expectMsg(CloseAck(reader))
  }

  it should "stop the current file reader actor on closing" in {
    val buffer, watcher = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val fileReader = installFileReaderActor(reader)
    watcher watch fileReader.ref

    reader ! CloseRequest
    checkFileReaderTermination(watcher, fileReader)
    expectMsg(CloseAck(reader))
  }

  it should "stop all file reader actors received after a closing request" in {
    val buffer, watcher = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    reader ! CloseRequest
    expectMsg(CloseAck(reader))

    val fileReader = installFileReaderActor(reader)
    watcher watch fileReader.ref
    checkFileReaderTermination(watcher, fileReader)
  }

  it should "allow adapting the size of the last audio source (to handle source read errors)" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    reader ! audioSource(1)
    reader ! SourceReaderActor.AudioSourceDownloadCompleted(100)

    reader ! PlaybackActor.GetAudioSource
    val src = expectMsgType[AudioSource]
    src.length should be(100)
  }

  it should "send an error message about an unexpected download completed message" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val completedMsg = SourceReaderActor.AudioSourceDownloadCompleted(100)
    reader ! completedMsg

    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(completedMsg)
    errMsg.errorText should include("Unexpected AudioSourceDownloadCompleted")
  }

  it should "allow adapting the size of the currently processed audio source" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    reader ! audioSource(1)
    reader ! GetAudioSource
    expectMsg(audioSource(1))

    reader ! SourceReaderActor.AudioSourceDownloadCompleted(100)
    val fileReaderActor = installFileReaderActor(reader)
    reader ! GetAudioData(10000)
    fileReaderActor.expectMsg(FileReaderActor.ReadData(100))
  }

  it should "replace a source only if its length has changed" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(1)
    reader ! source
    reader ! SourceReaderActor.AudioSourceDownloadCompleted(source.length)

    reader ! PlaybackActor.GetAudioSource
    expectMsgType[AudioSource] should be theSameInstanceAs source
  }
}
