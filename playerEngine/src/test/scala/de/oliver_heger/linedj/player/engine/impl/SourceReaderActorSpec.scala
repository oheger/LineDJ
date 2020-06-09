/*
 * Copyright 2015-2020 The Developers Team.
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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.AudioSource
import de.oliver_heger.linedj.player.engine.impl.PlaybackActor.{GetAudioData, GetAudioSource}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec

object SourceReaderActorSpec {
  /** A byte string to generate messages with test data. */
  private final val TestString = ByteString(FileTestHelper.TestData)

  /**
    * Creates a test audio source with parameters derived from the given index.
    *
    * @param index  the index
    * @param length the optional length of the audio source
    * @return the test audio source
    */
  private def audioSource(index: Int, length: Long = 10000): AudioSource =
    AudioSource(s"testSource$index.mp3", length, 0, 0)
}

/**
  * Test class for ''SourceReaderActor''.
  */
class SourceReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import SourceReaderActorSpec._

  def this() = this(ActorSystem("SourceReaderActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a ''Props'' object for instantiating a test actor. Optionally,
    * references to collaboration actors can be passed in. If unspecified, the
    * implicit test actor is used.
    *
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
    *
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

  it should "reset a pending audio source request after it has been processed" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(2)

    reader ! PlaybackActor.GetAudioSource
    reader ! source
    reader ! audioSource(3)
    expectMsg(source)
    reader ! PlaybackActor.GetAudioSource
    expectMsgType[PlaybackProtocolViolation]
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

    reader ! LocalBufferActor.BufferReadActor(fileReader.ref, Nil)
    val unexpected = LocalBufferActor.BufferReadActor(fileReader.ref, Nil)
    reader ! unexpected
    val msgErr = expectMsgType[PlaybackProtocolViolation]
    msgErr.msg should be(unexpected)
    msgErr.errorText should include("Unexpected BufferReadActor message")
  }

  /**
    * Creates a dummy buffer read result with the specified length. (Only the
    * length is important; the actual content is not evaluated.)
    *
    * @param length the length of the result message
    * @return the dummy result message
    */
  private def bufferResult(length: Int): LocalBufferActor.BufferDataResult = {
    @tailrec def fillStr(current: ByteString): ByteString =
      if (length >= FileTestHelper.TestData.length)
        fillStr(current ++ TestString)
      else current ++ ByteString(FileTestHelper.TestData.substring(0, length - current.length))

    val data = fillStr(ByteString.empty)
    LocalBufferActor.BufferDataResult(data)
  }

  /**
    * Executes a request for audio data on the test actor. This method simulates
    * the interaction with the involved actors.
    *
    * @param reader          the test source reader actor
    * @param fileReader      the file reader actor serving the request
    * @param requestedLength the requested length of audio data
    * @param readLength      the length passed to the file reader actor
    * @param result          the result from the file reader actor
    * @return the result message from the file reader actor
    */
  private def audioDataRequest(reader: ActorRef, fileReader: TestProbe, requestedLength: Int,
                               readLength: Int, result: Any): Any = {
    reader ! GetAudioData(requestedLength)
    fileReader.expectMsg(LocalBufferActor.BufferDataRequest(readLength))
    reader ! result
    result
  }

  /**
    * Creates a ''TestProbe'' for a file reader actor and passes it to the
    * test source reader actor.
    *
    * @param actor         the test source reader actor
    * @param sourceLengths a list with the lengths of completed sources
    * @return the probe for the file reader actor
    */
  private def installFileReaderActor(actor: ActorRef, sourceLengths: List[Long] = Nil): TestProbe = {
    val fileReader = TestProbe()
    actor ! LocalBufferActor.BufferReadActor(fileReader.ref, sourceLengths)
    fileReader
  }

  it should "serve a GetAudioData request from an available read actor" in {
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val source = audioSource(1)
    val length = 64
    val readData = bufferResult(length)
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
    *
    * @param reader       the test reader actor
    * @param fileReader   the actor for reading from a file
    * @param sourceLength the length of the test source
    * @param chunkSize    the chunk size (must be less than the source length)
    */
  private def processSource(reader: ActorRef, fileReader: TestProbe, sourceLength: Int,
                            chunkSize: Int): Unit = {
    expectMsg(audioDataRequest(reader, fileReader, chunkSize, chunkSize, bufferResult
    (chunkSize)))
    expectMsg(audioDataRequest(reader, fileReader, chunkSize, sourceLength - chunkSize,
      bufferResult(sourceLength - chunkSize)))
    reader ! GetAudioData(chunkSize)
    expectMsg(LocalBufferActor.BufferDataComplete)
  }

  it should "reject unexpected data from the buffer" in {
    val reader = readerActor()
    expectMsg(LocalBufferActor.ReadBuffer)
    val bufferResult = LocalBufferActor.BufferDataResult(TestString)

    reader ! bufferResult
    val msgErr = expectMsgType[PlaybackProtocolViolation]
    msgErr.msg should be(bufferResult)
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

  it should "read a full chunk of audio data if the source size is unknown" in {
    val ChunkSize = 128
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val fileReader = installFileReaderActor(reader)
    reader ! GetAudioSource
    reader ! audioSource(1, AudioSource.UnknownLength)
    expectMsgType[AudioSource]

    reader ! GetAudioData(ChunkSize)
    fileReader.expectMsg(LocalBufferActor.BufferDataRequest(ChunkSize))
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
    *
    * @param watcher    the watcher test probe
    * @param fileReader the test probe for the file reader
    */
  private def checkFileReaderTermination(watcher: TestProbe, fileReader: TestProbe): Unit = {
    watcher.expectTerminated(fileReader.ref)
  }

  it should "deal with an EndOfData message from the read actor" in {
    val ChunkSize = 32
    val buffer, watcher = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    val fileReader = installFileReaderActor(reader)
    watcher watch fileReader.ref
    buffer.expectMsg(LocalBufferActor.ReadBuffer)
    reader ! audioSource(1)
    reader ! GetAudioSource
    expectMsgType[AudioSource]

    expectMsg(audioDataRequest(reader, fileReader, ChunkSize, ChunkSize, bufferResult
    (ChunkSize)))
    expectMsg(audioDataRequest(reader, fileReader, ChunkSize, ChunkSize, bufferResult
    (ChunkSize / 2)))
    audioDataRequest(reader, fileReader, ChunkSize, ChunkSize, LocalBufferActor.BufferDataComplete)
    buffer.expectMsg(LocalBufferActor.BufferReadComplete(fileReader.ref))
    buffer.expectMsg(LocalBufferActor.ReadBuffer)
    val nextFileReader = installFileReaderActor(reader)
    nextFileReader.expectMsg(LocalBufferActor.BufferDataRequest(ChunkSize))
  }

  it should "reject an unexpected EndOfData message" in {
    val reader = readerActor()
    expectMsg(LocalBufferActor.ReadBuffer)

    reader ! LocalBufferActor.BufferDataComplete
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(LocalBufferActor.BufferDataComplete)
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
    val Size = 20180227L
    val reader = readerActor(optBufferActor = Some(TestProbe().ref))
    reader ! audioSource(1, length = AudioSource.UnknownLength)
    installFileReaderActor(reader, List(Size))

    reader ! PlaybackActor.GetAudioSource
    val src = expectMsgType[AudioSource]
    src.length should be(Size)
  }

  it should "only change the size of a source that is undefined" in {
    val Size = 20180228L
    val reader = readerActor(optBufferActor = Some(TestProbe().ref))
    reader ! audioSource(1, length = Size)
    installFileReaderActor(reader, List(Size - 1))

    reader ! PlaybackActor.GetAudioSource
    val src = expectMsgType[AudioSource]
    src.length should be(Size)
  }

  it should "handle size updates for multiple sources" in {
    val SourceLength1 = 100
    val SourceLength2 = 80
    val SourceLength3 = 70
    val ChunkSize = 64
    val buffer = TestProbe()
    val reader = readerActor(optBufferActor = Some(buffer.ref))
    reader ! audioSource(1, length = AudioSource.UnknownLength)
    reader ! audioSource(2, length = AudioSource.UnknownLength)
    reader ! audioSource(3, length = AudioSource.UnknownLength)
    reader ! GetAudioSource
    expectMsg(audioSource(1, length = AudioSource.UnknownLength))
    val fileReader = installFileReaderActor(reader, List(SourceLength1, SourceLength2,
      SourceLength3))

    processSource(reader, fileReader, SourceLength1, ChunkSize)
    reader ! GetAudioSource
    expectMsgType[AudioSource]
    processSource(reader, fileReader, SourceLength2, ChunkSize)
    reader ! GetAudioSource
    expectMsgType[AudioSource]
    processSource(reader, fileReader, SourceLength3, ChunkSize)
  }

  it should "handle source sizes over multiple file reader actors" in {
    val SourceLength1 = 100
    val SourceLength2 = 110
    val SourceLength2InFirstFile = 60
    val ChunkSize = 64
    val reader = readerActor(optBufferActor = Some(TestProbe().ref))
    val fileReader1 = installFileReaderActor(reader, List(SourceLength1))
    reader ! audioSource(1, length = AudioSource.UnknownLength)
    reader ! audioSource(2, length = AudioSource.UnknownLength)
    reader ! GetAudioSource
    expectMsgType[AudioSource]
    processSource(reader, fileReader1, SourceLength1, ChunkSize)

    reader ! GetAudioSource
    expectMsgType[AudioSource]
    expectMsg(audioDataRequest(reader, fileReader1, ChunkSize, ChunkSize,
      bufferResult(SourceLength2InFirstFile)))
    reader ! GetAudioData(ChunkSize)
    fileReader1.expectMsg(LocalBufferActor.BufferDataRequest(ChunkSize))
    reader ! LocalBufferActor.BufferDataComplete
    val fileReader2 = installFileReaderActor(reader, List(SourceLength2))
    fileReader2.expectMsg(LocalBufferActor.BufferDataRequest(SourceLength2 - SourceLength2InFirstFile))
  }

  it should "handle a pending read request with a remaining size of 0" in {
    val Length = 100
    val reader = readerActor(optBufferActor = Some(TestProbe().ref))
    val fileReader = installFileReaderActor(reader, List())
    reader ! audioSource(1, length = AudioSource.UnknownLength)
    reader ! GetAudioSource
    reader ! audioSource(2)
    expectMsgType[AudioSource]
    expectMsg(audioDataRequest(reader, fileReader, Length, Length, bufferResult(Length)))

    reader ! GetAudioData(Length)
    reader ! LocalBufferActor.BufferDataComplete
    val fileReader2 = installFileReaderActor(reader, List(Length))
    expectMsg(LocalBufferActor.BufferDataComplete)
    val ping = new Object
    fileReader2.ref ! ping
    fileReader2.expectMsg(ping)
    reader ! GetAudioSource
    expectMsgType[AudioSource]
  }
}
