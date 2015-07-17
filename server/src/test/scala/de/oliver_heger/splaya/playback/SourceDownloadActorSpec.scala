package de.oliver_heger.splaya.playback

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import de.oliver_heger.splaya.RecordingSchedulerSupport.SchedulerInvocation
import de.oliver_heger.splaya.io.{CloseAck, CloseRequest}
import de.oliver_heger.splaya.media.MediaManagerActor
import de.oliver_heger.splaya.{RecordingSchedulerSupport, SupervisionTestActor}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object SourceDownloadActorSpec {
  /** Constant for a test medium ID. */
  private val MediumID = "Test-Medium"

  /** Constant for a test source length. */
  private val SourceLength = 20150309111624L

  /** Constant for the initial delay of download in progress messages. */
  private val ReaderAliveDelay = 2.minutes

  /** Constant for the interval of download in progress messages. */
  private val ReaderAliveInterval = 4.minutes

  /**
   * Generates a unique URI for the audio source with the specified index.
   * @param index the index of the audio source
   * @return the URI of this source
   */
  private def sourceURI(index: Int): String = s"TestSource$index.mp3"

  /**
   * Generates the ID of a test audio source with the specified index.
   * @param index the index of the audio source
   * @return the ID of this test audio source
   */
  private def sourceID(index: Int): AudioSourceID = AudioSourceID(MediumID, sourceURI(index))

  /**
   * Creates an audio source in a playlist which can be used for testing.
   * @param index an index for generating unique test data
   * @return the test audio source playlist info
   */
  private def createPlaylistInfo(index: Int, skip: Long = 0, skipTime: Long = 0):
  AudioSourcePlaylistInfo =
    AudioSourcePlaylistInfo(sourceID(index), index, skip, skipTime)
}

/**
 * Test class for ''SourceDownloadActor''.
 */
class SourceDownloadActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import de.oliver_heger.splaya.playback.SourceDownloadActorSpec._

  def this() = this(ActorSystem("SourceDownloadActorSpec",
  ConfigFactory.parseString(
    s"""
       |splaya {
       |  playback {
       |    downloadProgressMessageDelay = ${SourceDownloadActorSpec.ReaderAliveDelay.toString()}
       |    downloadProgressMessageInterval = ${SourceDownloadActorSpec.ReaderAliveInterval.toString()}
       |  }
       |}
     """.stripMargin)))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Creates a ''Props'' object for instantiating a test actor. Optionally,
   * references to collaborating actors can be passed. If not specified, the
   * implicit test actor is used.
   * @param optSource optional reference to the source actor
   * @param optBuffer optional reference to the buffer actor
   * @param optReader optional reference to the reader actor
   * @param optQueue optional queue for scheduler invocations
   * @return the ''Props'' object
   */
  private def propsForActor(optSource: Option[ActorRef] = None, optBuffer: Option[ActorRef] =
  None, optReader: Option[ActorRef] = None, optQueue: Option[BlockingQueue[SchedulerInvocation]]
  = None): Props = {
    def fetchRef(optRef: Option[ActorRef]): ActorRef = optRef getOrElse testActor

    val schedulerQueue = optQueue getOrElse new LinkedBlockingQueue[SchedulerInvocation]
    Props(new SourceDownloadActor(fetchRef(optSource), fetchRef(optBuffer), fetchRef
      (optReader)) with RecordingSchedulerSupport {
      override val queue: BlockingQueue[SchedulerInvocation] = schedulerQueue
    })
  }

  /**
   * Creates a test actor with the given optional dependencies.
   * @param optSource optional reference to the source actor
   * @param optBuffer optional reference to the buffer actor
   * @param optReader optional reference to the reader actor
   * @param optQueue optional queue for scheduler invocations
   * @return the test actor reference
   */
  private def createDownloadActor(optSource: Option[ActorRef] = None, optBuffer: Option[ActorRef]
  = None, optReader: Option[ActorRef] = None, optQueue:
  Option[BlockingQueue[SchedulerInvocation]] = None): ActorRef =
    system.actorOf(propsForActor(optSource = optSource, optBuffer = optBuffer, optReader =
      optReader, optQueue = optQueue))

  /**
   * Convenience method for creating a download test actor which is initialized
   * with test probe objects.
   * @param srcActor probe for the source actor
   * @param bufActor probe for the buffer actor
   * @param readActor probe for the reader actor
   * @return the test download actor
   */
  private def createDownloadActorWithProbes(srcActor: TestProbe, bufActor: TestProbe, readActor:
  TestProbe): ActorRef =
    createDownloadActor(Some(srcActor.ref), Some(bufActor.ref), Some(readActor.ref))

  "A SourceDownloadActor" should "request a source when it becomes available" in {
    val srcActor = TestProbe()
    val actor = createDownloadActor(optSource = Some(srcActor.ref))
    val source = createPlaylistInfo(1)

    actor ! source
    srcActor.expectMsg(source.sourceID)
  }

  it should "reject a download response that was not requested" in {
    val actor = createDownloadActor()
    val response = AudioSourceDownloadResponse(sourceID(1), testActor, 42)
    actor ! response

    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(response)
    errMsg.errorText should include("Unexpected AudioSourceDownloadResponse")
  }

  it should "reject an unexpected buffer-filled message" in {
    val actor = createDownloadActor()
    val msg = LocalBufferActor.BufferFilled(testActor, 100)
    actor ! msg

    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(msg)
    errMsg.errorText should include("Unexpected BufferFilled")
  }

  it should "handle a download response" in {
    val srcActor, bufActor, readActor, contentActor = TestProbe()
    val actor = createDownloadActorWithProbes(srcActor, bufActor, readActor)

    actor ! createPlaylistInfo(1)
    actor ! AudioSourceDownloadResponse(sourceID(1), contentActor.ref, SourceLength)
    bufActor.expectMsg(LocalBufferActor.FillBuffer(contentActor.ref))
    readActor.expectMsg(AudioSource(sourceURI(1), 1, SourceLength, 0, 0))
  }

  it should "handle multiple items on the playlist" in {
    val Skip = 20150309130001L
    val SkipTime = 20150309130018L
    val srcActor, bufActor, readActor, contentActor1, contentActor2 = TestProbe()
    val actor = createDownloadActorWithProbes(srcActor, bufActor, readActor)

    actor ! createPlaylistInfo(1, skip = Skip, skipTime = SkipTime)
    srcActor.expectMsg(sourceID(1))
    actor ! createPlaylistInfo(2)
    actor ! AudioSourceDownloadResponse(sourceID(1), contentActor1.ref, SourceLength)
    bufActor.expectMsg(LocalBufferActor.FillBuffer(contentActor1.ref))
    readActor.expectMsg(AudioSource(sourceURI(1), 1, SourceLength, Skip, SkipTime))
    srcActor.expectMsg(sourceID(2))
    actor ! createPlaylistInfo(3)
    actor ! AudioSourceDownloadResponse(sourceID(2), contentActor2.ref, SourceLength + 1)
    readActor.expectMsg(AudioSource(sourceURI(2), 2, SourceLength + 1, 0, 0))
    actor ! LocalBufferActor.BufferFilled(contentActor1.ref, SourceLength)
    bufActor.expectMsg(LocalBufferActor.FillBuffer(contentActor2.ref))
    srcActor.expectMsg(sourceID(3))
  }

  it should "ignore download response messages if the length is undefined" in {
    val srcActor, bufActor, readActor, contentActor1, contentActor2 = TestProbe()
    val actor = createDownloadActorWithProbes(srcActor, bufActor, readActor)
    actor ! createPlaylistInfo(1)
    srcActor.expectMsg(sourceID(1))
    actor ! createPlaylistInfo(2)

    actor ! AudioSourceDownloadResponse(sourceID(1), contentActor1.ref, -1)
    srcActor.expectMsg(sourceID(2))
    actor ! AudioSourceDownloadResponse(sourceID(2), contentActor2.ref, SourceLength)
    readActor.expectMsg(AudioSource(sourceURI(2), 2, SourceLength, 0, 0))
    bufActor.expectMsg(LocalBufferActor.FillBuffer(contentActor2.ref))
  }

  it should "report that a reader actor is still alive" in {
    val srcActor, bufActor, readActor, contentActor = TestProbe()
    val actor = createDownloadActorWithProbes(srcActor, bufActor, readActor)
    actor ! createPlaylistInfo(1)
    actor ! AudioSourceDownloadResponse(sourceID(1), contentActor.ref, SourceLength)
    srcActor.expectMsgType[AudioSourceID]

    actor ! SourceDownloadActor.ReportReaderActorAlive
    srcActor.expectMsg(MediaManagerActor.ReaderActorAlive(contentActor.ref))
  }

  it should "deal with with an undefined reader when receiving a report reader alive message" in {
    val srcActor = TestProbe()
    val strategy = OneForOneStrategy() {
      case _: Exception => Stop
    }
    val supervisionTestActor = SupervisionTestActor(system, strategy, propsForActor(optSource =
      Some(srcActor.ref)))
    val actor = supervisionTestActor.underlyingActor.childActor

    actor ! SourceDownloadActor.ReportReaderActorAlive
    actor ! createPlaylistInfo(1)
    srcActor.expectMsgType[AudioSourceID]
  }

  it should "stop a read actor after it has been processed" in {
    val srcActor, bufActor, readActor, contentActor, watchActor = TestProbe()
    val actor = createDownloadActorWithProbes(srcActor, bufActor, readActor)
    watchActor watch contentActor.ref

    actor ! createPlaylistInfo(1)
    srcActor.expectMsg(sourceID(1))
    actor ! AudioSourceDownloadResponse(sourceID(1), contentActor.ref, SourceLength)
    actor ! LocalBufferActor.BufferFilled(contentActor.ref, SourceLength)
    val termMsg = watchActor.expectMsgType[Terminated]
    termMsg.actor should be(contentActor.ref)
  }

  it should "sent a download completion message after a buffer fill operation" in {
    val FilledSize = 20150410
    val srcActor, bufActor, readActor, contentActor = TestProbe()
    val actor = createDownloadActorWithProbes(srcActor, bufActor, readActor)

    actor ! createPlaylistInfo(1)
    srcActor.expectMsg(sourceID(1))
    actor ! AudioSourceDownloadResponse(sourceID(1), contentActor.ref, SourceLength)
    bufActor.expectMsg(LocalBufferActor.FillBuffer(contentActor.ref))
    readActor.expectMsgType[AudioSource]

    actor ! LocalBufferActor.BufferFilled(contentActor.ref, FilledSize)
    readActor.expectMsg(SourceReaderActor.AudioSourceDownloadCompleted(FilledSize))
  }

  it should "ack a close request" in {
    val actor = createDownloadActor()

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
  }

  it should "stop the current read actor when receiving a close request" in {
    val srcActor, bufActor, readActor, contentActor, watchActor = TestProbe()
    val actor = createDownloadActorWithProbes(srcActor, bufActor, readActor)
    watchActor watch contentActor.ref

    actor ! createPlaylistInfo(1)
    actor ! AudioSourceDownloadResponse(sourceID(1), contentActor.ref, SourceLength)
    actor ! CloseRequest
    val termMsg = watchActor.expectMsgType[Terminated]
    termMsg.actor should be(contentActor.ref)
    expectMsg(CloseAck(actor))
  }

  it should "periodically report that the current reader actor is alive" in {
    val queue = new LinkedBlockingQueue[SchedulerInvocation]
    val actor = createDownloadActor(optQueue = Some(queue))

    val invocation = RecordingSchedulerSupport.expectInvocation(queue)
    invocation.initialDelay should be (ReaderAliveDelay)
    invocation.interval should be (ReaderAliveInterval)
    invocation.receiver should be(actor)
    invocation.message should be(SourceDownloadActor.ReportReaderActorAlive)
  }

  it should "cancel scheduled tasks when it is stopped" in {
    val queue = new LinkedBlockingQueue[SchedulerInvocation]
    val actor = createDownloadActor(optQueue = Some(queue))
    val invocation = RecordingSchedulerSupport.expectInvocation(queue)

    system stop actor
    awaitCond(invocation.cancellable.isCancelled)
  }
}
