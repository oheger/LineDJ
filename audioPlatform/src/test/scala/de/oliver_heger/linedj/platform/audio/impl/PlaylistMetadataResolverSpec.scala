/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.impl

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio.*
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistMetadata, PlaylistMetadataRegistration, PlaylistMetadataUnregistration}
import de.oliver_heger.linedj.platform.bus.{ComponentID, Identifiable}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.*
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

object PlaylistMetadataResolverSpec:
  /** The chunk size for metadata requests. */
  private val RequestChunkSize = 4

  /** Size of the metadata cache. */
  private val CacheSize = RequestChunkSize + 1

  /** Default timeout for actor requests. */
  private val RequestTimeout = Timeout(3.seconds)

  /** Test medium ID. */
  private val TestMediumID = MediumID("someMedium", Some("someSettings"))

  /**
    * Generates a test file ID with the specified index.
    *
    * @param idx the index
    * @return the file ID with this index
    */
  private def fileID(idx: Int): MediaFileID =
    MediaFileID(TestMediumID, s"audio://Song$idx.mp3")

  /**
    * Generates metadata for the test song with the given index.
    *
    * @param idx the index
    * @return metadata for this test song
    */
  private def metadata(idx: Int): MediaMetadata =
    MediaMetadata.UndefinedMediaData.copy(title = Some("title" + idx))

  /**
    * Generates a list of file IDs in the provided range.
    *
    * @param from the from index
    * @param to   the to index (including)
    * @return the list with file IDs
    */
  private def fileIDs(from: Int, to: Int): List[MediaFileID] =
    (from to to).map(fileID).toList

  /**
    * Generates a list with metadata for the specified test files.
    *
    * @param ranges a sequence of ranges for the files in the map
    * @return a list containing test metadata for these files
    */
  private def metadataList(ranges: (Int, Int)*): List[(MediaFileID, MediaMetadata)] =
    val rangeLists = ranges map { r =>
      (r._1 to r._2) map (i => (fileID(i), metadata(i)))
    }
    rangeLists.flatten.toList

  /**
    * Generates a request for metadata for a number of files. The files are
    * specified as a sequence of from/to indices (in case there is no single
    * continuous range).
    *
    * @param seqNo  the sequence number for the request
    * @param ranges a sequence of ranges for files to be requested
    * @return the request
    */
  private def request(seqNo: Int, ranges: (Int, Int)*): GetFilesMetadata =
    GetFilesMetadata(files = ranges.flatMap(r => fileIDs(r._1, r._2)), seqNo)

  /**
    * Generates a response for a request for metadata. The response references
    * the given request and consists of metadata defined by the given index
    * ranges. Each range defines the from and to indices of a chunk of metadata
    * (in case that there is no single continuous range).
    *
    * @param request the referenced request
    * @param ranges  a sequence of ranges with metadata in the response
    * @return the response
    */
  private def response(request: GetFilesMetadata, ranges: (Int, Int)*): FilesMetadataResponse =
    FilesMetadataResponse(request, metadataList(ranges: _*))

  /**
    * Generates both a request and a response for metadata for a given set of
    * files. This is useful for responses that contain full metadata for all
    * requested files.
    *
    * @param seqNo  the sequence number for the request
    * @param ranges a sequence of ranges for files to be requested
    * @return a tuple with the request and the response
    */
  private def metadataQuery(seqNo: Int, ranges: (Int, Int)*):
  (GetFilesMetadata, FilesMetadataResponse) =
    val req = request(seqNo, ranges: _*)
    val resp = response(req, ranges: _*)
    (req, resp)

  /**
    * Generates a change event for a player state that consists of the
    * specified playlist information.
    *
    * @param pendingIDs the files in the pending list
    * @param playedIDs  the files in the played list
    * @param seqNo      the sequence number for the playlist
    * @return the resulting event
    */
  private def playlistChangeEvent(pendingIDs: List[MediaFileID],
                                  playedIDs: List[MediaFileID],
                                  seqNo: Int = 1):
  AudioPlayerStateChangedEvent =
    val playlist = Playlist(pendingSongs = pendingIDs, playedSongs = playedIDs)
    val state = AudioPlayerState(playlist = playlist, playbackActive = false,
      playlistClosed = false, playlistSeqNo = seqNo, playlistActivated = false)
    AudioPlayerStateChangedEvent(state)

  /**
    * Completes the specified list by adding undefined metadata to the indices
    * which are not contained.
    *
    * @param dataList the original list
    * @param from     the from index
    * @param to       the to index (including)
    * @return the filled list
    */
  private def fillUndefined(dataList: List[(MediaFileID, MediaMetadata)], from: Int, to: Int):
  List[(MediaFileID, MediaMetadata)] =
    val data = dataList.toMap
    (from to to).foldLeft(data) { (m, i) =>
      val key = fileID(i)
      if m contains key then m else m + (key -> MediaMetadata.UndefinedMediaData)
    }.toList

