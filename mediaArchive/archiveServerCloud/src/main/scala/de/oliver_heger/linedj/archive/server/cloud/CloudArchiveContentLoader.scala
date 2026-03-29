/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.metadata.persistence.ArchiveTocSerializer
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import de.oliver_heger.linedj.shared.archive.media.{MediumDescription, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.{ActorAttributes, ClosedShape, Supervision}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed, actor as classic}

import scala.concurrent.Future
import scala.util.{Failure, Try}

object CloudArchiveContentLoader:
  /**
    * A dummy medium ID. This is used to populate temporary song metadata
    * objects which require such an ID. The archive server, however, references
    * media via a different ID.
    */
  private val DummyMediumId = MediumID("dummy-ID", None)

  /** The logger. */
  private val log = LogManager.getLogger(CloudArchiveContentLoader.getClass)

  /**
    * A supervision strategy to be applied to streams that download metadata
    * documents from cloud archives. The strategy causes the affected documents
    * to be skipped, while the stream continues processing with the next
    * elements.
    */
  private val loadContentStreamDecider: Supervision.Decider = ex =>
    log.error("Download from cloud archive failed.", ex)
    Supervision.resume

  /**
    * Returns a [[Sink]] that passes update commands to a specific content 
    * actor.
    *
    * @param contentActor the content actor
    * @return the [[Sink]] to populate this actor
    */
  private def contentActorSink(contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand]):
  Sink[ArchiveCommands.UpdateArchiveContentCommand, Future[Any]] =
    Sink.foreach[ArchiveCommands.UpdateArchiveContentCommand](contentActor ! _)

  /**
    * Returns a [[Flow]] that parses a serialized medium description and 
    * converts it to a command that can be passed to the archive content actor.
    *
    * @param mediumID    the ID of the current medium
    * @param archiveName the name of the archive
    * @return the [[Flow]] to parse the medium description
    */
  private def mediumDescriptionParser(mediumID: Checksums.MediumChecksum, archiveName: String):
  Flow[ByteString, ArchiveCommands.UpdateArchiveContentCommand, NotUsed] =
    JsonStreamParser.streamParserFlow[MediumDescription]()
      .map: description =>
        val details = ArchiveModel.MediumDetails(
          overview = ArchiveModel.MediumOverview(mediumID, description.name),
          description = description.description,
          orderMode = toOrderMode(description.orderMode),
          archiveName = archiveName
        )
        ArchiveCommands.UpdateArchiveContentCommand.AddMedium(details)

  /**
    * Returns a [[Flow]] that parses serialized song metadata of a medium and
    * converts this to commands that can be passed to the archive content 
    * actor.
    *
    * @param mediumID the ID of the current medium
    * @return the [[Flow]] to parse song metadata
    */
  private def metadataParser(mediumID: Checksums.MediumChecksum):
  Flow[ByteString, ArchiveCommands.UpdateArchiveContentCommand, NotUsed] =
    MetadataParser.metadataParserFlow(DummyMediumId).map: mpr =>
      ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
        mediumID = mediumID,
        fileUri = mpr.uri,
        metadata = mpr.metadata
      )

  /**
    * Converts a string for the order mode to the corresponding enumeration
    * literal. If this fails, no order mode is used, and an error is logged.
    *
    * @param orderStr the order mode as string
    * @return an [[Option]] with the converted order mode
    */
  private def toOrderMode(orderStr: String): Option[ArchiveModel.OrderMode] =
    Try:
      ArchiveModel.OrderMode.valueOf(orderStr)
    .recoverWith: ex =>
      log.error("Unsupported order mode '{}'.", orderStr)
      Failure(ex)
    .toOption
end CloudArchiveContentLoader

/**
  * A class that is responsible for loading the content of a cloud archive
  * either directly from the archive or from a local cache, as far as the cache
  * is up-to-date.
  *
  * The class offers a function that obtains the content of a cloud archive and
  * passes the corresponding information to a content management actor. The
  * function loads the archive's content document and queries the cache for its
  * content. Based on this information, it can decide which data to load from
  * where, and it can also sync the cache, so that it gets updated.
  *
  * @param system the actor system
  */
