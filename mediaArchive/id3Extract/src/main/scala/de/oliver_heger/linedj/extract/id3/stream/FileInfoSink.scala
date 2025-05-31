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

package de.oliver_heger.linedj.extract.id3.stream

import de.oliver_heger.linedj.extract.id3.stream.FileInfoSink.FileInfo
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import org.apache.pekko.stream.{Attributes, Inlet, SinkShape}
import org.apache.pekko.util.ByteString

import java.security.MessageDigest
import scala.concurrent.{Future, Promise}

object FileInfoSink:
  /**
    * A data class that collects the information extracted from the content of
    * the processed audio file. An instance of this class is used as
    * materialized result value of this sink.
    *
    * @param size          the size of the file in bytes
    * @param hashValue     a hash value generated for the contents (as Base64
    *                      encoded string)
    * @param hashAlgorithm the algorithm used for calculating the hash value
    */
  case class FileInfo(size: Long,
                      hashValue: String,
                      hashAlgorithm: String)
end FileInfoSink

/**
  * A sink implementation that gathers general information about an audio file.
  *
  * This sink consumes the full content of the file and processes it to
  * generate some data required for the metadata managed by a media archive.
  * This data is available as the materialized value of this sink.
  *
  * @param hashAlgorithm the algorithm to generate the hash value
  */
class FileInfoSink(hashAlgorithm: String = "SHA-256")
  extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[FileInfo]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("FileInfoSink.in")

  override def shape: SinkShape[ByteString] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
  (GraphStageLogic, Future[FileInfo]) =
    val promiseResult = Promise[FileInfo]()

    val logic = new GraphStageLogic(shape):
      /** Keeps track on the size of the data processed by this sink. */
      private var fileSize = 0L

      /** The digest for calculating the hash value. */
      private val digest = MessageDigest.getInstance(hashAlgorithm)

      override def preStart(): Unit =
        pull(in)

      setHandler(in, new InHandler:
        override def onPush(): Unit =
          processChunk(grab(in))
          pull(in)

        override def onUpstreamFinish(): Unit =
          super.onUpstreamFinish()
          promiseResult.success(createResult())

        override def onUpstreamFailure(ex: Throwable): Unit =
          super.onUpstreamFailure(ex)
          promiseResult.failure(ex)
      )

      /**
        * Updates the internal state of this sink based on the passed in data
        * chunk.
        *
        * @param data the chunk of data
        */
      private def processChunk(data: ByteString): Unit =
        digest.update(data.toByteBuffer)
        fileSize += data.size

      /**
        * Creates the result of this sink when the full content of the current
        * file has been processed.
        *
        * @return the result of this sink
        */
      private def createResult(): FileInfo =
        val hashValue = Checksums.toHexString(digest.digest())
        FileInfo(fileSize, hashValue, hashAlgorithm)

    (logic, promiseResult.future)    
    