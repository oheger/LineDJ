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

package de.oliver_heger.linedj.platform.audio.impl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import akka.util.Timeout
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistMetaData, PlaylistMetaDataRegistration, PlaylistMetaDataUnregistration}
import de.oliver_heger.linedj.platform.bus.{ComponentID, Identifiable}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object PlaylistMetaDataResolverSpec {
  /** The chunk size for meta data requests. */
  private val RequestChunkSize = 4

  /** Size of the meta data cache. */
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
    * Generates meta data for the test song with the given index.
    *
    * @param idx the index
    * @return meta data for this test song
    */
  private def metaData(idx: Int): MediaMetaData =
    MediaMetaData(title = Some("title" + idx))

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
    * Generates a map with meta data for the specified test files.
    *
    * @param ranges a sequence of ranges for the files in the map
    * @return a map containing test meta data for these files
    */
  private def metaDataMap(ranges: (Int, Int)*): Map[MediaFileID, MediaMetaData] = {
    val rangeLists = ranges map { r =>
      (r._1 to r._2) map (i => (fileID(i), metaData(i)))
    }
    rangeLists.flatten.toMap
  }

  /**
    * Generates a request for meta data for a number of files. The files are
    * specified as a sequence of from/to indices (in case there is no single
    * continuous range).
    *
    * @param seqNo  the sequence number for the request
    * @param ranges a sequence of ranges for files to be requested
    * @return the request
    */
  private def request(seqNo: Int, ranges: (Int, Int)*): GetFilesMetaData =
    GetFilesMetaData(files = ranges.flatMap(r => fileIDs(r._1, r._2)), seqNo)

  /**
    * Generates a response for a request for meta data. The response references
    * the given request and consists of meta data defined by the given index
    * ranges. Each range defines the from and to indices of a chunk of meta
    * data (in case that there is no single continuous range).
    *
    * @param request the referenced request
    * @param ranges  a sequence of ranges with meta data in the response
    * @return the response
    */
  private def response(request: GetFilesMetaData, ranges: (Int, Int)*): FilesMetaDataResponse =
    FilesMetaDataResponse(request, metaDataMap(ranges: _*))

  /**
    * Generates both a request and a response for meta data for a given set of
    * files. This is useful for responses that contain full meta data for all
    * requested files.
    *
    * @param seqNo  the sequence number for the request
    * @param ranges a sequence of ranges for files to be requested
    * @return a tuple with the request and the response
    */
  private def metaDataQuery(seqNo: Int, ranges: (Int, Int)*):
  (GetFilesMetaData, FilesMetaDataResponse) = {
    val req = request(seqNo, ranges: _*)
    val resp = response(req, ranges: _*)
    (req, resp)
  }

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
  AudioPlayerStateChangedEvent = {
    val playlist = Playlist(pendingSongs = pendingIDs, playedSongs = playedIDs)
    val state = AudioPlayerState(playlist = playlist, playbackActive = false,
      playlistClosed = false, playlistSeqNo = seqNo, playlistActivated = false)
    AudioPlayerStateChangedEvent(state)
  }

  /**
    * Completes the specified map by adding undefined meta data to the indices
    * which are not contained.
    *
    * @param data the original map
    * @param from the from index
    * @param to   the to index (including)
    * @return the filled map
    */
  private def fillUndefined(data: Map[MediaFileID, MediaMetaData], from: Int, to: Int):
  Map[MediaFileID, MediaMetaData] =
    (from to to).foldLeft(data) { (m, i) =>
      val key = fileID(i)
      if (m contains key) m else m + (key -> MediaMetaData())
    }
}

/**
  * Test class for ''PlaylistMetaDataResolver''.
  */
class PlaylistMetaDataResolverSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("PlaylistMetaDataResolverSpec"))

  import PlaylistMetaDataResolverSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A PlaylistMetaDataResolver" should "resolve meta data in a small playlist" in {
    val (req, resp) = metaDataQuery(0, (1, RequestChunkSize))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .expectMetaDataUpdate(resp.data)
  }

  it should "report the current meta data to new consumers" in {
    val (req, resp) = metaDataQuery(0, (1, 2))
    val playlistMetaData = PlaylistMetaData(resp.data)
    var metaDataReceived: PlaylistMetaData = null
    val reg = PlaylistMetaDataRegistration(ComponentID(), d => metaDataReceived = d)
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 2), Nil))
      .processMessageOnBus()
      .send(reg)
    metaDataReceived should be(playlistMetaData)
  }

  it should "use the chunk size when sending requests for meta data" in {
    val size = RequestChunkSize + 1
    val (req1, resp1) = metaDataQuery(0, (1, RequestChunkSize))
    val (req2, resp2) = metaDataQuery(0, (size, size))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, size), Nil))
      .expectMetaDataUpdate(resp1.data)
      .expectMetaDataUpdate(metaDataMap((1, size)))
  }

  it should "also resolve the files in the played list" in {
    val size = 2 * RequestChunkSize
    val playedFiles = RequestChunkSize + 1
    val (req1, resp1) = metaDataQuery(0, (playedFiles + 1, size), (1, 1))
    val (req2, resp2) = metaDataQuery(0, (2, playedFiles))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(playedFiles + 1, size),
        fileIDs(1, playedFiles)))
      .expectMetaDataUpdate(resp1.data)
      .expectMetaDataUpdate(metaDataMap((1, size)))
  }

  it should "handle files which cannot be resolved" in {
    val req = request(0, (1, RequestChunkSize))
    val resp = response(req, (1, 2))
    val data = fillUndefined(metaDataMap((1, 2)), 1, RequestChunkSize)
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .expectMetaDataUpdate(data)
  }

  it should "cache meta data that has already been resolved" in {
    val commonSize = 2
    val (req1, resp1) = metaDataQuery(0, (1, RequestChunkSize))
    val (req2, resp2) = metaDataQuery(1, (RequestChunkSize + 1, RequestChunkSize +
      (RequestChunkSize - commonSize)))
    val range2 = (commonSize + 1, commonSize + RequestChunkSize)
    val data = metaDataMap(range2)
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .processMessageOnBus()
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(range2._1, range2._2), Nil, seqNo = 2))
      .expectMetaDataUpdate(metaDataMap((commonSize + 1, RequestChunkSize)))
      .expectMetaDataUpdate(data)
  }

  it should "apply the restriction of the cache size" in {
    val range1 = (1, RequestChunkSize)
    val range2 = (RequestChunkSize + 1, 2 * RequestChunkSize)
    val (req1, resp1) = metaDataQuery(0, range1)
    val (req2, resp2) = metaDataQuery(1, range2)
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
  }

  it should "handle a timeout from the meta data actor" in {
    val data = (1 to RequestChunkSize).map(i => (fileID(i), MediaMetaData())).toMap
    val helper = new ResolverTestHelper(timeout = 100.millis)

    helper.sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .expectMetaDataUpdate(data)
  }

  it should "take over data from an outdated response if possible" in {
    val index = 2 * RequestChunkSize - 1
    val (req1, resp1) = metaDataQuery(0, (1, 2), (index, index))
    val req2 = request(1, (2, 2 + RequestChunkSize - 1))
    val resp2 = response(req2, (3, 3))
    val req3 = request(1, (2 + RequestChunkSize, 2 * RequestChunkSize))
    val resp3 = response(req3)
    val endData = fillUndefined(metaDataMap((2, 3), (index, index)),
      2, 2 * RequestChunkSize)
    val helper = new ResolverTestHelper

    helper
      .sendStateChangeEvent(playlistChangeEvent(List(fileID(1), fileID(2),
        fileID(index)), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(2, 2 * RequestChunkSize), Nil, seqNo = 2))
      .prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .prepareMetaDataRequest(req3, resp3)
      .expectMetaDataUpdate(metaDataMap((2, 2), (index, index)))
      .processMessageOnBus()
      .expectMetaDataUpdate(endData)
  }

  it should "ignore an outdated response if it does not bring new data" in {
    val (req1, resp1) = metaDataQuery(0, (1, 2))
    val (req2, resp2) = metaDataQuery(1, (3, 4))
    val helper = new ResolverTestHelper

    helper.sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 2), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(3, 4), Nil, seqNo = 2))
      .prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .processMessageOnBus()
      .expectMetaDataUpdate(metaDataMap((3, 4)))
    helper.numberOfUpdates should be(1)
  }

  it should "ignore a response if it does not bring new data" in {
    val (req1, resp1) = metaDataQuery(0, (1, 2))
    val (req2, resp2) = metaDataQuery(1, (1, 1))
    val helper = new ResolverTestHelper

    helper.sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 2), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 1), Nil, seqNo = 2))
      .prepareMetaDataRequest(req1, resp1)
      .expectMetaDataUpdate(metaDataMap((1, 1)))
      .prepareMetaDataRequest(req2, resp2)
      .processMessageOnBus()
    helper.numberOfUpdates should be(1)
  }

  it should "ignore a state update event that does not change the playlist" in {
    val (req1, resp1) = metaDataQuery(0, (1, 3))
    val req2 = request(1, (2, 3), (1, 1))
    val resp2 = FilesMetaDataResponse(req1, fillUndefined(Map.empty, 1, 3))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, 3), Nil))
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(2, 3), List(fileID(1))))
      .prepareMetaDataRequest(req1, resp1)
      .expectMetaDataUpdate(resp1.data)
  }

  it should "resolve the playlist anew when a meta data scan ends" in {
    val req1 = request(0, (1, RequestChunkSize))
    val resp1 = FilesMetaDataResponse(req1, fillUndefined(Map.empty, 1, RequestChunkSize))
    val (req2, resp2) = metaDataQuery(1, (1, RequestChunkSize))
    val helper = new ResolverTestHelper

    helper.prepareMetaDataRequest(req1, resp1)
      .prepareMetaDataRequest(req2, resp2)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .processMessageOnBus()
      .send(MetaDataScanCompleted)
      .expectMetaDataUpdate(resp2.data)
  }

  it should "handle a scan completed event before a playlist was set" in {
    val helper = new ResolverTestHelper

    helper.send(MetaDataScanCompleted)
      .expectNoMessageOnBus()
  }

  it should "handle a request to remove a consumer" in {
    val (req, resp) = metaDataQuery(0, (1, RequestChunkSize))
    val helper = new ResolverTestHelper

    helper.removeConsumerRegistration()
      .prepareMetaDataRequest(req, resp)
      .sendStateChangeEvent(playlistChangeEvent(fileIDs(1, RequestChunkSize), Nil))
      .processMessageOnBus()
      .numberOfUpdates should be(0)
  }

  it should "create a correct un-registration object for a registration" in {
    val id = ComponentID()
    val reg = PlaylistMetaDataRegistration(id, null)

    val unReg = reg.unRegistration
    unReg should be(PlaylistMetaDataUnregistration(id))
  }

  /**
    * Test helper class managing a test instance and all of its dependencies.
    *
    * @param timeout a timeout for actor requests
    */
  private class ResolverTestHelper(timeout: Timeout = RequestTimeout) extends Identifiable {
    /** The meta data manager simulator actor. */
    private val metaDataActor = system.actorOf(Props[MetaDataActorTestImpl])

    /** The test message bus. */
    private val messageBus = new MessageBusTestImpl

    /** A counter for the invocations of the consumer function. */
    private val metaDataUpdateCount = new AtomicInteger(-1)

    /** The instance to be tested. */
    private val resolver = createResolver()

    /** The meta data passed to the consumer function. */
    private var playlistMetaData: PlaylistMetaData = _

    /**
      * Prepares the test actor to handle a specific request for meta data.
      *
      * @param req  the request
      * @param resp the response to be sent for this request
      * @return this test helper
      */
    def prepareMetaDataRequest(req: GetFilesMetaData, resp: FilesMetaDataResponse):
    ResolverTestHelper = {
      metaDataActor ! MetaDataResponseMapping(req, resp)
      this
    }

    /**
      * Sends a message to the test instance via the message bus receiver
      * function.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): ResolverTestHelper = {
      resolver receive msg
      this
    }

    /**
      * Sends the specified state change event to the test instance.
      *
      * @param event the event
      * @return this test helper
      */
    def sendStateChangeEvent(event: AudioPlayerStateChangedEvent): ResolverTestHelper = {
      resolver.playerStateChangeRegistration.callback(event)
      this
    }

    /**
      * Processes an internal message on the message bus. Such messages are
      * produced by the test instance for different use cases. With this
      * method they can be processed without having to deal with them.
      *
      * @return this test helper
      */
    def processMessageOnBus(): ResolverTestHelper = {
      messageBus.processNextMessage[Any]()
      this
    }

    /**
      * Expects that the test instance sends another update message for
      * playlist meta data to all its consumers. The message is returned.
      *
      * @return the playlist meta data message received from the test instance
      */
    def nextMetaDataUpdate: PlaylistMetaData = {
      processMessageOnBus()
      playlistMetaData
    }

    /**
      * Expects that the specified meta data message is received from the test
      * instance.
      *
      * @param data the expected meta data
      * @return this test helper
      */
    def expectMetaDataUpdate(data: Map[MediaFileID, MediaMetaData]): ResolverTestHelper = {
      nextMetaDataUpdate should be(PlaylistMetaData(data))
      this
    }

    /**
      * Checks that no message was sent on the message bus.
      *
      * @return this test helper
      */
    def expectNoMessageOnBus(): ResolverTestHelper = {
      messageBus.expectNoMessage()
      this
    }

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
    def removeConsumerRegistration(): ResolverTestHelper = {
      send(PlaylistMetaDataUnregistration(componentID))
      this
    }

    /**
      * Creates a resolver test instance.
      *
      * @return the test instance
      */
    private def createResolver(): PlaylistMetaDataResolver = {
      import system.dispatcher
      val res = new PlaylistMetaDataResolver(metaDataActor, messageBus, RequestChunkSize,
        CacheSize, timeout)
      messageBus registerListener res.receive
      val consumerReg = PlaylistMetaDataRegistration(id = componentID,
        callback = updateMetaData)
      res receive consumerReg
      playlistMetaData.data shouldBe 'empty // check initial consumer invocation
      res
    }

    /**
      * Consumer function for meta data updates.
      *
      * @param d the current playlist meta data
      */
    private def updateMetaData(d: PlaylistMetaData): Unit = {
      playlistMetaData = d
      metaDataUpdateCount.incrementAndGet()
    }
  }

}