class CloudArchiveContentLoader(using system: classic.ActorSystem):

  import CloudArchiveContentLoader.*

  /**
    * Loads the content of a specific cloud archive using a preconfigured
    * [[ContentDownloader]] and populates the given content actor with this
    * data.
    *
    * @param downloader   the object to download content from the archive
    * @param cache        the local cache for the archive's metadata
    * @param archiveName  the name of the archive
    * @param contentActor the content actor to populate
    * @param parallelism  the number of media to process in parallel
    * @return a [[Future]] with the result of the load operation
    */
  def loadContent(downloader: ContentDownloader,
                  cache: CloudArchiveCache,
                  archiveName: String,
                  contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                  parallelism: Int): Future[Done] =
    val futCacheContent = cache.loadAndValidateContent
    val futSourceArchiveContent = downloader.loadContentDocument()
    for
      cacheContent <- futCacheContent
      sourceArchiveContent <- futSourceArchiveContent
      result <- loadContentAndUpdateCache(
        downloader,
        cache,
        archiveName,
        contentActor,
        parallelism,
        cacheContent,
        sourceArchiveContent
      )
    yield
      result

  /**
    * Loads the content of a specific cloud archive, updates the local cache
    * for this archive, and populates the given content actor with the 
    * archive's data.
    *
    * @param downloader       the object to download content from the archive
    * @param cache            the local cache for the archive's metadata
    * @param archiveName      the name of the archive
    * @param contentActor     the content actor to populate
    * @param parallelism      the number of media to process in parallel
    * @param cacheContent     the content of the local cache
    * @param archiveTocSource the source for the archive's content document
    * @return a [[Future]] with the result of the operation
    */
  private def loadContentAndUpdateCache(downloader: ContentDownloader,
                                        cache: CloudArchiveCache,
                                        archiveName: String,
                                        contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                                        parallelism: Int,
                                        cacheContent: CloudArchiveContent,
                                        archiveTocSource: Source[ByteString, Any]): Future[Done] =
    val archiveTocSink = Sink.fold[Map[Checksums.MediumChecksum, MediumEntry], MediumEntry](Map.empty): (map, e) =>
      map + (e.id -> e)
    val tocReader = ArchiveTocSerializer.reader()
    val loaderStream = tocReader.readToc(archiveTocSource).mapAsync(parallelism): tocEntry =>
      processMedium(
        downloader,
        cache,
        archiveName,
        contentActor,
        cacheContent,
        tocEntry
      )
    .toMat(archiveTocSink)(Keep.right)

    val supervisedStream = loaderStream.withAttributes(ActorAttributes.supervisionStrategy(loadContentStreamDecider))
    supervisedStream.run().flatMap: mediaData =>
      if mediaData == cacheContent.media then
        Future.successful(Done)
      else
        cache.saveContent(CloudArchiveContent(mediaData)).map(_ => Done)

  /**
    * Processes a single medium of a cloud archive whose content is to be 
    * obtained. The function decides whether this medium's data is available in
    * the local cache. If so, it is obtained from there. Otherwise, the 
    * function loads the data from the archive and writes it to the cache.
    *
    * @param downloader   the object to download content from the archive
    * @param cache        the local cache for the archive's metadata
    * @param archiveName  the name of the archive
    * @param contentActor the content actor to populate
    * @param cacheContent the content of the local cache
    * @param mediumEntry  the entry for the current medium
    * @return a [[Future]] with information about the processed medium
    */
  private def processMedium(downloader: ContentDownloader,
                            cache: CloudArchiveCache,
                            archiveName: String,
                            contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                            cacheContent: CloudArchiveContent,
                            mediumEntry: ArchiveTocSerializer.MediumEntry): Future[MediumEntry] =
    val mediumID = Checksums.MediumChecksum(mediumEntry.checksum)
    val futMedium = if cacheContent.media.get(mediumID).exists(_.timestamp == mediumEntry.changedAt) then
      readMediumFromCache(cache, contentActor, mediumID, archiveName)
    else
      readMediumFromArchive(downloader, cache, contentActor, mediumEntry, archiveName)

    futMedium map : _ =>
      MediumEntry(mediumID, mediumEntry.changedAt)

  /**
    * Reads data about a medium from the cache and passes it to the archive 
    * content actor.
    *
    * @param cache        the local cache for the archive's metadata
    * @param contentActor the content actor to populate
    * @param mediumID     the ID of the current medium
    * @param archiveName  the name of the archive
    * @return a [[Future]] with the result of the operation
    */
  private def readMediumFromCache(cache: CloudArchiveCache,
                                  contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                                  mediumID: Checksums.MediumChecksum,
                                  archiveName: String): Future[Any] =
    val sink = contentActorSink(contentActor)
    val futDescription = cache.entryFor(mediumID, CloudArchiveCache.EntryType.MediumDescription).source
      .via(mediumDescriptionParser(mediumID, archiveName))
      .runWith(sink)
    val futMetadata = cache.entryFor(mediumID, CloudArchiveCache.EntryType.MediumSongs).source
      .via(metadataParser(mediumID))
      .runWith(sink)

    for
      _ <- futDescription
      _ <- futMetadata
    yield Done

  /**
    * Reads data about a medium from the cloud archive (via the provided
    * downloader) and passes it to the archive content actor. The data is also
    * stored in the local cache.
    *
    * @param downloader   the object to load content from the archive
    * @param cache        the local cache for the archive's metadata
    * @param contentActor the content actor to populate
    * @param mediumEntry  the entry for the current medium
    * @param archiveName  the name of the archive
    * @return a [[Future]] with the result of the operation
    */
  private def readMediumFromArchive(downloader: ContentDownloader,
                                    cache: CloudArchiveCache,
                                    contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                                    mediumEntry: ArchiveTocSerializer.MediumEntry,
                                    archiveName: String): Future[Any] =
    val mediumID = Checksums.MediumChecksum(mediumEntry.checksum)
    val futDescriptionSource = downloader.loadMediumDescription(mediumEntry.mediumDescriptionPath)
    val futSongsSource = downloader.loadMediumMetadata(mediumID)
    val descriptionEntry = cache.entryFor(mediumID, CloudArchiveCache.EntryType.MediumDescription)
    val songsEntry = cache.entryFor(mediumID, CloudArchiveCache.EntryType.MediumSongs)
    val futDescription = futDescriptionSource flatMap : source =>
      downloadAndPassContent(
        contentActor,
        source,
        mediumDescriptionParser(mediumID, archiveName),
        descriptionEntry.sink
      )
    val futSongs = futSongsSource flatMap : source =>
      downloadAndPassContent(
        contentActor,
        source,
        metadataParser(mediumID),
        songsEntry.sink
      )

    for
      _ <- futDescription
      _ <- futSongs
    yield Done

  /**
    * Processes a single content document from the cloud archive from a 
    * provided [[Source]]. The function parses the document and passes the
    * results to a content actor. It also writes the data to a [[Sink]] that
    * points to the correct location in the local cache.
    *
    * @param contentActor the content actor to populate
    * @param source       the source for the content document
    * @param parser       the function to parse the content document
    * @param cacheSink    the sink for the target entry in the local cache
    * @return a [[Future]] with the outcome of the operation
    */
  private def downloadAndPassContent(contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                                     source: Source[ByteString, Any],
                                     parser: Flow[ByteString, ArchiveCommands.UpdateArchiveContentCommand, NotUsed],
                                     cacheSink: Sink[ByteString, Future[Any]]): Future[Any] =
    val contentSink = contentActorSink(contentActor)
    val g = RunnableGraph.fromGraph(GraphDSL.createGraph(cacheSink, contentSink)(processingFuture) { implicit builder =>
      (sinkEntry, sinkActor) =>
        import GraphDSL.Implicits.*
        val broadcast = builder.add(Broadcast[ByteString](2))

        source ~> broadcast ~> sinkEntry
        broadcast ~> parser ~> sinkActor
        ClosedShape
    })
    g.run()

  /**
    * Combines the futures for the sinks involved in processing a content 
    * document downloaded for the archive. Makes sure that the operation is
    * reported as successfully finished only after both futures have completed.
    *
    * @param futSink1 the future for the result of sink 1
    * @param futSink2 the future for the result of sink 2
    * @return the combined future
    */
  private def processingFuture(futSink1: Future[Any], futSink2: Future[Any]): Future[Any] =
    for
      _ <- futSink1
      _ <- futSink2
    yield Done
