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

package de.oliver_heger.linedj.archivehttp.impl

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, MediaContribution, MetaDataProcessingSuccess}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object ContentPropagationUpdateServiceSpec {
  /** The name of the archive. */
  private val ArchiveName = "TestArchive"

  /** Test sequence number. */
  private val SeqNo = 20180617

  /**
    * Creates an ID for a test medium with the given index.
    *
    * @param idx the index
    * @return the ID for the test medium with this index
    */
  private def mediumID(idx: Int): MediumID =
    MediumID("testMedium" + idx, Some(s"medium$idx.settings"))

  /**
    * Generates a ''MediumInfo'' object for a test medium.
    *
    * @param idx the index of the test medium
    * @return the info object for this test medium
    */
  private def mediumInfo(idx: Int): MediumInfo =
    MediumInfo(name = "TestMedium" + idx, description = "", orderMode = "", checksum = "",
      orderParams = "", mediumID = mediumID(idx))

  /**
    * Generates the path for the media files contained on the given test
    * medium.
    *
    * @param idx the index of the test medium
    * @return the path for files on this test medium
    */
  private def mediumPath(idx: Int): String = s"mediumPath$idx/"

  /**
    * Generates the path of a file contained on the given test medium.
    *
    * @param medIdx  the index of the test medium
    * @param fileIdx the index of the test file
    * @return the resulting path to this medium file
    */
  private def mediumFilePath(medIdx: Int, fileIdx: Int): String =
    s"${mediumPath(medIdx)}file$fileIdx.mp3"

  /**
    * Calculates the size of a file on a test medium.
    *
    * @param medIdx  the index of the test medium
    * @param fileIdx the index of the test file
    * @return the size of this test file
    */
  private def fileSize(medIdx: Int, fileIdx: Int): Int =
    medIdx * 100 + fileIdx * 10

  /**
    * Generates the URIS of the media files for the test medium with the given
    * index.
    *
    * @param idx the index
    * @return the map with the content of this medium
    */
  private def mediumContent(idx: Int): Map[MediumID, Iterable[MediaFileUri]] = {
    val files = (1 to idx).map(i => MediaFileUri(mediumFilePath(idx, i)))
    Map(mediumID(idx) -> files)
  }

  /**
    * Generates a meta data processing result object for the specified test
    * file.
    *
    * @param medIdx  the medium index
    * @param fileIdx the file index
    * @return the test meta data processing result
    */
  private def metaDataProcessingResult(medIdx: Int, fileIdx: Int): MetaDataProcessingSuccess = {
    val filePath = mediumFilePath(medIdx, fileIdx)
    MetaDataProcessingSuccess(mediumID = mediumID(medIdx), uri = MediaFileUri(filePath),
      metaData = MediaMetaData(title = Some(filePath), size = fileSize(medIdx, fileIdx)))
  }

  /**
    * Generates the sequence of meta data processing result objects for the
    * specified test medium.
    *
    * @param idx the medium index
    * @return the meta data results for this medium
    */
  private def metaDataProcessingResults(idx: Int): Iterable[MetaDataProcessingSuccess] =
    (1 to idx) map (metaDataProcessingResult(idx, _))

  /**
    * Generates a processing result for a test medium.
    *
    * @param idx the index of the test medium
    * @return the processing result for this medium
    */
  private def mediumResult(idx: Int): MediumProcessingResult = {
    val metaData = metaDataProcessingResults(idx)
    MediumProcessingResult(mediumInfo(idx), metaData, SeqNo)
  }

  /**
    * Generates a media contribution message for the given test medium.
    *
    * @param idx the index of the test medium
    * @return the ''MediaContribution''
    */
  private def mediaContribution(idx: Int): MediaContribution =
    MediaContribution(mediumContent(idx))

  /**
    * Generates an ''AddMedia'' message for the specified test medium.
    *
    * @param idx    the index of the test medium
    * @param actors actors involved in propagation
    * @return the ''AddMedia'' message
    */
  private def addMedia(idx: Int, actors: PropagationActors): AddMedia = {
    val info = mediumInfo(idx)
    AddMedia(Map(info.mediumID -> info), ArchiveName, Some(actors.client))
  }

  /**
    * Generates a ''MessageData'' object with the messages to be sent for the
    * media manager actor for the given test medium.
    *
    * @param idx    the index of the test medium
    * @param actors actors involved in propagation
    * @return the ''MessageData'' object
    */
  private def mediaManagerMessages(idx: Int, actors: PropagationActors): MessageData =
    MessageData(actors.mediaManager, List(addMedia(idx, actors)))

  /**
    * Generates a ''MessageData'' object with the messages to be sent for the
    * meta data manager actor for the given test medium.
    *
    * @param idx    the index of the test medium
    * @param actors actors involved in propagation
    * @return the ''MessageData'' object
    */
  private def metaManagerMessages(idx: Int, actors: PropagationActors): MessageData = {
    val results = metaDataProcessingResults(idx).toList
    val messages = mediaContribution(idx) :: results
    MessageData(actors.metaManager, messages)
  }

  /**
    * Generates a ''MessageData'' object with the ACK message for propagation
    * of the results of a medium.
    *
    * @param actors actors involved in propagation
    * @return the ''MessageData'' object
    */
  private def ackMessage(actors: PropagationActors): MessageData =
    MessageData(actors.client, Seq(MediumPropagated(SeqNo)))

  /**
    * Generates a ''MessageData'' object with a request to remove the content
    * of the test archive from the union archive.
    *
    * @param actors actors involved in propagation
    * @return the ''MessageData'' object
    */
  private def removeMessage(actors: PropagationActors): MessageData =
    MessageData(actors.mediaManager, Seq(ArchiveComponentRemoved(ArchiveName)))

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: ContentPropagationUpdateServiceImpl.StateUpdate[A],
                             oldState: ContentPropagationState =
                             ContentPropagationUpdateServiceImpl.InitialState):
  (ContentPropagationState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: ContentPropagationUpdateServiceImpl.StateUpdate[Unit],
                          oldState: ContentPropagationState =
                          ContentPropagationUpdateServiceImpl.InitialState):
  ContentPropagationState = {
    val (next, _) = updateState(s, oldState)
    next
  }
}

