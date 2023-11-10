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

package de.oliver_heger.linedj.archive.group

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''GroupScanStateService''.
  */
class GroupScanStateServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("GroupScanStateServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: GroupScanStateServiceImpl.StateUpdate[A],
                             oldState: GroupScanState = GroupScanStateServiceImpl.InitialState):
  (GroupScanState, A) = s(oldState)

  "GroupScanStateServiceImpl" should "define a correct initial state" in:
    val state = GroupScanStateServiceImpl.InitialState

    state.scanInProgress shouldBe false
    state.pendingScanRequests should have size 0
    state.currentScanRequest.isEmpty shouldBe true

  it should "handle a scan request if no scan operation is in progress" in:
    val actor = TestProbe().ref

    val (nextState, optRequest) = updateState(GroupScanStateServiceImpl.handleScanRequest(actor))
    nextState.scanInProgress shouldBe true
    nextState.pendingScanRequests should have size 0
    nextState.currentScanRequest.isEmpty shouldBe true
    optRequest should be(Some(actor))

  it should "handle a scan request if a scan is already in progress" in:
    val actor = TestProbe().ref
    val orgState = GroupScanStateServiceImpl.InitialState.copy(scanInProgress = true)

    val (nextState, optRequest) = updateState(GroupScanStateServiceImpl.handleScanRequest(actor), orgState)
    nextState.currentScanRequest.isEmpty shouldBe true
    nextState.scanInProgress shouldBe true
    nextState.pendingScanRequests should contain only actor
    optRequest should be(None)

  it should "handle a scan completed notification if there are no pending requests" in:
    val orgState = GroupScanStateServiceImpl.InitialState.copy(scanInProgress = true)

    val (nextState, optRequest) = updateState(GroupScanStateServiceImpl.handleScanCompleted(), orgState)
    nextState should be(GroupScanStateServiceImpl.InitialState)
    optRequest.isEmpty shouldBe true

  it should "handle a scan completed notification if there are pending requests" in:
    val pending1 = TestProbe().ref
    val pending2 = TestProbe().ref
    val orgState = GroupScanState(pendingScanRequests = Set(pending1, pending2), scanInProgress = true,
      currentScanRequest = None)

    val (nextState, optRequest) = updateState(GroupScanStateServiceImpl.handleScanCompleted(), orgState)
    optRequest.isDefined shouldBe true
    val requestTarget = optRequest.get
    if requestTarget == pending1 then
      nextState.pendingScanRequests should contain only pending2
    else
      requestTarget should be(pending2)
      nextState.pendingScanRequests should contain only pending1
    nextState.currentScanRequest.isEmpty shouldBe true
    nextState.scanInProgress shouldBe true
