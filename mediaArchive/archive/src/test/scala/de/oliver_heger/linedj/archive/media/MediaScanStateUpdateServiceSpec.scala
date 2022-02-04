/*
 * Copyright 2015-2022 The Developers Team.
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

import java.nio.file.{Path, Paths}
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileUri, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object MediaScanStateUpdateServiceSpec {
  /** The name of the test archive. */
  private val ArchiveName = "TestLocalArchive"

  /** Prefix for the URI of a media file. */
  private val UriPrefix = "song://"

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
  private def checkSum(idx: Int): MediumChecksum = MediumChecksum("check_" + mediumUri(idx))

  /**
    * A function to convert paths to URIs; this is used by tests for result
    * processing.
    *
    * @param path the path
    * @return the URI for this path
    */
  private def uriForPath(path: Path): MediaFileUri = MediaFileUri(UriPrefix + path.toString)

  /**
    * Generates the name of a test song on a given test medium.
    *
    * @param mediumIdx the index of the medium
    * @param songIdx   the index of the song
    * @return the name of this test song
    */
  private def songName(mediumIdx: Int, songIdx: Int): String = s"medium$mediumIdx-testSong$songIdx.mp3"

  /**
    * Generates the path for a test song on a given test medium.
    *
    * @param mediumIdx the index of the medium
    * @param songIdx   the index of the song
    * @return the ''Path'' pointing to this test song
    */
  private def songPath(mediumIdx: Int, songIdx: Int): Path = Paths get songName(mediumIdx, songIdx)

  /**
    * Generates the URI for a test song on a given test medium.
    *
    * @param mediumIdx the index of the medium
    * @param songIdx   the index of the song
    * @return the URI referencing this test song
    */
  private def songUri(mediumIdx: Int, songIdx: Int): MediaFileUri = MediaFileUri(songName(mediumIdx, songIdx))

  /**
    * Generates a test scan result which contains data about a test medium with
    * the given index.
    *
    * @param idx the index
    * @return the test result object
    */
  private def scanResult(idx: Int): EnhancedMediaScanResult = {
    val mid = mediumID(idx)
    val files = (1 to idx).map(i => FileData(songPath(idx, i), i * 42)).toList
    val scanResult = MediaScanResult(Paths.get(mid.mediumURI), Map(mid -> files))
    EnhancedMediaScanResult(scanResult = scanResult, fileUriMapping = Map.empty,
      checksumMapping = Map(mid -> checkSum(idx)))
  }

  /**
    * Generates a set with the URIs of the test songs contained on the given
    * test medium.
    *
    * @param mediumIdx the index of the medium
    * @return a set with the URIs of the test songs on this medium
    */
  private def mediumFileUris(mediumIdx: Int): Set[MediaFileUri] =
    (1 to mediumIdx).map(songIdx => songUri(mediumIdx, songIdx)).toSet

  /**
    * Generates a set with URIs for the test songs on the given test medium
    * that have been produced by the conversion function.
    *
    * @param mediumIdx the index of the medium
    * @return a set with URIs of test songs after applying the conversion
    *         function
    */
  private def convertedMediumFileUris(mediumIdx: Int): Set[MediaFileUri] =
    scanResult(mediumIdx).scanResult.mediaFiles.flatMap(_._2).map(file => uriForPath(file.path)).toSet

  /**
    * Generates a test medium info object with the given index.
    *
    * @param idx          the index
    * @param withChecksum flag whether a valid checksum should be contained
    * @return the test medium info
    */
  private def mediumInfo(idx: Int, withChecksum: Boolean = false): MediumInfo = {
    val mid = mediumID(idx)
    val cs = if (withChecksum) checkSum(idx) else MediumChecksum.Undefined
    MediumInfo(name = "Medium " + mid.mediumURI, mediumID = mid, description = "",
      checksum = cs.checksum, orderMode = "", orderParams = "")
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
    * @param indices the indices of the test media to include
    * @return the map with the test media
    */
  private def multiMediaMap(indices: Int*): Map[MediumID, MediumInfo] =
    indices.foldLeft(Map.empty[MediumID, MediumInfo]) { (map, i) =>
      map ++ mediaMap(i, withChecksum = true)
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
class MediaScanStateUpdateServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
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

  /**
    * Returns a state object with a scan operation in progress.
    *
    * @return the state
    */
  private def stateInProgress(): MediaScanState =
    MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()))

  "A MediaScanStateUpdateService" should "define a valid initial state" in {
    val s = MediaScanStateUpdateServiceImpl.InitialState
    s.scanClient shouldBe empty
    s.scanInProgress shouldBe false
    s.removeState should be(UnionArchiveRemoveState.Removed)
    s.startAnnounced shouldBe false
    s.seqNo should be(0)
    s.fileData should have size 0
    s.mediaData should have size 0
    s.ackPending shouldBe empty
    s.ackMetaManager shouldBe true
    s.currentResults should have size 0
    s.currentMediaData should have size 0
    s.availableMediaSent shouldBe true
  }

  it should "handle a start scan request if a scan is already in progress" in {
    val root = Paths get "someRoot"
    val state = stateInProgress()

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.triggerStartScan(root, actor()), state)
    next should be(state)
    msg shouldBe empty
  }

  it should "support triggering a new scan operation" in {
    val root = Paths get "myArchiveRoot"
    val res = combinedResult(1)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(seqNo = 11,
      fileData = Map(mediumID(5) -> mediumFileUris(5)),
      mediaData = res.info.toList, startAnnounced = true)
    val client = actor()
    val expMsg = MediaScannerActor.ScanPath(root, state.seqNo)

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.triggerStartScan(root, client), state)
    next.scanInProgress shouldBe true
    next.scanClient should be(Some(client))
    next.fileData should have size 0
    next.mediaData should have size 0
    next.removeState should be(UnionArchiveRemoveState.Initial)
    next.startAnnounced shouldBe false
    msg should be(Some(expMsg))
  }

  it should "set the correct remove state for the initial scan operation" in {
    val (next, _) = updateState(MediaScanStateUpdateServiceImpl.triggerStartScan(
      Paths get "foo", actor()))

    next.removeState should be(UnionArchiveRemoveState.Removed)
  }

  it should "handle a query for start messages if no scan is in progress" in {
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName))

    next should be(MediaScanStateUpdateServiceImpl.InitialState)
    msg should be(ScanStateTransitionMessages())
  }

  it should "handle a query for start messages if no data is in the union archive" in {
    val state = stateInProgress()
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName),
      state)

    next.startAnnounced shouldBe true
    next.ackMetaManager shouldBe false
    msg.ack shouldBe empty
    msg.unionArchiveMessage shouldBe empty
    msg.metaManagerMessage should be(Some(MediaScanStarts(state.scanClient.get)))
  }

  it should "handle a query for start messages if data has to be removed from union archive" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      removeState = UnionArchiveRemoveState.Initial)
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName),
      state)

    next.startAnnounced shouldBe false
    next.ackMetaManager shouldBe true
    next.removeState should be(UnionArchiveRemoveState.Pending)
    msg.ack shouldBe empty
    msg.metaManagerMessage shouldBe empty
    msg.unionArchiveMessage should be(Some(ArchiveComponentRemoved(ArchiveName)))
  }

  it should "handle a query for start messages if the remove state is Pending" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      removeState = UnionArchiveRemoveState.Pending)
    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.startScanMessages(ArchiveName),
      state)

    next should be(state)
    msg should be(ScanStateTransitionMessages())
  }

  it should "only announce the start of a scan operation once" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
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
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      currentResults = List(scanResult(1)), ackPending = Some(actor()))
    val newResults = ScanSinkActor.CombinedResults(List(combinedResult(2)), state.seqNo)
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(newResults, actor())(uriForPath), state)

    next should be(state)
  }

  it should "process new results" in {
    val res1 = combinedResult(2)
    val res2 = combinedResult(3)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      mediaData = mediaMap(1, withChecksum = true).toList,
      fileData = Map(mediumID(1) -> mediumFileUris(1)))
    val newResults = List(res1, res2)
    val expFileData = state.fileData ++ Map(mediumID(2) -> convertedMediumFileUris(2)) ++
      Map(mediumID(3) -> convertedMediumFileUris(3))
    val expMediaData = multiMediaMap(1, 2, 3).toList
    val sender = actor()
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = sender,
      results = ScanSinkActor.CombinedResults(newResults, state.seqNo))(uriForPath), state)

    next.ackPending should be(Some(sender))
    next.mediaData should be(expMediaData)
    next.fileData should be(expFileData)
    next.currentResults should be(List(res1.result, res2.result))
    next.currentMediaData should be(multiMediaMap(2, 3))
  }

  it should "process new results with multiple media per scan result" in {
    val res1 = scanResult(1)
    val res2 = scanResult(2)
    val res = EnhancedMediaScanResult(scanResult = res1.scanResult.copy(mediaFiles =
      res1.scanResult.mediaFiles ++ res2.scanResult.mediaFiles),
      checksumMapping = Map(mediumID(1) -> checkSum(1), mediumID(2) -> checkSum(2)),
      fileUriMapping = res1.fileUriMapping ++ res2.fileUriMapping)
    val info = multiMediaMap(1, 2)
    val combinedRes = CombinedMediaScanResult(res, info)
    val state = stateInProgress()
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = actor(),
      results = ScanSinkActor.CombinedResults(List(combinedRes), state.seqNo))(uriForPath), state)

    next.currentResults should contain only res
    next.mediaData should be(info.toList)
    next.fileData should have size 2
    next.fileData(mediumID(1)) should be(convertedMediumFileUris(1))
    next.fileData(mediumID(2)) should be(convertedMediumFileUris(2))
  }

  it should "process new results with incomplete checksum mapping" in {
    val mid = mediumID(1)
    val result = combinedResult(1)
    val esr = result.result.copy(checksumMapping = Map.empty)
    val state = stateInProgress()
    val combinedResults = ScanSinkActor.CombinedResults(seqNo = state.seqNo,
      results = List(CombinedMediaScanResult(esr, result.info)))
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = actor(),
      results = combinedResults)(uriForPath))

    next.currentMediaData(mid).checksum should be(MediaScanStateUpdateServiceImpl.UndefinedChecksum)
    next.mediaData.find(_._1 == mid).get._2.checksum should be(MediaScanStateUpdateServiceImpl.UndefinedChecksum)
  }

  it should "ignore new results if the sequence number does not match" in {
    val state = stateInProgress()
    val next = modifyState(MediaScanStateUpdateServiceImpl.resultsReceived(sender = actor(),
      results = ScanSinkActor.CombinedResults(List(combinedResult(1)), state.seqNo + 1))(uriForPath), state)

    next should be(state)
  }

  it should "handle a request for an actor to ACK if none is pending" in {
    val (next, ack) = updateState(MediaScanStateUpdateServiceImpl.actorToAck())

    ack shouldBe empty
    next should be theSameInstanceAs MediaScanStateUpdateServiceImpl.InitialState
  }

  it should "return the actor to ACK if possible" in {
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(ackPending = Some(ack),
      ackMetaManager = false)

    val (next, optAck) = updateState(MediaScanStateUpdateServiceImpl.actorToAck(), state)
    optAck should be(Some(ack))
    next.ackPending shouldBe empty
  }

  it should "not return the actor to ACK if there are current results" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(ackPending = Some(actor()),
      currentResults = List(scanResult(1)))

    val (next, optAck) = updateState(MediaScanStateUpdateServiceImpl.actorToAck(), state)
    optAck shouldBe empty
    next should be theSameInstanceAs state
  }

  it should "not return the actor to ACK if there is current media information" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(ackPending = Some(actor()),
      currentMediaData = mediaMap(2))

    val (next, optAck) = updateState(MediaScanStateUpdateServiceImpl.actorToAck(), state)
    optAck shouldBe empty
    next should be theSameInstanceAs state
  }

  it should "return an available media message to the meta data manager" in {
    val media = multiMediaMap(1, 2).toList
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      availableMediaSent = false, mediaData = media, ackMetaManager = false)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next.availableMediaSent shouldBe true
    next.mediaData should have size 0
    optMsg should be(Some(AvailableMedia(media)))
  }

  it should "return a scan start message to the meta data manager" in {
    val state = stateInProgress()

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next.startAnnounced shouldBe true
    optMsg should be(Some(MediaScanStarts(state.scanClient.get)))
  }

  it should "return the next scan result as message to the meta data manager" in {
    val res1 = scanResult(1)
    val res2 = scanResult(2)
    val res3 = scanResult(3)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      startAnnounced = true, currentResults = List(res1, res2, res3))

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next.currentResults should contain only(res2, res3)
    next.ackMetaManager shouldBe false
    optMsg should be(Some(res1))
  }

  it should "not return a meta data message if there is no current result" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      startAnnounced = true, currentResults = List())

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next should be(state)
    optMsg shouldBe empty
  }

  it should "not return a meta data message if ACK from the manager is pending" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      startAnnounced = true, currentResults = List(scanResult(5)), ackMetaManager = false)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next should be(state)
    optMsg shouldBe empty
  }

  it should "not return a meta data message if a remove operation is pending" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      startAnnounced = true, currentResults = List(scanResult(5)),
      removeState = UnionArchiveRemoveState.Pending)

    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl.metaDataMessage(), state)
    next should be(state)
    optMsg shouldBe empty
  }

  it should "not return a union archive message if no current media data is present" in {
    val (next, optMsg) = updateState(MediaScanStateUpdateServiceImpl
      .unionArchiveMessage(ArchiveName))

    next should be theSameInstanceAs MediaScanStateUpdateServiceImpl.InitialState
    optMsg shouldBe empty
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
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      seqNo = 28)

    val next = modifyState(MediaScanStateUpdateServiceImpl.scanComplete(state.seqNo), state)
    next.scanClient shouldBe empty
    next.scanInProgress shouldBe false
    next.availableMediaSent shouldBe false
    next.seqNo should not be state.seqNo
  }

  it should "ignore a scan complete notification with a wrong sequence number" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      seqNo = 15)

    val next = modifyState(MediaScanStateUpdateServiceImpl.scanComplete(state.seqNo + 1), state)
    next should be theSameInstanceAs state
  }

  it should "updated the state if the scan operation was canceled" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(currentMediaData = mediaMap(1),
      removeState = UnionArchiveRemoveState.Pending,
      currentResults = List(scanResult(1), scanResult(2)),
      mediaData = multiMediaMap(3, 4).toList, scanClient = Some(actor()),
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
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      currentMediaData = media, currentResults = List(result), ackPending = Some(ack),
      startAnnounced = true)

    val (next, msg) =
      updateState(MediaScanStateUpdateServiceImpl.handleRemovedFromUnionArchive(ArchiveName),
        state)
    next.currentResults should have size 0
    next.currentMediaData should have size 0
    next.ackPending shouldBe empty
    msg should be(ScanStateTransitionMessages(Some(AddMedia(media, ArchiveName, None)),
      Some(result), Some(ack)))
  }

  it should "handle the arrival of new results" in {
    val res1 = combinedResult(1)
    val res2 = combinedResult(2)
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      startAnnounced = true)
    val newResults = List(res1, res2)
    val expMediaData = multiMediaMap(1, 2)
    val sender = actor()

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.handleResultsReceived(sender = sender,
      results = ScanSinkActor.CombinedResults(newResults, state.seqNo),
      archiveName = ArchiveName)(uriForPath), state)
    next.ackPending should be(Some(sender))
    next.currentResults should contain only res2.result
    next.currentMediaData should have size 0
    msg.unionArchiveMessage should be(Some(AddMedia(expMediaData, ArchiveName, None)))
    msg.metaManagerMessage should be(Some(res1.result))
    msg.ack shouldBe empty
  }

  it should "handle an ACK from the meta data manager" in {
    val result = scanResult(1)
    val media = mediaMap(1)
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      currentMediaData = media, currentResults = List(result), ackPending = Some(ack),
      startAnnounced = true, ackMetaManager = false)

    val (next, msg) =
      updateState(MediaScanStateUpdateServiceImpl.handleAckFromMetaManager(ArchiveName), state)
    next.currentResults should have size 0
    next.currentMediaData should have size 0
    next.ackMetaManager shouldBe false
    next.ackPending shouldBe empty
    msg should be(ScanStateTransitionMessages(Some(AddMedia(media, ArchiveName, None)),
      Some(result), Some(ack)))
  }

  it should "handle a completed scan operation" in {
    val mediaData = multiMediaMap(2, 4, 8, 16).toList
    val currentMedia = mediaMap(16)
    val ack = actor()
    val client = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(client),
      mediaData = mediaData, ackPending = Some(ack), currentMediaData = currentMedia, seqNo = 4)

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.handleScanComplete(state.seqNo,
      ArchiveName), state)
    next.availableMediaSent shouldBe true
    next.mediaData should have size 0
    next.ackPending shouldBe empty
    next.scanClient shouldBe empty
    msg.metaManagerMessage should be(Some(AvailableMedia(mediaData)))
    msg.ack should be(Some(ack))
    msg.unionArchiveMessage should be(Some(AddMedia(currentMedia, ArchiveName, None)))
  }

  it should "handle a canceled scan operation" in {
    val ack = actor()
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(actor()),
      mediaData = multiMediaMap(1, 2).toList, ackPending = Some(ack),
      currentMediaData = mediaMap(1), currentResults = List(scanResult(2)))

    val (next, msg) = updateState(MediaScanStateUpdateServiceImpl.handleScanCanceled(), state)
    next.mediaData should have size 0
    next.currentMediaData should have size 0
    next.currentResults should have size 0
    next.ackPending shouldBe empty
    msg should be(ScanStateTransitionMessages(ack = Some(ack)))
  }
}