/**
  * Test class for ''ContentPropagationUpdateServiceImpl''.
  */
class ContentPropagationUpdateServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("ContentPropagationUpdateServiceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import ContentPropagationUpdateServiceSpec._

  /**
    * Creates an object with test actors involved in propagation.
    *
    * @return the ''PropagationActors'' object
    */
  private def createActors(): PropagationActors =
    PropagationActors(TestProbe().ref, TestProbe().ref, TestProbe().ref)

  "ContentPropagationUpdateServiceImpl" should "have a valid initial state" in {
    val state = ContentPropagationUpdateServiceImpl.InitialState

    state.messages shouldBe empty
    state.pendingMessages shouldBe empty
    state.removeAck shouldBe true
  }

  it should "process results if no removal ACK is pending" in {
    val Idx = 1
    val actors = createActors()
    val expMessages = List(mediaManagerMessages(Idx, actors),
      metaManagerMessages(Idx, actors), ackMessage(actors))

    val next = modifyState(ContentPropagationUpdateServiceImpl
      .mediumProcessed(mediumResult(Idx), actors, ArchiveName, remove = false))
    next.pendingMessages shouldBe empty
    next.removeAck shouldBe true
    next.messages should contain theSameElementsInOrderAs expMessages
  }

  it should "append data for another medium to messages to be sent" in {
    val actors = createActors()
    val orgMessages = List(mediaManagerMessages(1, actors),
      metaManagerMessages(1, actors), ackMessage(actors))
    val newMessages = List(mediaManagerMessages(2, actors),
      metaManagerMessages(2, actors), ackMessage(actors))
    val expMessages = newMessages ::: orgMessages
    val state = ContentPropagationUpdateServiceImpl.InitialState.copy(messages = orgMessages)

    val next = modifyState(ContentPropagationUpdateServiceImpl
      .mediumProcessed(mediumResult(2), actors, ArchiveName, remove = false), state)
    next.messages should contain theSameElementsInOrderAs expMessages
  }

  it should "append data to pending messages if remove is not confirmed" in {
    val actors = createActors()
    val orgMessages = List(metaManagerMessages(2, actors))
    val newMessages = List(mediaManagerMessages(2, actors),
      metaManagerMessages(2, actors), ackMessage(actors))
    val expMessages = newMessages ::: orgMessages
    val state = ContentPropagationUpdateServiceImpl.InitialState.copy(removeAck = false,
      pendingMessages = orgMessages)

    val next = modifyState(ContentPropagationUpdateServiceImpl
      .mediumProcessed(mediumResult(2), actors, ArchiveName, remove = false), state)
    next.pendingMessages should contain theSameElementsInOrderAs expMessages
    next.messages shouldBe empty
  }

  it should "evaluate the remove parameter" in {
    val actors = createActors()
    val orgMessages = List(mediaManagerMessages(1, actors),
      metaManagerMessages(1, actors), ackMessage(actors))
    val orgPending = List(mediaManagerMessages(2, actors),
      metaManagerMessages(2, actors), ackMessage(actors))
    val newPending = List(mediaManagerMessages(3, actors),
      metaManagerMessages(3, actors), ackMessage(actors))
    val state = ContentPropagationUpdateServiceImpl.InitialState.copy(messages = orgMessages,
      pendingMessages = orgPending)

    val next = modifyState(ContentPropagationUpdateServiceImpl
      .mediumProcessed(mediumResult(3), actors, ArchiveName, remove = true), state)
    next.removeAck shouldBe false
    next.messages should contain only removeMessage(actors)
    next.pendingMessages should contain theSameElementsInOrderAs newPending
  }

  it should "ignore a remove ACK if one already arrived" in {
    val actors = createActors()
    val state = ContentPropagationUpdateServiceImpl.InitialState
      .copy(messages = List(metaManagerMessages(1, actors)))

    val next = modifyState(ContentPropagationUpdateServiceImpl.removalConfirmed(), state)
    next should be theSameInstanceAs state
  }

  it should "update the state for a remove ACK" in {
    val actors = createActors()
    val pending = List(metaManagerMessages(1, actors), mediaManagerMessages(1, actors),
      ackMessage(actors))
    val state = ContentPropagationState(messages = List(ackMessage(actors)),
      pendingMessages = pending, removeAck = false)

    val next = modifyState(ContentPropagationUpdateServiceImpl.removalConfirmed(), state)
    next.removeAck shouldBe true
    next.messages should be(pending)
    next.pendingMessages shouldBe empty
  }

  it should "return the messages to be sent" in {
    val actors = createActors()
    val messages = List(metaManagerMessages(1, actors), mediaManagerMessages(1, actors),
      ackMessage(actors))
    val pending = List(metaManagerMessages(2, actors), mediaManagerMessages(2, actors),
      ackMessage(actors))
    val state = ContentPropagationState(messages = messages, pendingMessages = pending,
      removeAck = false)

    val (next, sendMsg) = updateState(ContentPropagationUpdateServiceImpl.messagesToSend(), state)
    sendMsg should be(messages)
    next should be(state.copy(messages = Nil))
  }

  it should "handle a notification about a processed medium" in {
    val Idx = 1
    val actors = createActors()
    val expMessages = List(mediaManagerMessages(Idx, actors),
      metaManagerMessages(Idx, actors), ackMessage(actors))

    val (next, sendMsg) = updateState(ContentPropagationUpdateServiceImpl
      .handleMediumProcessed(mediumResult(Idx), actors, ArchiveName, remove = false))
    next.pendingMessages shouldBe empty
    next.removeAck shouldBe true
    next.messages shouldBe empty
    sendMsg.toSeq should contain theSameElementsInOrderAs expMessages
  }

  it should "handle a removal confirmation" in {
    val actors = createActors()
    val pending = List(metaManagerMessages(1, actors), mediaManagerMessages(1, actors),
      ackMessage(actors))
    val state = ContentPropagationState(messages = Nil, pendingMessages = pending,
      removeAck = false)

    val (next, sendMsg) = updateState(ContentPropagationUpdateServiceImpl.handleRemovalConfirmed(),
      state)
    next.removeAck shouldBe true
    next.messages shouldBe empty
    next.pendingMessages shouldBe empty
    sendMsg should be(pending)
  }
}
