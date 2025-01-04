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

package de.oliver_heger.linedj.archivehttp.impl

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ContentProcessingUpdateServiceSpec:
  /** Test value for the max in progress counter. */
  private val MaxInProgress = 8

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: ContentProcessingUpdateServiceImpl.StateUpdate[A],
                             oldState: ContentProcessingState =
                             ContentProcessingUpdateServiceImpl.InitialState):
  (ContentProcessingState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: ContentProcessingUpdateServiceImpl.StateUpdate[Unit],
                          oldState: ContentProcessingState =
                          ContentProcessingUpdateServiceImpl.InitialState):
  ContentProcessingState =
    val (next, _) = updateState(s, oldState)
    next

/**
  * Test class for ''ContentProcessingUpdateService''.
  */
class ContentProcessingUpdateServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ContentProcessingUpdateServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  import ContentProcessingUpdateServiceSpec._

  /**
    * Creates a mock result object.
    *
    * @param seqNo the sequence number of the result
    * @return the mock result
    */
  private def createResult(seqNo: Int = 0): MediumProcessingResult =
    val result = mock[MediumProcessingResult]
    when(result.seqNo).thenReturn(seqNo)
    result

  "ContentProcessingUpdateService" should "have a correct initial state" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState

    state.mediaInProgress should be(0)
    state.ack shouldBe empty
    state.pendingResult shouldBe empty
    state.pendingClient shouldBe empty
    state.propagateMsg shouldBe empty
    state.contentInArchive shouldBe false
    state.removeTriggered shouldBe true
    state.seqNo should be(0)
    state.scanInProgress shouldBe false

  it should "update the state for a newly started processing operation" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState.copy(mediaInProgress = 28,
      ack = Some(testActor), propagateMsg = Some(mock[PropagateMediumResult]),
      pendingResult = Some(createResult()), pendingClient = Some(testActor))

    val (next, f) = updateState(ContentProcessingUpdateServiceImpl.processingStarts(), state)
    f shouldBe true
    next.propagateMsg shouldBe empty
    next.ack shouldBe empty
    next.pendingResult shouldBe empty
    next.pendingClient shouldBe empty
    next.seqNo should not be state.seqNo
    next.mediaInProgress should be(0)
    next.removeTriggered shouldBe false
    next.scanInProgress shouldBe true

  it should "ignore a processing starts call if a scan is already in progress" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState.copy(scanInProgress = true,
      ack = Some(testActor))

    val (next, f) = updateState(ContentProcessingUpdateServiceImpl.processingStarts(), state)
    next should be theSameInstanceAs state
    f shouldBe false

  it should "reject a new result if the last one has not been propagated yet" in:
    val propagate = PropagateMediumResult(createResult(), removeContent = true)
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(propagateMsg = Some(propagate))

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultAvailable(createResult(),
      testActor, MaxInProgress), state)
    next should be theSameInstanceAs state

  it should "reject a new result if another one is pending" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(pendingResult = Some(createResult()))

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultAvailable(createResult(),
      testActor, MaxInProgress), state)
    next should be theSameInstanceAs state

  it should "update the state for a new result" in:
    val ProgressCount = MaxInProgress - 1
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(mediaInProgress = ProgressCount)
    val result = createResult()

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultAvailable(result, testActor,
      MaxInProgress), state)
    next.removeTriggered shouldBe true
    next.mediaInProgress should be(ProgressCount + 1)
    next.propagateMsg should be(Some(PropagateMediumResult(result, removeContent = false)))
    next.contentInArchive shouldBe true
    next.ack should be(Some(testActor))
    next.pendingClient shouldBe empty
    next.pendingResult shouldBe empty

  it should "ignore a result with a wrong sequence number" in:
    val result = createResult(ContentProcessingUpdateServiceImpl.InitialState.seqNo + 1)

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultAvailable(result, testActor,
      MaxInProgress))
    next should be theSameInstanceAs ContentProcessingUpdateServiceImpl.InitialState

  it should "take the maximum in progress count into account" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(mediaInProgress = MaxInProgress)
    val result = createResult()

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultAvailable(result, testActor,
      MaxInProgress), state)
    next.mediaInProgress should be(MaxInProgress)
    next.propagateMsg shouldBe empty
    next.ack shouldBe empty
    next.pendingResult should be(Some(result))
    next.pendingClient should be(Some(testActor))

  it should "set the remove flag for the first result if content in archive" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState.copy(contentInArchive = true,
      removeTriggered = false)
    val result = createResult()

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultAvailable(result, testActor,
      MaxInProgress), state)
    next.propagateMsg should be(Some(PropagateMediumResult(result, removeContent = true)))
    next.removeTriggered shouldBe true

  it should "set the remove flag only for the first result if content in archive" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState.copy(removeTriggered = false)
    val result = createResult()

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultAvailable(result, testActor,
      MaxInProgress), state)
    next.propagateMsg should be(Some(PropagateMediumResult(result, removeContent = false)))
    next.removeTriggered shouldBe true

  it should "decrement the progress counter when a result was propagated" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(mediaInProgress = MaxInProgress)

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultPropagated(state.seqNo,
      MaxInProgress), state)
    next should be(state.copy(mediaInProgress = MaxInProgress - 1))

  it should "not decrement the progress counter below 0" in:
    val next = modifyState(ContentProcessingUpdateServiceImpl
      .resultPropagated(ContentProcessingUpdateServiceImpl.InitialState.seqNo, MaxInProgress))

    next.mediaInProgress should be(0)

  it should "ignore result propagation messages with a wrong seq number" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState.copy(mediaInProgress = 1)

    val next = modifyState(ContentProcessingUpdateServiceImpl
      .resultPropagated(state.seqNo + 1, MaxInProgress), state)
    next should be theSameInstanceAs state

  it should "deal with a pending result when a result was propagated" in:
    val result = createResult()
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(mediaInProgress = MaxInProgress, pendingClient = Some(testActor),
        pendingResult = Some(result))

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultPropagated(state.seqNo,
      MaxInProgress), state)
    next.mediaInProgress should be(MaxInProgress)
    next.pendingClient shouldBe empty
    next.pendingResult shouldBe empty
    next.propagateMsg should be(Some(PropagateMediumResult(result, removeContent = false)))
    next.ack should be(Some(testActor))

  it should "only deal with a pending result if the progress count is low enough" in:
    val result = createResult()
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(mediaInProgress = MaxInProgress + 1, pendingClient = Some(testActor),
        pendingResult = Some(result))

    val next = modifyState(ContentProcessingUpdateServiceImpl.resultPropagated(state.seqNo,
      MaxInProgress), state)
    next.pendingClient should be(Some(testActor))
    next.pendingResult should be(Some(result))
    next.ack shouldBe empty
    next.propagateMsg shouldBe empty
    next.mediaInProgress should be(MaxInProgress)

  it should "update the state when processing is complete" in:
    val state = ContentProcessingUpdateServiceImpl.InitialState.copy(scanInProgress = true)

    val next = modifyState(ContentProcessingUpdateServiceImpl.processingDone(), state)
    next.scanInProgress shouldBe false

  it should "fetch transition data" in:
    val propMsg = PropagateMediumResult(createResult(), removeContent = false)
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(propagateMsg = Some(propMsg), ack = Some(testActor))

    val (next, data) = updateState(ContentProcessingUpdateServiceImpl.fetchTransitionData(), state)
    next.ack shouldBe empty
    next.propagateMsg shouldBe empty
    data.propagateMsg should be(Some(propMsg))
    data.actorToAck should be(Some(testActor))

  it should "handle a new result" in:
    val result = createResult()

    val (next, data) = updateState(ContentProcessingUpdateServiceImpl
      .handleResultAvailable(result, testActor, MaxInProgress))
    next.mediaInProgress should be(1)
    next.ack shouldBe empty
    next.propagateMsg shouldBe empty
    data.propagateMsg should be(Some(PropagateMediumResult(result, removeContent = false)))
    data.actorToAck should be(Some(testActor))

  it should "handle a result propagated confirmation" in:
    val result = createResult()
    val state = ContentProcessingUpdateServiceImpl.InitialState
      .copy(mediaInProgress = MaxInProgress, pendingClient = Some(testActor),
        pendingResult = Some(result))

    val (next, data) = updateState(ContentProcessingUpdateServiceImpl
      .handleResultPropagated(state.seqNo, MaxInProgress), state)
    next.mediaInProgress should be(MaxInProgress)
    next.pendingClient shouldBe empty
    next.pendingResult shouldBe empty
    next.propagateMsg shouldBe empty
    next.ack shouldBe empty
    data.propagateMsg should be(Some(PropagateMediumResult(result, removeContent = false)))
    data.actorToAck should be(Some(testActor))
