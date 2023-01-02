/*
 * Copyright 2015-2023 The Developers Team.
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
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import scalaz.State

object ScanSinkUpdateServiceImplSpec {
  /** A test medium ID. */
  private val Medium1 = MediumID("medium1", Some("test.settings"))

  /** Another test medium ID. */
  private val Medium2 = MediumID("otherMedium", None)

  /** Test root path. */
  private val RootPath = Paths get "TestRoot"

  /**
    * Produces an enhanced scan result object with dummy files for the
    * specified test media.
    *
    * @param media a sequence with test medium IDs
    * @return the scan result object
    */
  private def createScanResult(media: MediumID*): EnhancedMediaScanResult = {
    val fileMap = media.foldLeft(Map.empty[MediumID, List[FileData]]) { (m, id) =>
      m + (id -> createFiles(id))
    }
    EnhancedMediaScanResult(MediaScanResult(RootPath, fileMap), Map.empty)
  }

  /**
    * Produces a list with mock file data objects for the given test medium.
    * These are necessary for scan result objects, but the actual files do not
    * matter.
    *
    * @param mid the ID of the test medium
    * @return a list with mock file objects
    */
  private def createFiles(mid: MediumID): List[FileData] =
    List(createFileData(mid, 1), createFileData(mid, 2))

  /**
    * Creates a test file data object for a specific test medium.
    *
    * @param mid the ID of the test medium
    * @param idx the index to generate unique objects
    * @return the resulting file data object
    */
  private def createFileData(mid: MediumID, idx: Int): FileData =
    FileData(Paths get s"${mid.mediumURI}/Song$idx.mp3", 100 * idx)

  /**
    * Creates a test medium info object for the specified medium.
    *
    * @param mid the ID of the medium
    * @return the new medium info
    */
  private def createMediumInfo(mid: MediumID): MediumInfo =
    MediumInfo(name = "Info for " + mid.mediumURI, mediumID = mid,
      description = "", orderMode = "", orderParams = "", checksum = "")

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: State[ScanSinkState, A],
                             oldState: ScanSinkState = ScanSinkUpdateServiceImpl.InitialState):
  (ScanSinkState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: State[ScanSinkState, Unit],
                          oldState: ScanSinkState = ScanSinkUpdateServiceImpl.InitialState):
  ScanSinkState = {
    val (next, _) = updateState(s, oldState)
    next
  }
}

/**
  * Test class of ''ScanSinkUpdateServiceImpl''.
  */
class ScanSinkUpdateServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ScanSinkUpdateServiceImplSpec._

  def this() = this(ActorSystem("ScanSinkUpdateServiceImplSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Returns a test actor reference.
    *
    * @return the actor reference
    */
  private def actorRef(): ActorRef =
    TestProbe().ref

  "A ScanSinkUpdateServiceImpl" should "define a correct initial state" in {
    ScanSinkUpdateServiceImpl.InitialState.scanResults should have size 0
    ScanSinkUpdateServiceImpl.InitialState.mediaInfo should have size 0
    ScanSinkUpdateServiceImpl.InitialState.resultAck shouldBe true
    ScanSinkUpdateServiceImpl.InitialState.ackMediumFiles shouldBe empty
    ScanSinkUpdateServiceImpl.InitialState.ackMediumInfo shouldBe empty
    ScanSinkUpdateServiceImpl.InitialState.resultsDone shouldBe false
    ScanSinkUpdateServiceImpl.InitialState.infoDone shouldBe false
  }

  it should "add a new scan result if possible" in {
    val sender = actorRef()
    val result = createScanResult(Medium1)

    val next = modifyState(ScanSinkUpdateServiceImpl.addScanResult(result, sender))
    next.scanResults should contain only result
    next.ackMediumFiles should be(Some(sender))
  }

  it should "reject a new scan result if an ACK is pending" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(ackMediumFiles = Some(actorRef()))

    val next = modifyState(ScanSinkUpdateServiceImpl.addScanResult(createScanResult(Medium2),
      actorRef()), state)
    next should be(state)
  }

  it should "add a new medium info if possible" in {
    val info = createMediumInfo(Medium1)
    val sender = actorRef()

    val next = modifyState(ScanSinkUpdateServiceImpl.addMediumInfo(info, sender))
    next.mediaInfo should contain only (Medium1 -> info)
    next.ackMediumInfo should be(Some(sender))
  }

  it should "reject a new medium info if an ACK is pending" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(ackMediumInfo = Some(actorRef()))

    val next = modifyState(ScanSinkUpdateServiceImpl.addMediumInfo(createMediumInfo(Medium2),
      actorRef()), state)
    next should be(state)
  }

  it should "return actors to ACK if no ACK is pending" in {
    val (state, actors) = updateState(ScanSinkUpdateServiceImpl.actorsToAck(1))

    state should be(ScanSinkUpdateServiceImpl.InitialState)
    actors should have size 0
  }

  it should "return the pending actors to ACK" in {
    val act1 = actorRef()
    val act2 = actorRef()
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(ackMediumFiles = Some(act1),
      ackMediumInfo = Some(act2), scanResults = List(createScanResult(Medium1)),
      mediaInfo = Map(Medium2 -> createMediumInfo(Medium2)))

    val (next, actors) = updateState(ScanSinkUpdateServiceImpl.actorsToAck(2), state)
    next.ackMediumInfo shouldBe empty
    next.ackMediumFiles shouldBe empty
    actors should contain only(act1, act2)
  }

  it should "handle a single pending actor to ACK" in {
    val actor = actorRef()
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(ackMediumFiles = Some(actor))

    val (next, actors) = updateState(ScanSinkUpdateServiceImpl.actorsToAck(5), state)
    next.ackMediumFiles shouldBe empty
    actors should contain only actor
  }

  it should "not send an ACK if the internal buffer is filled" in {
    val scanResults = List(createScanResult(Medium1), createScanResult(Medium2))
    val info = Map(Medium1 -> createMediumInfo(Medium1),
      Medium2 -> createMediumInfo(Medium2))
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = scanResults,
      mediaInfo = info, ackMediumFiles = Some(actorRef()), ackMediumInfo = Some(actorRef()))

    val (next, actors) = updateState(ScanSinkUpdateServiceImpl.actorsToAck(2), state)
    next should be(state)
    actors should have size 0
  }

  it should "handle a single actor to ACK taking buffers into account" in {
    val act1 = actorRef()
    val act2 = actorRef()
    val scanResults = List(createScanResult(Medium1), createScanResult(Medium2))
    val info = Map(Medium1 -> createMediumInfo(Medium1))
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = scanResults,
      mediaInfo = info, ackMediumFiles = Some(act1), ackMediumInfo = Some(act2))

    val (next, actors) = updateState(ScanSinkUpdateServiceImpl.actorsToAck(2), state)
    next.ackMediumInfo shouldBe empty
    next.ackMediumFiles should be(Some(act1))
    actors should contain only act2
  }

  it should "not return combined results if an ACK is pending" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(
      scanResults = List(createScanResult(Medium1)),
      mediaInfo = Map(Medium1 -> createMediumInfo(Medium1)),
      resultAck = false)

    val (next, results) = updateState(ScanSinkUpdateServiceImpl.combinedResults(), state)
    next should be(state)
    results should have size 0
  }

  it should "not return combined results if no matching result data is available" in {
    val scanResults = List(createScanResult(Medium2))
    val info = Map(Medium1 -> createMediumInfo(Medium1))
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = scanResults,
      mediaInfo = info)

    val (next, results) = updateState(ScanSinkUpdateServiceImpl.combinedResults(), state)
    next should be(state)
    results should have size 0
  }

  it should "return correct combined results" in {
    val mid3 = MediumID("test3", Some("test3"))
    val mid4 = MediumID("test4", Some("test4"))
    val sr12 = createScanResult(Medium1, Medium2)
    val sr3 = createScanResult(mid3)
    val scanResults = List(sr12, sr3)
    val info = Map(Medium1 -> createMediumInfo(Medium1),
      mid4 -> createMediumInfo(mid4),
      Medium2 -> createMediumInfo(Medium2))
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = scanResults,
      mediaInfo = info)

    val (next, results) = updateState(ScanSinkUpdateServiceImpl.combinedResults(), state)
    next.scanResults should contain only sr3
    next.mediaInfo should contain only (mid4 -> createMediumInfo(mid4))
    next.resultAck shouldBe false
    results should contain only CombinedMediaScanResult(sr12,
      Map(Medium1 -> createMediumInfo(Medium1), Medium2 -> createMediumInfo(Medium2)))
  }

  it should "ignore a received ACK if none is expected" in {
    val state = modifyState(ScanSinkUpdateServiceImpl.resultAckReceived())

    state should be(ScanSinkUpdateServiceImpl.InitialState)
  }

  it should "update the state for a received ACK" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(resultAck = false)

    val nextState = modifyState(ScanSinkUpdateServiceImpl.resultAckReceived(), state)
    nextState.resultAck shouldBe true
  }

  it should "handle the arrival of a new scan result" in {
    val state = ScanSinkUpdateServiceImpl.InitialState
      .copy(mediaInfo = Map(Medium1 -> createMediumInfo(Medium1)))
    val scanResult = createScanResult(Medium1)
    val sender = actorRef()

    val (next, messages) = updateState(ScanSinkUpdateServiceImpl.handleNewScanResult(scanResult,
      sender, 5), state)
    messages.actorsToAck should contain only sender
    messages.results should have size 1
    messages.results.head.result should be(scanResult)
    messages.processingDone shouldBe false
    next.resultAck shouldBe false
    next.scanResults should have size 0
    next.mediaInfo should have size 0
    next.ackMediumFiles shouldBe empty
  }

  it should "handle the arrival of a new medium info" in {
    val scanResult = createScanResult(Medium1)
    val info = createMediumInfo(Medium1)
    val media = Map(Medium1 -> info)
    val sender = actorRef()
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = List(scanResult))

    val (next, messages) = updateState(ScanSinkUpdateServiceImpl.handleNewMediumInfo(info,
      sender, 5), state)
    messages.actorsToAck should contain only sender
    messages.results should have size 1
    messages.results.head.info should be(media)
    messages.processingDone shouldBe false
    next.resultAck shouldBe false
    next.scanResults should have size 0
    next.mediaInfo should have size 0
    next.ackMediumInfo shouldBe empty
  }

  it should "handle the arrival of a result ACK" in {
    val scanResult = createScanResult(Medium1)
    val info = createMediumInfo(Medium1)
    val media = Map(Medium1 -> info)
    val act1 = actorRef()
    val act2 = actorRef()
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = List(scanResult),
      mediaInfo = media, ackMediumFiles = Some(act1), ackMediumInfo = Some(act2),
      resultAck = false)

    val (next, messages) = updateState(ScanSinkUpdateServiceImpl.handleResultAck(1),
      state)
    messages.actorsToAck should contain only(act1, act2)
    messages.results should have size 1
    messages.processingDone shouldBe false
    next.resultAck shouldBe false
    next.scanResults should have size 0
    next.mediaInfo should have size 0
    next.ackMediumFiles shouldBe empty
    next.ackMediumInfo shouldBe empty
  }

  it should "update the scan results done flag" in {
    val next = modifyState(ScanSinkUpdateServiceImpl.scanResultsDone())

    next.resultsDone shouldBe true
  }

  it should "update the media info done flag" in {
    val next = modifyState(ScanSinkUpdateServiceImpl.mediaInfoDone())

    next.infoDone shouldBe true
  }

  it should "return the correct completion flag if everything is complete" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(infoDone = true,
      resultsDone = true)

    val (next, done) = updateState(ScanSinkUpdateServiceImpl.processingDone(), state)
    done shouldBe true
    next should be(state)
  }

  it should "return the correct completion flag if media info are not done" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(resultsDone = true)

    val (next, done) = updateState(ScanSinkUpdateServiceImpl.processingDone(), state)
    done shouldBe false
    next should be(state)
  }

  it should "return the correct completion flag if scan results are not done" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(infoDone = true)

    val (next, done) = updateState(ScanSinkUpdateServiceImpl.processingDone(), state)
    done shouldBe false
    next should be(state)
  }

  it should "return the correct completion flag if there are pending results" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(infoDone = true, resultsDone = true,
      scanResults = List(createScanResult(Medium1)))

    val (next, done) = updateState(ScanSinkUpdateServiceImpl.processingDone(), state)
    done shouldBe false
    next should be(state)
  }

  it should "return the correct completion flag if downstream ACK is pending" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(infoDone = true, resultsDone = true,
      resultAck = false)

    val (next, done) = updateState(ScanSinkUpdateServiceImpl.processingDone(), state)
    done shouldBe false
    next should be(state)
  }

  it should "return remaining scan results when processing is done" in {
    val scanResult = createScanResult(Medium1, Medium2)
    val m1Info = createMediumInfo(Medium1)
    val info = Map(Medium1 -> m1Info)
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = List(scanResult),
      mediaInfo = info, infoDone = true, resultsDone = true)

    val (next, messages) = updateState(ScanSinkUpdateServiceImpl.combinedResults(), state)
    next.scanResults should have size 0
    next.mediaInfo should have size 0
    messages should have size 1
    val result = messages.head
    result.result should be(scanResult)
    result.info(Medium1) should be(m1Info)
    result.info(Medium2) should be(MediumInfoParserActor.DummyMediumSettingsData)
  }

  it should "handle the arrival of a scan results done message" in {
    val actor = actorRef()
    val scanResult = createScanResult(Medium1)
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = List(scanResult),
      infoDone = true, ackMediumFiles = Some(actor))

    val (next, messages) =
      updateState(ScanSinkUpdateServiceImpl.handleScanResultsDone(2), state)
    messages.results should have size 1
    messages.results.head.result should be(scanResult)
    messages.actorsToAck should contain only actor
    messages.processingDone shouldBe false
    next.resultsDone shouldBe true
    next.resultAck shouldBe false
    next.scanResults should have size 0
  }

  it should "handle the arrival of a media info done message" in {
    val actor = actorRef()
    val scanResult = createScanResult(Medium1)
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = List(scanResult),
      resultsDone = true, ackMediumInfo = Some(actor))

    val (next, messages) =
      updateState(ScanSinkUpdateServiceImpl.handleMediaInfoDone(1), state)
    messages.results should have size 1
    messages.results.head.result should be(scanResult)
    messages.actorsToAck should contain only actor
    messages.processingDone shouldBe false
    next.infoDone shouldBe true
    next.resultAck shouldBe false
    next.scanResults should have size 0
  }

  it should "set the correct processing done flag when receiving the final result ACK" in {
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(resultAck = false,
      resultsDone = true, infoDone = true)

    val (next, messages) =
      updateState(ScanSinkUpdateServiceImpl.handleResultAck(1), state)
    messages.results should have size 0
    messages.actorsToAck should have size 0
    messages.processingDone shouldBe true
    next.resultAck shouldBe true
  }
}
