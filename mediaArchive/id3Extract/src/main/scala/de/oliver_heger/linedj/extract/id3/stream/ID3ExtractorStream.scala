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

import de.oliver_heger.linedj.extract.id3.model.Mp3Metadata
import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{Broadcast, FileIO, GraphDSL, RunnableGraph}
import org.apache.pekko.util.ByteString

import java.nio.file.Path
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing functionality to extract metadata for MP3 audio files.
  *
  * This object offers a function that generates a [[MediaMetadata]] object for
  * a passed in audio file. It extracts the data by running a stream over this
  * file that splits the content to the different extractor sources defined in
  * this package. With the combined results of these sources, all data 
  * supported by [[MediaMetadata]] is available.
  */
object ID3ExtractorStream:
  /**
    * Returns a [[Future]] with a [[MediaMetadata]] object that contains the
    * available metadata for the audio file with the given path. This function
    * expects that this is an MP3 audio file. It is able to extract metadata 
    * from MPEG and ID3 frames.
    *
    * @param tagSizeLimit the maximum size of tags to process
    * @param path         the path to the audio file
    * @param system       the actor system
    * @return a [[Future]] with the extracted metadata
    */
  def extractMetadata(tagSizeLimit: Int = Int.MaxValue)
                     (path: Path)
                     (using system: ActorSystem): Future[MediaMetadata] =
    given ExecutionContext = system.dispatcher

    val graph = createExtractorGraph(path, tagSizeLimit)
    graph.run()

  /**
    * Creates a [[MediaMetadata]] object from the passed in parameters. The
    * tricky part here is the combination of the different metadata providers
    * which may contain only partial or even contradicting information. This
    * function prefers providers with a higher ID3 version.
    *
    * @param optV1Provider the optional provider for ID3v1 metadata
    * @param v2Providers   the providers for ID3v2 metadata
    * @param mp3Data       data about the MP3 file
    * @param fileInfo      data about the file
    * @return the resulting [[MediaMetadata]]
    */
  private[stream] def createMetadata(optV1Provider: Option[MetadataProvider],
                                     v2Providers: List[MetadataProvider],
                                     mp3Data: Mp3Metadata,
                                     fileInfo: FileInfoSink.FileInfo): MediaMetadata =
    val sortedV2Providers = v2Providers.sortWith(_.version.value < _.version.value)
    val sortedProviders = optV1Provider.fold(sortedV2Providers) { v1Provider =>
      v1Provider :: sortedV2Providers
    }.reverse

    @tailrec
    def queryPrioritizedProviders[A](providers: List[MetadataProvider])(f: MetadataProvider => Option[A]): Option[A] =
      providers match
        case h :: t =>
          val optResult = f(h)
          if optResult.isDefined then optResult
          else queryPrioritizedProviders(t)(f)
        case _ =>
          None

    def queryProviders[A](f: MetadataProvider => Option[A]): Option[A] =
      queryPrioritizedProviders(sortedProviders)(f)

    MediaMetadata(
      title = queryProviders(_.title),
      artist = queryProviders(_.artist),
      album = queryProviders(_.album),
      inceptionYear = queryProviders(_.inceptionYear),
      trackNumber = queryProviders(_.trackNo),
      duration = Some(mp3Data.duration),
      formatDescription = Some(generateFormatDescription(mp3Data)),
      size = fileInfo.size,
      checksum = fileInfo.hashValue
    )

  /**
    * Generates a human-readable format description for an MP3 audio file based
    * on the given metadata.
    *
    * @param data the metadata for the MP3 file
    * @return the format description
    */
  private def generateFormatDescription(data: Mp3Metadata): String =
    s"${data.maximumBitRate / 1000} kbps"

  /**
    * The function to combine the results of the different sinks used by the 
    * extractor stream. This function gathers the data from the different
    * information sources and constructs a [[MediaMetadata]] object.
    *
    * @param futID3v1Provider  the result from the ID3v1 extractor sink
    * @param futID3v2Providers the result from the ID3v2 extractor sink
    * @param futMp3Data        the result from the MP3 data extractor sink
    * @param futFileInfo       the result from the file info sink
    * @param ec                the execution context
    * @return a [[Future]] with the resulting [[MediaMetadata]]
    */
  private def combineSinkResults(futID3v1Provider: Future[Option[MetadataProvider]],
                                 futID3v2Providers: Future[List[MetadataProvider]],
                                 futMp3Data: Future[Mp3Metadata],
                                 futFileInfo: Future[FileInfoSink.FileInfo])
                                (using ec: ExecutionContext): Future[MediaMetadata] =
    for
      optV1Provider <- futID3v1Provider
      v2Providers <- futID3v2Providers
      mp3Data <- futMp3Data
      fileInfo <- futFileInfo
    yield createMetadata(optV1Provider, v2Providers, mp3Data, fileInfo)

  /**
    * Constructs a [[RunnableGraph]] that can process the content of an audio
    * file and extract a [[MediaMetadata]] instance based on this processing.
    *
    * @param path         the path to the audio file
    * @param tagSizeLimit the maximum tag size to process
    * @return the graph for processing this audio file
    */
  private def createExtractorGraph(path: Path, tagSizeLimit: Int)
                                  (using ec: ExecutionContext): RunnableGraph[Future[MediaMetadata]] =
    val sinkID3v2 = new ID3v2ExtractorSink(tagSizeLimit)
    val sinkID3v1 = new ID3v1ExtractorSink
    val sinkMp3Data = new Mp3DataExtractorSink
    val sinkInfo = new FileInfoSink
    val source = FileIO.fromPath(path)

    RunnableGraph.fromGraph(GraphDSL.createGraph(sinkID3v1, sinkID3v2, sinkMp3Data, sinkInfo)(combineSinkResults) {
      implicit builder =>
        (v1Sink, v2Sink, mp3Sink, infoSink) =>
          import GraphDSL.Implicits.*

          val broadcast = builder.add(Broadcast[ByteString](4))
          source ~> broadcast ~> v1Sink
          broadcast ~> v2Sink
          broadcast ~> mp3Sink
          broadcast ~> infoSink
          ClosedShape
    })
