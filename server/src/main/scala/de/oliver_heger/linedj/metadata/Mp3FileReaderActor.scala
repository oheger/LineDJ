/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.metadata

import java.io.IOException
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import de.oliver_heger.linedj.io.FileReaderActor.ReadData
import de.oliver_heger.linedj.io.{ChannelHandler, FileReaderActor}
import de.oliver_heger.linedj.utils.ChildActorFactory

object Mp3FileReaderActor {

  private class Mp3FileReaderActorImpl(extractionContext: MetaDataExtractionContext) extends
  Mp3FileReaderActor(extractionContext) with ChildActorFactory

  /**
   * Returns a ''Props'' object for creating a new actor instance.
   * @param extractionContext the extraction context
   * @return ''Props'' for creating a new actor instance
   */
  def apply(extractionContext: MetaDataExtractionContext): Props =
    Props(classOf[Mp3FileReaderActorImpl], extractionContext)
}

/**
 * A specialized actor implementation responsible for reading MP3 files and
 * passing extracted chunks of data to processing actors.
 *
 * In order to obtain some types of meta data, MP3 files have to be read
 * completely. This is done by this actor. It interacts with a reader actor to
 * read a file chunk-wise. For each chunk of data read, a [[ProcessMp3Data]]
 * message is generated and sent to the collector actor. The collector actor
 * passes this information to the corresponding processing actors.
 *
 * So, this actor does no processing itself, but provides the source data on
 * which processing is done.
 *
 * @param extractionContext the central extraction context object
 */
class Mp3FileReaderActor(extractionContext: MetaDataExtractionContext) extends Actor with
ActorLogging {
  this: ChildActorFactory =>

  /** The actor for reading media files. */
  private var readerActor: ActorRef = _

  /** The path to the file which is currently processed. */
  private var currentPath: Path = _

  /**
   * A special supervisor strategy used by this actor class. Exceptions
   * thrown by child actors (typically caused by read errors) should cause an
   * escalation to the parent actor. This gives the parent the chance to
   * terminate the whole sub hierarchy of the affected reader actor.
   */
  override val supervisorStrategy = OneForOneStrategy() {
    case _: IOException => Escalate
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    val underlyingReaderActor = createChildActor(Props[FileReaderActor])
    readerActor = createChildActor(Props(classOf[ID3FrameReaderActor], underlyingReaderActor,
      extractionContext))
  }

  override def receive: Receive = {
    case ReadMediaFile(path) =>
      readerActor ! ChannelHandler.InitFile(path)
      readerActor ! ReadData(extractionContext.config.metaDataReadChunkSize)
      currentPath = path
      log.info("Read media file request for {}.", path)

    case result: FileReaderActor.ReadResult =>
      readerActor ! ReadData(extractionContext.config.metaDataReadChunkSize)
      extractionContext.collectorActor ! ProcessMp3Data(currentPath, result)

    case eof: FileReaderActor.EndOfFile if eof matches currentPath =>
      extractionContext.collectorActor ! MediaFileRead(currentPath)
  }
}
