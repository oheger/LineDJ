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

import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

/**
  * A stage which sends progress notifications during a copy operation.
  *
  * The stage counts the bytes passing through. Whenever a multiple of the
  * defined progress size has been processed, a notification is published on
  * the message bus. When the stage terminates, a final notification is
  * published which reports the complete size of the file. This is the
  * indicator that the file has been copied completely.
  *
  * If the size of the file is not known upfront, which is the case for a song
  * without metadata, then no notifications are sent while the file is copied.
  * In this case only the final notification is published. It reports the
  * number of bytes that have been copied.
  *
  * @param messageBus   the message bus to publish the notifications on
  * @param totalOps     the total number of copy operations
  * @param currentOp    the index of the current copy operation
  * @param totalSize    the total size to be copied (in bytes)
  * @param currentSize  the size of the current file (in bytes); 0 if unknown
  * @param fileName     the name of the file which is copied
  * @param progressSize the number of bytes after which a notification is sent
  */
private class CopyProgressNotificationStage(messageBus: MessageBus,
                                            totalOps: Int,
                                            currentOp: Int,
                                            totalSize: Long,
                                            currentSize: Long,
                                            fileName: String,
                                            progressSize: Int)
  extends GraphStage[FlowShape[ByteString, ByteString]]:
  val in: Inlet[ByteString] = Inlet("CopyProgressNotificationStage.in")
  val out: Outlet[ByteString] = Outlet("CopyProgressNotificationStage.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      /** A counter for the number of bytes that have been processed. */
      private var bytesProcessed = 0L

      /** A counter for the progress of the current copy operation. */
      private var progressCount = 0L

      /** The size of the current file. */
      private var fileSize = currentSize

      /** A flag whether the size of the current file is known. */
      private val sizeKnown = currentSize > 0

      /**
        * Publishes a notification about the given number of bytes that have
        * been processed for the current file.
        *
        * @param progressedSize the number of bytes processed so far
        */
      private def publishNotification(progressedSize: Long): Unit =
        messageBus.publish(PlaylistExporter.ProgressNotification.Copy(
          totalOps, currentOp, totalSize, fileSize, progressedSize, fileName))

      setHandler(in, new InHandler:
        override def onPush(): Unit =
          val chunk = grab(in)
          push(out, chunk)
          bytesProcessed += chunk.size
          if sizeKnown then
            val newProgressCount = bytesProcessed / progressSize
            if progressCount != newProgressCount then
              progressCount = newProgressCount
              publishNotification(progressCount * progressSize)

        override def onUpstreamFinish(): Unit =
          if !sizeKnown then fileSize = bytesProcessed
          // If the last chunk filled the file exactly, a notification has
          // already been published. So it is not repeated here.
          if progressCount * progressSize < fileSize then
            publishNotification(fileSize)
          completeStage()
      )

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          pull(in)
      )
