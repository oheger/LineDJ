/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.file.Paths

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object MediaScanStateUpdateServiceSpec {
  /** The name of the test archive. */
  private val ArchiveName = "TestLocalArchive"

  /**
    * Generates the URI for the test medium with the given index.
    *
    * @param idx the index
    * @return the URI for this test medium
    */
  private def mediumUri(idx: Int): String = "medium" + idx

  /**
    * Generates a test medium ID with the given index.
    *
    * @param idx the index
    * @return the medium ID with this index
    */
  private def mediumID(idx: Int): MediumID =
    MediumID(mediumUri(idx), Some(s"playlist$idx.settings"), ArchiveName)

  /**
    * Generates a checksum for the test medium with the given index.
    *
    * @param idx the index
    * @return the checksum for this test medium
    */
  private def checkSum(idx: Int): String = "check_" + mediumUri(idx)

  /**
    * Generates a test scan result which contains data about a test medium with
    * the given index.
    *
    * @param idx the index
    * @return the test result object
    */
  private def scanResult(idx: Int): EnhancedMediaScanResult = {
    val mid = mediumID(idx)
    val files = (1 to idx).map(i => FileData(s"testSong$i.mp3", i * 42)).toList
    val scanResult = MediaScanResult(Paths.get(mid.mediumURI), Map(mid -> files))
    val uriMapping = files.foldLeft(Map.empty[String, FileData])((m, f) => {
      m + (s"file://${f.path}" -> f)
    })
    EnhancedMediaScanResult(scanResult = scanResult, fileUriMapping = uriMapping,
      checksumMapping = Map(mid -> checkSum(idx)))
  }

  /**
    * Generates a test medium info object with the given index.
    *
    * @param idx          the index
    * @param withChecksum flag whether a valid checksum should be contained
    * @return the test medium info
    */
  private def mediumInfo(idx: Int, withChecksum: Boolean = false): MediumInfo = {
    val mid = mediumID(idx)
    val cs = if (withChecksum) checkSum(idx) else ""
    MediumInfo(name = "Medium " + mid.mediumURI, mediumID = mid, description = "",
      checksum = cs, orderMode = "", orderParams = "")
  }

  /**
    * Creates a map with medium information for the specified test medium.
    *
    * @param idx          the index of the test medium
    * @param withChecksum flag whether a valid checksum should be contained
    * @return the map with medium information
    */
  private def mediaMap(idx: Int, withChecksum: Boolean = false): Map[MediumID, MediumInfo] = {
    val info = mediumInfo(idx, withChecksum)
    Map(info.mediumID -> info)
  }

  /**
    * Generates a map with medium information for the specified test media.
    *
    * @param withChecksum flag whether a valid checksum should be contained
    * @param indices      the indices of the test media to include
    * @return the map with the test media
    */
  private def multiMediaMap(withChecksum: Boolean, indices: Int*): Map[MediumID, MediumInfo] =
    indices.foldLeft(Map.empty[MediumID, MediumInfo]) { (map, i) =>
      map ++ mediaMap(i, withChecksum)
    }

  /**
    * Generates a combined result object that contains only the test medium
    * with the given ID.
    *
    * @param idx the index of the test medium
    * @return the combined result object
    */
  private def combinedResult(idx: Int): CombinedMediaScanResult =
    CombinedMediaScanResult(scanResult(idx), mediaMap(idx))

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: MediaScanStateUpdateServiceImpl.StateUpdate[A],
                             oldState: MediaScanState =
                             MediaScanStateUpdateServiceImpl.InitialState):
  (MediaScanState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: MediaScanStateUpdateServiceImpl.StateUpdate[Unit],
                          oldState: MediaScanState = MediaScanStateUpdateServiceImpl.InitialState):
  MediaScanState = {
    val (next, _) = updateState(s, oldState)
    next
  }
}

/**
  * Test class for ''MediaScanStateUpdateService''.
  */
class MediaScanStateUpdateServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("MediaScanStateUpdateServiceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import MediaScanStateUpdateServiceSpec._

  /**
    * Returns a test actor reference.
    *
    * @return the actor reference
    */
  private def actor(): ActorRef = TestProbe().ref

  "A MediaScanStateUpdateService" should "define a valid initial state" in {
    val s = MediaScanStateUpdateServiceImpl.InitialState
    s.scanInProgress shouldBe false
    s.removeState should be(UnionArchiveRemoveState.Removed)
    s.startAnnounced shouldBe false
    s.seqNo should be(0)
    s.fileData should have size 0
    s.mediaData should have size 0
    s.ackPending shouldBe 'empty
    s.ackMetaManager shouldBe true
    s.currentResults should have size 0
    s.currentMediaData should have size 0
    s.availableMediaSent shouldBe true
  }

  it should "handle a start scan request if a scan is already in progress" in {
    val root = Paths get "someRoot"
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.triggerStartScan(root), state)
    next should be(state)
    msg shouldBe 'empty
  }

  it should "support triggering a new scan operation" in {
    val root = Paths get "myArchiveRoot"
    val res = combinedResult(1)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(seqNo = 11,
      fileData = Map(mediumID(1) -> res.result.fileUriMapping),
      mediaData = res.info)
    val expMsg = MediaScannerActor.ScanPath(root, state.seqNo)

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.triggerStartScan(root), state)
    next.scanInProgress shouldBe true
    next.fileData should have size 0
    next.mediaData should have size 0
    next.removeState should be(UnionArchiveRemoveState.Initial)
    msg should be(Some(expMsg))
  }

  it should "set the correct remove state for the initial scan operation" in {
    val (next, _) = updateState(MediaScanStateUpdateServiceImpl.triggerStartScan(
      Paths get "foo"))

    next.removeState should be(UnionArchiveRemoveState.Removed)
  }

  it should "handle a query for start messages if no scan is in progress" in {
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName))

    next should be(MediaScanStateUpdateServiceImpl.InitialState)
    msg should be(ScanStateTransitionMessages())
  }

  it should "handle a query for start messages if no data is in the union archive" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName),
      state)

    next.startAnnounced shouldBe true
    next.ackMetaManager shouldBe false
    msg.ack shouldBe 'empty
    msg.unionArchiveMessage shouldBe 'empty
    msg.metaManagerMessage should be(Some(MediaScanStarts))
  }

  it should "handle a query for start messages if data has to be removed from union archive" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      removeState = UnionArchiveRemoveState.Initial)
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName),
      state)

    next.startAnnounced shouldBe false
    next.ackMetaManager shouldBe true
    next.removeState should be(UnionArchiveRemoveState.Pending)
    msg.ack shouldBe 'empty
    msg.metaManagerMessage shouldBe 'empty
    msg.unionArchiveMessage should be(Some(ArchiveComponentRemoved(ArchiveName)))
  }

  it should "handle a query for start messages if the remove state is Pending" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      removeState = UnionArchiveRemoveState.Pending)
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName),
      state)

    next should be(state)
    msg should be(ScanStateTransitionMessages())
  }

  it should "only announce the start of a scan operation once" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      startAnnounced = true)
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName),
      state)

    next should be(state)
    msg should be(ScanStateTransitionMessages())
  }

  it should "update the union archive removal state" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(
      removeState = UnionArchiveRemoveState.Pending)
    val next = modifyState(MediaScanStateUpdateServiceImpl.removedFromUnionArchive(), state)

    next.removeState should be(UnionArchiveRemoveState.Removed)
  }

  it should "only update the union archive removal state if allowed" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(
      removeState = UnionArchiveRemoveState.Initial)
    val next = modifyState(MediaScanStateUpdateServiceImpl.removedFromUnionArchive(), state)

    next should be(state)
  }

  it should "react on an ACK from the meta data manager" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(ackMetaManager = false)
    val next = modifyState(MediaScanStateUpdateServiceImpl.ackFromMetaManager(), state)

    next.ackMetaManager shouldBe true
  }

  it should "ignore new results if an ACK is still pending" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      currentResults = List(scanResult(1)), ackPending = Some(actor()))
    val newResults = ScanSinkActor.CombinedResults(List(combinedResult(2)), state.seqNo)
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(newResults,
      actor()), state)

    next should be(state)
  }

  it should "process new results" in {
    val res1 = combinedResult(1)
    val res2 = combinedResult(2)
    val res3 = combinedResult(3)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      mediaData = mediaMap(1, withChecksum = true),
      fileData = Map(mediumID(1) -> res1.result.fileUriMapping))
    val newResults = List(res2, res3)
    val expFileData = state.fileData ++ Map(mediumID(2) -> res2.result.fileUriMapping) ++
      Map(mediumID(3) -> res3.result.fileUriMapping)
    val expMediaData = multiMediaMap(withChecksum = true, 1, 2, 3)
    val sender = actor()
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = sender,
      results = ScanSinkActor.CombinedResults(newResults, state.seqNo)), state)

    next.ackPending should be(Some(sender))
    next.mediaData should be(expMediaData)
    next.fileData should be(expFileData)
    next.currentResults should be(List(res2.result, res3.result))
    next.currentMediaData should be(multiMediaMap(withChecksum = true, 2, 3))
  }

  it should "process new results with multiple media per scan result" in {
    val res1 = scanResult(1)
    val res2 = scanResult(2)
    val res = EnhancedMediaScanResult(scanResult = res1.scanResult.copy(mediaFiles =
      res1.scanResult.mediaFiles ++ res2.scanResult.mediaFiles),
      checksumMapping = Map(mediumID(1) -> checkSum(1), mediumID(2) -> checkSum(2)),
      fileUriMapping = res1.fileUriMapping ++ res2.fileUriMapping)
    val info = multiMediaMap( withChecksum = true, 1, 2)
    val combinedRes = CombinedMediaScanResult(res, info)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = actor(),
      results = ScanSinkActor.CombinedResults(List(combinedRes), state.seqNo)), state)

    next.currentResults should contain only res
    next.mediaData should be(info)
    next.fileData should have size 2
    next.fileData(mediumID(1)) should be(res1.fileUriMapping)
    next.fileData(mediumID(2)) should be(res2.fileUriMapping)
  }

  it should "process new results with incomplete checksum mapping" in {
    val mid = mediumID(1)
    val result = combinedResult(1)
    val esr = result.result.copy(checksumMapping = Map.empty)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)
    val combinedResults = ScanSinkActor.CombinedResults(seqNo = state.seqNo,
      results = List(CombinedMediaScanResult(esr, result.info)))
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = actor(),
      results = combinedResults))

    next.currentMediaData(mid).checksum should be(MediaScanStateUpdateServiceImpl.UndefinedChecksum)
    next.mediaData(mid).checksum should be(MediaScanStateUpdateServiceImpl.UndefinedChecksum)
  }

  it should "ignore new results if the sequence number does not match" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = actor(),
      results = ScanSinkActor.CombinedResults(List(combinedResult(1)), state.seqNo + 1)),
      state)

    next should be(state)
  }

  it should "handle a request for an actor to ACK if none is pending" in {
    val (next, ack) = updateState(MediaScanStateUpdateServiceImpl.actorToAck())

    ack shouldBe 'empty
    next should be theSameInstanceAs MediaScanStateUpdateServiceImpl.InitialState
  }

  it should "return the actor to ACK if possible" in {
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(ackPending = Some(ack),
      ackMetaManager = false)

    val (next, optAck) = updateState(MediaScanStateUpdateServiceImpl.actorToAck(), state)
    optAck should be(Some(ack))
    next.ackPending shouldBe 'empty
  }

  it should "not return the actor to ACK if there are current results" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(ackPending = Some(actor()),
      currentResults = List(scanResult(1)))

    val (next, optAck) = updateState(MediaScanStateUpdateServiceImpl.actorToAck(), state)
    optAck shouldBe 'empty
    next should be theSameInstanceAs state
  }

  it should "not return the actor to ACK if there is current media information" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(ackPending = Some(actor()),
      currentMediaData = mediaMap(2))

    val (next, optAck) = updateState(MediaScanStateUpdateServiceImpl.actorToAck(), state)
    optAck shouldBe 'empty
    next should be theSameInstanceAs state
  }

  it should "return an available media message to the meta data manager" in {
    val media = multiMediaMap(withChecksum = true, 1, 2)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      availableMediaSent = false, mediaData = media, ackMetaManager = false)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next.availableMediaSent shouldBe true
    next.mediaData should have size 0
    optMsg should be(Some(AvailableMedia(media)))
  }

  it should "return a scan start message to the meta data manager" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next.startAnnounced shouldBe true
    optMsg should be(Some(MediaScanStarts))
  }

  it should "return the next scan result as message to the meta data manager" in {
    val res1 = scanResult(1)
    val res2 = scanResult(2)
    val res3 = scanResult(3)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      startAnnounced = true, currentResults = List(res1, res2, res3))

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next.currentResults should contain only(res2, res3)
    next.ackMetaManager shouldBe false
    optMsg should be(Some(res1))
  }

  it should "not return a meta data message if there is no current result" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      startAnnounced = true, currentResults = List())

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next should be(state)
    optMsg shouldBe 'empty
  }

  it should "not return a meta data message if ACK from the manager is pending" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      startAnnounced = true, currentResults = List(scanResult(5)), ackMetaManager = false)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next should be(state)
    optMsg shouldBe 'empty
  }

  it should "not return a meta data message if a remove operation is pending" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      startAnnounced = true, currentResults = List(scanResult(5)),
      removeState = UnionArchiveRemoveState.Pending)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next should be(state)
    optMsg shouldBe 'empty
  }

  it should "not return a union archive message if no current media data is present" in {
    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl
      .unionArchiveMessage(ArchiveName))

    next should be theSameInstanceAs MediaScanStateUpdateServiceImpl.InitialState
    optMsg shouldBe 'empty
  }

  it should "return a union archive message if there is current meta data" in {
    val media = mediaMap(42)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(currentMediaData = media)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl
      .unionArchiveMessage(ArchiveName), state)
    next.currentMediaData should have size 0
    optMsg should be(Some(AddMedia(media, ArchiveName, None)))
  }

  it should "update the state when the scan is complete" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      seqNo = 28)

    val next = modifyState(MediaScanStateUpdateServiceImpl.scanComplete(state.seqNo), state)
    next.scanInProgress shouldBe false
    next.availableMediaSent shouldBe false
    next.seqNo should not be state.seqNo
  }

  it should "ignore a scan complete notification with a wrong sequence number" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      seqNo = 15)

    val next = modifyState(MediaScanStateUpdateServiceImpl.scanComplete(state.seqNo + 1), state)
    next should be theSameInstanceAs state
  }

  it should "updated the state if the scan operation was canceled" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(currentMediaData = mediaMap(1),
      removeState = UnionArchiveRemoveState.Pending,
      currentResults = List(scanResult(1), scanResult(2)),
      mediaData = multiMediaMap(withChecksum = true, 3, 4),
      availableMediaSent = false)

    val next = modifyState(MediaScanStateUpdateServiceImpl.scanCanceled(), state)
    next.removeState should be(UnionArchiveRemoveState.Initial)
    next.currentMediaData should have size 0
    next.currentResults should have size 0
    next.mediaData should have size 0
    next.availableMediaSent shouldBe true
  }

  it should "handle a confirmation of removed data from the union archive" in {
    val result = scanResult(1)
    val media = mediaMap(1)
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      currentMediaData = media, currentResults = List(result), ackPending = Some(ack),
      startAnnounced = true)

    val (next, msg) =
      updateState(MediaScanStateUpdateServiceImpl.handleRemovedFromUnionArchive(ArchiveName),
        state)
    next.currentResults should have size 0
    next.currentMediaData should have size 0
    next.ackPending shouldBe 'empty
    msg should be(ScanStateTransitionMessages(Some(AddMedia(media, ArchiveName, None)),
      Some(result), Some(ack)))
  }

  it should "handle the arrival of new results" in {
    val res1 = combinedResult(1)
    val res2 = combinedResult(2)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      startAnnounced = true)
    val newResults = List(res1, res2)
    val expMediaData = multiMediaMap(withChecksum = true, 1, 2)
    val sender = actor()

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.handleResultsReceived(sender =
      sender,
      results = ScanSinkActor.CombinedResults(newResults, state.seqNo),
      archiveName = ArchiveName), state)
    next.ackPending should be(Some(sender))
    next.currentResults should contain only res2.result
    next.currentMediaData should have size 0
    msg.unionArchiveMessage should be(Some(AddMedia(expMediaData, ArchiveName, None)))
    msg.metaManagerMessage should be(Some(res1.result))
    msg.ack shouldBe 'empty
  }

  it should "handle an ACK from the meta data manager" in {
    val result = scanResult(1)
    val media = mediaMap(1)
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      currentMediaData = media, currentResults = List(result), ackPending = Some(ack),
      startAnnounced = true, ackMetaManager = false)

    val (next, msg) =
      updateState(MediaScanStateUpdateServiceImpl.handleAckFromMetaManager(ArchiveName), state)
    next.currentResults should have size 0
    next.currentMediaData should have size 0
    next.ackMetaManager shouldBe false
    next.ackPending shouldBe 'empty
    msg should be(ScanStateTransitionMessages(Some(AddMedia(media, ArchiveName, None)),
      Some(result), Some(ack)))
  }

  it should "handle a completed scan operation" in {
    val mediaData = multiMediaMap(withChecksum = true, 2, 4, 8, 16)
    val currentMedia = mediaMap(16)
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      mediaData = mediaData, ackPending = Some(ack), currentMediaData = currentMedia, seqNo = 4)

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.handleScanComplete(state.seqNo,
      ArchiveName), state)
    next.availableMediaSent shouldBe true
    next.mediaData should have size 0
    next.ackPending shouldBe 'empty
    msg.metaManagerMessage should be(Some(AvailableMedia(mediaData)))
    msg.ack should be(Some(ack))
    msg.unionArchiveMessage should be(Some(AddMedia(currentMedia, ArchiveName, None)))
  }

  it should "handle a canceled scan operation" in {
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      mediaData = multiMediaMap(withChecksum = true, 1, 2), ackPending = Some(ack),
      currentMediaData = mediaMap(1), currentResults = List(scanResult(2)))

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.handleScanCanceled(), state)
    next.mediaData should have size 0
    next.currentMediaData should have size 0
    next.currentResults should have size 0
    next.ackPending shouldBe 'empty
    msg should be(ScanStateTransitionMessages(ack = Some(ack)))
  }
}