/**
  * Test class for ''PlaylistMetaDataResolver''.
  */
class PlaylistMetadataResolverSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("PlaylistMetadataResolverSpec"))

  import PlaylistMetadataResolverSpec._

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A PlaylistMetadataResolver" should "resolve metadata in a small playlist" in:
    val (req, resp) = metadataQuery(0, (1, RequestChunkSize))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .expectMetaDataUpdate(resp.data)

  it should "report the current metadata to new consumers" in:
    val (req, resp) = metadataQuery(0, (1, 2))
    val playlistMetaData = PlaylistMetadata(resp.data.toMap)
    var metaDataReceived: PlaylistMetadata = null
    val reg = PlaylistMetadataRegistration(ComponentID(), d => metaDataReceived = d)
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 2), Nil))
      .processMessageOnBus()
      .send(reg)
    metaDataReceived should be(playlistMetaData)

  it should "use the chunk size when sending requests for metadata" in:
    val size = RequestChunkSize + 1
    val (req1, resp1) = metadataQuery(0, (1, RequestChunkSize))
    val (req2, resp2) = metadataQuery(0, (size, size))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, size), Nil))
      .expectMetaDataUpdate(resp1.data)
      .expectMetaDataUpdate(metadataList((1, size)))

  it should "also resolve the files in the played list" in:
    val size = 2 * RequestChunkSize
    val playedFiles = RequestChunkSize + 1
    val (req1, resp1) = metadataQuery(0, (playedFiles + 1, size), (1, 1))
    val (req2, resp2) = metadataQuery(0, (2, playedFiles))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(playedFiles + 1, size),
        fileIDs(1, playedFiles)))
      .expectMetaDataUpdate(resp1.data)
      .expectMetaDataUpdate(metadataList((1, size)))

  it should "handle files which cannot be resolved" in:
    val req = request(0, (1, RequestChunkSize))
    val resp = response(req, (1, 2))
    val data = fillUndefined(metadataList((1, 2)), 1, RequestChunkSize)
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .expectMetaDataUpdate(data)

  it should "cache metadata that has already been resolved" in:
    val commonSize = 2
    val (req1, resp1) = metadataQuery(0, (1, RequestChunkSize))
    val (req2, resp2) = metadataQuery(1, (RequestChunkSize + 1, RequestChunkSize +
      (RequestChunkSize - commonSize)))
    val range2 = (commonSize + 1, commonSize + RequestChunkSize)
    val data = metadataList(range2)
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .processMessageOnBus()
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(range2._1, range2._2), Nil, seqNo = 2))
      .expectMetaDataUpdate(metadataList((commonSize + 1, RequestChunkSize)))
      .expectMetaDataUpdate(data)

  it should "apply the restriction of the cache size" in:
    val range1 = (1, RequestChunkSize)
    val range2 = (RequestChunkSize + 1, 2 * RequestChunkSize)
    val (req1, resp1) = metadataQuery(0, range1)
    val (req2, resp2) = metadataQuery(1, range2)
    val event = playlistChangeEvent(fileIDs(range1._1, range1._2), Nil)
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(event)
      .processMessageOnBus()
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(range2._1, range2._2), Nil, seqNo = 2))
      .processMessageOnBus()
    val metaData = helper.sendStateChangeEvent(event).nextMetaDataUpdate
    metaData.data should have size (CacheSize - RequestChunkSize)

  it should "handle a timeout from the metadata actor" in:
    val data = (1 to RequestChunkSize).map(i => (fileID(i), MediaMetadata.UndefinedMediaData)).toList
    val helper = new ResolverTestHelper(timeout = 100.millis)

    helper.sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .expectMetaDataUpdate(data)

  it should "take over data from an outdated response if possible" in:
    val index = 2 * RequestChunkSize - 1
    val (req1, resp1) = metadataQuery(0, (1, 2), (index, index))
    val req2 = request(1, (2, 2 + RequestChunkSize - 1))
    val resp2 = response(req2, (3, 3))
    val req3 = request(1, (2 + RequestChunkSize, 2 * RequestChunkSize))
    val resp3 = response(req3)
    val endData = fillUndefined(metadataList((2, 3), (index, index)),
      2, 2 * RequestChunkSize)
    val helper = new ResolverTestHelper

    helper
      .sendStateChangeEvent(playlistChangeEvent(List(fileID(1), fileID(2),
        fileID(index)), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(2, 2 * RequestChunkSize), Nil, seqNo = 2))
      .prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .prepareMetaDataRequest(req3, resp3)
      .expectMetaDataUpdate(metadataList((2, 2), (index, index)))
      .processMessageOnBus()
      .expectMetaDataUpdate(endData)

  it should "ignore an outdated response if it does not bring new data" in:
    val (req1, resp1) = metadataQuery(0, (1, 2))
    val (req2, resp2) = metadataQuery(1, (3, 4))
    val helper = new ResolverTestHelper

    helper.sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 2), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(3, 4), Nil, seqNo = 2))
      .prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .processMessageOnBus()
      .expectMetaDataUpdate(metadataList((3, 4)))
    helper.numberOfUpdates should be(1)

  it should "ignore a response if it does not bring new data" in:
    val (req1, resp1) = metadataQuery(0, (1, 2))
    val (req2, resp2) = metadataQuery(1, (1, 1))
    val helper = new ResolverTestHelper

    helper.sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 2), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 1), Nil, seqNo = 2))
      .prepareMetaDataRequest(req1, resp1)
      .expectMetaDataUpdate(metadataList((1, 1)))
      .prepareMetaDataRequest(req2, resp2)
      .processMessageOnBus()
    helper.numberOfUpdates should be(1)

  it should "ignore a state update event that does not change the playlist" in:
    val (req1, resp1) = metadataQuery(0, (1, 3))
    val req2 = request(1, (2, 3), (1, 1))
    val resp2 = FilesMetadataResponse(req1, fillUndefined(Nil, 1, 3))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 3), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(2, 3), List(fileID(1))))
      .prepareMetaDataRequest(req1, resp1)
      .expectMetaDataUpdate(resp1.data)

  it should "resolve the playlist anew when a metadata scan ends" in:
    val req1 = request(0, (1, RequestChunkSize))
    val resp1 = FilesMetadataResponse(req1, fillUndefined(Nil, 1, RequestChunkSize))
    val (req2, resp2) = metadataQuery(1, (1, RequestChunkSize))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .processMessageOnBus()
      .send(MetadataScanCompleted)
      .expectMetaDataUpdate(resp2.data)

  it should "handle a scan completed event before a playlist was set" in:
    val helper = new ResolverTestHelper

    helper.send(MetadataScanCompleted)
      .expectNoMessageOnBus()

  it should "handle a request to remove a consumer" in:
    val (req, resp) = metadataQuery(0, (1, RequestChunkSize))
    val helper = new ResolverTestHelper

    helper.removeConsumerRegistration()
      .prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .processMessageOnBus()
      .numberOfUpdates should be(0)

  it should "create a correct un-registration object for a registration" in:
    val id = ComponentID()
    val reg = PlaylistMetadataRegistration(id, null)

    val unReg = reg.unRegistration
    unReg should be(PlaylistMetadataUnregistration(id))

  /**
    * Test helper class managing a test instance and all of its dependencies.
    *
    * @param timeout a timeout for actor requests
    */
  private class ResolverTestHelper(timeout: Timeout = RequestTimeout) extends Identifiable:
    /** The metadata manager simulator actor. */
    private val metaDataActor = system.actorOf(Props[MetadataActorTestImpl]())

    /** The test message bus. */
    private val messageBus = new MessageBusTestImpl

    /** A counter for the invocations of the consumer function. */
    private val metaDataUpdateCount = new AtomicInteger(-1)

    /** The instance to be tested. */
    private val resolver = createResolver()

    /** The metadata passed to the consumer function. */
    private var playlistMetaData: PlaylistMetadata = _

    /**
      * Prepares the test actor to handle a specific request for metadata.
      *
      * @param req  the request
      * @param resp the response to be sent for this request
      * @return this test helper
      */
    def prepareMetaDataRequest(req: GetFilesMetadata, resp: FilesMetadataResponse):
    ResolverTestHelper =
      metaDataActor ! MetadataResponseMapping(req, resp)
      this

    /**
      * Sends a message to the test instance via the message bus receiver
      * function.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): ResolverTestHelper =
      resolver receive msg
      this

    /**
      * Sends the specified state change event to the test instance.
      *
      * @param event the event
      * @return this test helper
      */
    def sendStateChangeEvent(event: AudioPlayerStateChangedEvent): ResolverTestHelper =
      resolver.playerStateChangeRegistration.callback(event)
      this

    /**
      * Processes an internal message on the message bus. Such messages are
      * produced by the test instance for different use cases. With this
      * method they can be processed without having to deal with them.
      *
      * @return this test helper
      */
    def processMessageOnBus(): ResolverTestHelper =
      messageBus.processNextMessage[Any]()
      this

    /**
      * Expects that the test instance sends another update message for
      * playlist metadata to all its consumers. The message is returned.
      *
      * @return the playlist metadata message received from the test instance
      */
    def nextMetaDataUpdate: PlaylistMetadata =
      processMessageOnBus()
      playlistMetaData

    /**
      * Expects that the specified metadata message is received from the test
      * instance.
      *
      * @param data the expected metadata
      * @return this test helper
      */
    def expectMetaDataUpdate(data: List[(MediaFileID, MediaMetadata)]): ResolverTestHelper =
      nextMetaDataUpdate should be(PlaylistMetadata(data.toMap))
      this

    /**
      * Checks that no message was sent on the message bus.
      *
      * @return this test helper
      */
    def expectNoMessageOnBus(): ResolverTestHelper =
      messageBus.expectNoMessage()
      this

    /**
      * Returns the number of updates that have been received from the test
      * resolver.
      *
      * @return the number of updates
      */
    def numberOfUpdates: Int = metaDataUpdateCount.get()

    /**
      * Removes the consumer registration used by this test helper.
      *
      * @return this test helper
      */
    def removeConsumerRegistration(): ResolverTestHelper =
      send(PlaylistMetadataUnregistration(componentID))
      this

    /**
      * Creates a resolver test instance.
      *
      * @return the test instance
      */
    private def createResolver(): PlaylistMetadataResolver =
      import system.dispatcher
      val res = new PlaylistMetadataResolver(metaDataActor, messageBus, RequestChunkSize,
        CacheSize, timeout)
      messageBus registerListener res.receive
      val consumerReg = PlaylistMetadataRegistration(id = componentID,
        callback = updateMetaData)
      res receive consumerReg
      playlistMetaData.data shouldBe empty // check initial consumer invocation
      res

    /**
      * Consumer function for metadata updates.
      *
      * @param d the current playlist metadata
      */
    private def updateMetaData(d: PlaylistMetadata): Unit =
      playlistMetaData = d
      metaDataUpdateCount.incrementAndGet()