/**
  * Data class defining a mapping for a meta data files requests to a response.
  *
  * @param request  the request
  * @param response the response to be returned for this request
  */
case class MetaDataResponseMapping(request: GetFilesMetaData, response: FilesMetaDataResponse)

/**
  * Data class storing information about a meta data request which cannot be
  * resolved yet.
  *
  * @param request the request
  * @param target  the sending actor
  */
case class PendingMetaDataRequest(request: GetFilesMetaData, target: ActorRef)

/**
  * Actor implementation which simulates the meta data manager.
  *
  * An instance can be configured to answer specific requests for meta data
  * with provided responses.
  */
class MetaDataActorTestImpl extends Actor {
  /** The configuration of requests and their responses. */
  private var requestMapping = Map.empty[GetFilesMetaData, FilesMetaDataResponse]

  /** List with requests which cannot be resolved yet. */
  private var pendingRequests = List.empty[PendingMetaDataRequest]

  override def receive: Receive = {
    case MetaDataResponseMapping(req, resp) =>
      requestMapping += req -> resp
      val (resolved, pending) = pendingRequests partition (_.request == req)
      pendingRequests = pending
      resolved foreach (_.target ! resp)

    case req: GetFilesMetaData =>
      requestMapping get req match {
        case Some(response) =>
          sender ! response
        case None =>
          pendingRequests = PendingMetaDataRequest(req, sender()) :: pendingRequests
      }
  }
}
