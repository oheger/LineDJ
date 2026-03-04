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

import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.shared.archive.media.{MediumDescription, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed, actor as classic}

import scala.concurrent.{ExecutionContext, Future}
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
    * @return a [[Future]] with the result of the load operation
    */
  def loadContent(downloader: ContentDownloader,
                  cache: CloudArchiveCache,
                  archiveName: String,
                  contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand]): Future[Done] =
    cache.loadAndValidateContent flatMap : cacheContent =>
      val mediaSource = Source(cacheContent.media.keySet)
      mediaSource.mapAsync(1): mediumID =>
        readMediumFromCache(cache, contentActor, mediumID, archiveName)
      .runWith(Sink.ignore)

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
    * Returns an implicit [[ExecutionContext]] that is obtained from the 
    * current actor system.
    *
    * @param system the actor system
    * @return the [[ExecutionContext]]
    */
  private given executionContext(using system: classic.ActorSystem): ExecutionContext = system.dispatcher
