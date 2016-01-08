/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.io

import akka.actor.ActorRef

object CopyState {
  /**
   * Generates a state transition to the initial ''CopyState''. This method
   * bootstrap a copy operation. The passed in chunk size is used for all read
   * operations.
   * @param readChunkSize the chunk size for read operations
   * @return a transition to the initial ''CopyState''
   */
  def init(readChunkSize: Int): CopyStateTransition = {
    val readMsg = FileReaderActor.ReadData(readChunkSize)
    CopyStateTransition(nextState = CopyState(readDone = false, writeDone = false,
      readPending = true, writePending = false, pendingResult = None,
      readRequest = readMsg), readerMessage = Some(readMsg), writerMessage = None)
  }
}

/**
 * A class holding state information about an operation which copies a file
 * from a reader actor to a writer actor.
 *
 * When doing such a copy operation a bunch of messages have to be handled.
 * Doing this correct and efficient (for instance, the reader actor could
 * already read another chunk of data while the writer is still writing), is
 * hard and error-prone. This class has the goal to simplify the implementation
 * of copy operations.
 *
 * Basically, an instance holds information about the current state of the
 * copy process. Which actor is currently busy, which information has already
 * been read and can be written, is the operation complete, etc.
 * Incoming messages can be passed to the ''update()'' method and produce an
 * updated state instance. In addition, the messages to be passed to the reader
 * and writer actors are generated. Client code only have to call ''update()''
 * repeatedly and serve the reader and writer actors in use until a state
 * indicates that the operation is complete.
 *
 * An initial instance can be queried from the ''init()'' method of the
 * companion object.
 *
 * Note that an instance has a number of fields representing the actual state.
 * These values are typically of less relevance for client code, but they are
 * accessible to have some feedback about the operation in progress.
 *
 * @param readPending flag if currently a read operation is in progress
 * @param writePending flag if currently a write operation is in progress
 * @param pendingResult a pending read result
 * @param readRequest the object for requesting read operations
 * @param readDone flag whether all data has been read
 * @param writeDone flag whether all data has been written
 */
case class CopyState(readPending: Boolean, writePending: Boolean, pendingResult:
Option[FileReaderActor.ReadResult], readRequest: FileReaderActor.ReadData, readDone: Boolean,
                     writeDone: Boolean) {
  /**
   * Produces a follow-up state object by applying the given message to the
   * current state stored in this instance.
   * @param msg the message to be processed
   * @return a transition to the follow-up state
   */
  def update(msg: Any): CopyStateTransition = {
    msg match {
      case res: FileReaderActor.ReadResult if readPending && !readDone =>
        if (writePending) CopyStateTransition(copy(readPending = false, pendingResult = Some(res)
        ), None, None)
        else CopyStateTransition(copy(writePending = true), Some(readRequest), Some(res))

      case FileReaderActor.EndOfFile(_) if readPending && !readDone =>
        CopyStateTransition(copy(readDone = true, readPending = false, writePending = true,
          writeDone = !writePending), None,
          if (writePending) None else Some(CloseRequest))

      case _: FileWriterActor.WriteResult if writePending && !writeDone =>
        if (readPending) CopyStateTransition(copy(writePending = false), None, None)
        else if (readDone) CopyStateTransition(copy(writeDone = true), None, Some(CloseRequest))
        else CopyStateTransition(copy(readPending = true, pendingResult = None), Some
          (readRequest), pendingResult)

      case CloseAck(_) if writeDone && writePending =>
        CopyStateTransition(copy(writePending = false), None, None)

      case _ =>
        idleTransition
    }
  }

  /**
   * Returns a flag whether the copy operation is done. This is the final
   * state, all messages have been processed.
   * @return a flag whether the copy operation is done
   */
  def done: Boolean = readDone && writeDone && !writePending

  /**
   * Produces an idle transition that does not change the copy state.
   * @return the idle transition
   */
  private def idleTransition: CopyStateTransition = {
    CopyStateTransition(this, None, None)
  }
}

/**
 * A class describing a transition from a [[CopyState]] to the next state.
 *
 * Objects of this class are returned by the ''update()'' method of
 * ''CopyState''. The meaning is as follows: If the messages stored in this
 * object are sent to the corresponding reader and writer actors, the state of
 * the ongoing copy operation will switch to the contained next state.
 * @param nextState the next state
 * @param readerMessage the optional message to be sent to the reader actor
 * @param writerMessage the optional message to be sent to the writer actor
 */
case class CopyStateTransition(nextState: CopyState, readerMessage: Option[Any], writerMessage:
Option[Any]) {
  /**
   * Sends the messages stored in this object to the corresponding actors
   * passed as parameters.
   * @param reader the reader actor
   * @param writer the writer actor
   * @param sender the sending actor
   */
  def sendMessages(reader: ActorRef, writer: ActorRef)(implicit sender: ActorRef): Unit = {
    def invokeActor(actor: ActorRef, optMsg: Option[Any]): Unit = {
      optMsg foreach (actor.tell(_, sender))
    }

    invokeActor(reader, readerMessage)
    invokeActor(writer, writerMessage)
  }
}
