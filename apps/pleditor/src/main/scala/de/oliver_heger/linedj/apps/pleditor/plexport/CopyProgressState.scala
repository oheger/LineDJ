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

object CopyProgressState:
  /**
    * Constant for an initial [[CopyProgressState]] object to represent the
    * start of an export operation.
    */
  final val Initial: CopyProgressState = CopyProgressState(
    totalSize = 0,
    opsInProgress = Set.empty,
    progressedSize = 0,
    activeNotifications = Nil,
    totalOperations = 0,
    lastOperation = 0
  )
end CopyProgressState

/**
  * A helper class to process progress notifications for copy operations for an
  * ongoing export operation.
  *
  * This class is used by [[ExportController]] to keep track on the
  * notifications sent during a playlist export. The controller manages an
  * instance that it updates for each progress notification that was received.
  * The current state then provides information for updating the UI, to show
  * what is currently going on. For instance, it is possible to get the
  * progress over the copied bytes or the number of completed versus total
  * operations.
  *
  * @param totalSize           the total size to copy in bytes
  * @param opsInProgress       the indices of ops currently in progress
  * @param progressedSize      the number of bytes that has been copied
  * @param activeNotifications the latest notifications for active operations
  * @param totalOperations     the total number of copy operations
  * @param lastOperation       the operation with the highest index in progress
  */
private case class CopyProgressState(totalSize: Long,
                                     opsInProgress: Set[Int],
                                     progressedSize: Long,
                                     activeNotifications: List[PlaylistExporter.ProgressNotification],
                                     totalOperations: Int,
                                     lastOperation: Int):
  /**
    * Updates this object for the given progress notification. If this
    * notification refers to a copy operation, the properties in this object
    * are updated. This change can then be reflected in the UI.
    *
    * @param p the notification
    * @return the updated state
    */
  def update(p: PlaylistExporter.ProgressNotification): CopyProgressState =
    p match
      case c: PlaylistExporter.ProgressNotification.Copy =>
        val operationComplete = c.progressedSize == c.currentSize
        if opsInProgress.contains(c.currentOperation) then
          if operationComplete then
            val nextActiveNotifications = activeNotifications.filterNot:
              case ca: PlaylistExporter.ProgressNotification.Copy if ca.currentOperation == c.currentOperation => true
              case _ => false
            copy(
              opsInProgress = opsInProgress - c.currentOperation,
              progressedSize = progressedSize + c.currentSize,
              activeNotifications = nextActiveNotifications
            )
          else
            val nextActiveNotifications = activeNotifications.map:
              case ca: PlaylistExporter.ProgressNotification.Copy if ca.currentOperation == c.currentOperation => c
              case ca => ca
            copy(activeNotifications = nextActiveNotifications)
        else {
          if operationComplete then
            copy(lastOperation = math.max(lastOperation, c.currentOperation))
          else
            copy(
              totalSize = c.totalSize,
              totalOperations = c.totalOperations,
              activeNotifications = c :: activeNotifications,
              opsInProgress = opsInProgress + c.currentOperation,
              lastOperation = c.currentOperation
            )
        }
      case _ => this

  /**
    * Returns a value from 0 to 100 for the current progress of the whole
    * playlist export. This can be used for instance to show a progress
    * indicator control.
    *
    * @return the progress of the export in percent
    */
  def progress: Int =
    val activeProgressedSize = progressedSize + activeNotifications.foldLeft(0L): (size, op) =>
      op match
        case c: PlaylistExporter.ProgressNotification.Copy => size + c.progressedSize
        case _ => size
    math.round(100.0d * activeProgressedSize / totalSize).toInt

  /**
    * Returns information about the copy operations that are currently in
    * progress. The returned list contains tuples with the index of an
    * operation and the name of the file it operates on. The list is ordered,
    * the operation with the highest index comes first.
    *
    * @return information about operations in progress
    */
  def activeOperations: List[(Int, String)] =
    activeNotifications.foldRight(List.empty[(Int, String)]): (op, list) =>
      op match
        case c: PlaylistExporter.ProgressNotification.Copy =>
          (c.currentOperation, c.fileName) :: list
        case _ => list
