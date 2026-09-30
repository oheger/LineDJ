/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.pleditor.plexport

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object CopyProgressStateSpec:
  /**
    * Constant for the size of the export operation. This is a number that
    * allows an easy computation of progress values.
    */
  private val ExportTotalSize = 1000

  /** Constant for the number of operations in the test export operation. */
  private val ExportTotalOps = 100

  /**
    * Returns a file name for a copy operation based on its index.
    *
    * @param index the index of the operation
    * @return the file name this operation operates on
    */
  private def fileName(index: Int): String = "copy-file-" + index

  /**
    * Returns a tuple with an operation index and the corresponding file name.
    * This is useful to assert on the active operations.
    *
    * @param index the index of the operation
    * @return the tuple for the active operation
    */
  private def activeOp(index: Int): (Int, String) = (index, fileName(index))

  /**
    * Creates a copy notification with the given parameters and the default
    * values used by this test class.
    *
    * @param index          the index of the operation
    * @param progressedSize the bytes copied by this operation so far
    * @param size           the size of this operation
    * @return the notification
    */
  private def notification(index: Int, progressedSize: Long, size: Long): PlaylistExporter.ProgressNotification =
    PlaylistExporter.ProgressNotification.Copy(
      totalOperations = ExportTotalOps,
      currentOperation = index,
      totalSize = ExportTotalSize,
      progressedSize = progressedSize,
      currentSize = size,
      fileName = fileName(index)
    )
end CopyProgressStateSpec

/**
  * Test class for [[CopyProgressState]].
  */
class CopyProgressStateSpec extends AnyFlatSpec, Matchers:

  import CopyProgressStateSpec.*

  "CopyProgressState.Initial" should "return a progress of 0" in :
    CopyProgressState.Initial.progress should be(0)

  it should "return an empty list of active operations" in :
    CopyProgressState.Initial.activeOperations shouldBe empty

  it should "return correct initial operation numbers" in :
    CopyProgressState.Initial.totalOperations should be(0)
    CopyProgressState.Initial.lastOperation should be(0)

  "A CopyProgressState" should "return itself when updated with a non-copy progress" in :
    val state = CopyProgressState.Initial.update(notification(1, 10, 100))

    val nextState = state.update(PlaylistExporter.ProgressNotification.Delete(200, 23, "test-delete"))

    nextState should be theSameInstanceAs state

  it should "update itself for a new operation" in :
    val state = CopyProgressState.Initial.update(notification(1, 10, 20))

    state.progress should be(1)
    state.activeOperations should contain only activeOp(1)
    state.totalOperations should be(ExportTotalOps)
    state.lastOperation should be(1)

  it should "handle multiple parallel operations" in :
    val state1 = CopyProgressState.Initial.update(notification(1, 10, 20))
    val state2 = state1.update(notification(2, 5, 50))
    val state3 = state2.update(notification(3, 15, 20))

    state3.progress should be(3)
    state3.activeOperations should contain theSameElementsInOrderAs List(activeOp(3), activeOp(2), activeOp(1))
    state3.lastOperation should be(3)

  it should "handle updates for active operations" in :
    val state1 = CopyProgressState.Initial.update(notification(1, 10, 200))
    val state2 = state1.update(notification(2, 5, 50))
    val state3 = state2.update(notification(1, 95, 200))

    state3.progress should be(10)
    state3.activeOperations should contain theSameElementsInOrderAs List(activeOp(2), activeOp(1))
    state3.lastOperation should be(2)

  it should "handle the notification for a completed operation" in :
    val state1 = CopyProgressState.Initial.update(notification(1, 10, 200))
    val state2 = state1.update(notification(2, 20, 50))
    val state3 = state2.update(notification(1, 200, 200))

    state3.progress should be(22)
    state3.activeOperations should contain theSameElementsInOrderAs List(activeOp(2))
    state3.lastOperation should be(2)

  it should "handle an operation whose size was not known initially" in :
    val state1 = CopyProgressState.Initial.update(notification(1, 10, 200))
    val state2 = state1.update(notification(2, 50, 50))

    state2 should be(state1.copy(lastOperation = 2))

  it should "set the last operation correctly for an operation whose size was not known initially" in :
    val state1 = CopyProgressState.Initial.update(notification(1, 10, 200))
    val state2 = state1.update(notification(3, 20, 200))
    val state3 = state2.update(notification(2, 50, 50))

    state3.progress should be(3)
    state3.activeOperations should contain theSameElementsInOrderAs List(activeOp(3), activeOp(1))
    state3.lastOperation should be(3)