/**
  * Data class defining a mapping for a metadata files requests to a response.
  *
  * @param request  the request
  * @param response the response to be returned for this request
  */
case class MetadataResponseMapping(request: GetFilesMetadata, response: FilesMetadataResponse)

/**
  * Data class storing information about a metadata request which cannot be
  * resolved yet.
  *
  * @param request the request
  * @param target  the sending actor
  */
case class PendingMetadataRequest(request: GetFilesMetadata, target: ActorRef)

/**
  * Actor implementation which simulates the metadata manager.
  *
  * An instance can be configured to answer specific requests for metadata
  * with provided responses.
  */
class MetadataActorTestImpl extends Actor:
  /** The configuration of requests and their responses. */
  private var requestMapping = Map.empty[GetFilesMetadata, FilesMetadataResponse]

  /** List with requests which cannot be resolved yet. */
  private var pendingRequests = List.empty[PendingMetadataRequest]

  override def receive: Receive =
    case MetadataResponseMapping(req, resp) =>
      requestMapping += req -> resp
      val (resolved, pending) = pendingRequests partition (_.request == req)
      pendingRequests = pending
      resolved foreach (_.target ! resp)

    case req: GetFilesMetadata =>
      requestMapping get req match
        case Some(response) =>
          sender() ! response
        case None =>
          pendingRequests = PendingMetadataRequest(req, sender()) :: pendingRequests
