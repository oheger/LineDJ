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

import de.oliver_heger.linedj.archive.server.cloud.CloudArchiveCache.CacheEntry
import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.io.stream.ListSeparatorStage
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.util.ByteString
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

object CloudArchiveCache:
  /** The name of the document storing the content of the cache. */
  private val TocName = "content.json"

  /** The extension for files with a medium description. */
  private val ExtMediumDesc = "json"

  /** The extension for files with song metadata for a medium. */
  private val ExtMediumSongs = "mdt"

  /**
    * An enumeration for the different types of entries supported by this
    * cache. Such a type has to be specified when requesting an entry.
    */
  enum EntryType:
    /**
      * The entry type for a medium description file. This is a JSON file with
      * a description of the medium.
      */
    case MediumDescription

    /**
      * The entry type for the document listing the songs contained on a
      * medium. This is a JSON file containing the metadata of all songs, such
      * as title, artist, album, etc.
      */
    case MediumSongs

  /**
    * A trait defining operations available on an entry in the cache. Callers
    * can request such an object for a specific file in the cache. The methods
    * can then be used to read or update this file.
    */
  trait CacheEntry:
    /**
      * Returns a [[Source]] to the file referenced by this entry.
      *
      * @return a [[Source]] for reading the associated file
      */
    def source: Source[ByteString, Any]

    /**
      * Returns a [[Sink]] to write the file referenced by this entry.
      *
      * @return a [[Sink]] for writing the associated file
      */
    def sink: Sink[ByteString, Future[Any]]

  /**
    * An internal representation of a medium entry that is used for JSON
    * serialization. It uses a plain String as ID type.
    *
    * @param id        the ID of the medium as string
    * @param timestamp the last-updated timestamp
    */
  private case class MediumEntryInternal(id: String,
                                         timestamp: Long)

  /**
    * Converts an internal medium entry to an official one.
    *
    * @param e the internal entry
    * @return the [[MediumEntry]]
    */
  private def toMediumEntry(e: MediumEntryInternal): MediumEntry =
    MediumEntry(Checksums.MediumChecksum(e.id), e.timestamp)

  /**
    * Converts a [[MediumEntry]] to an internal one.
    *
    * @param e the entry
    * @return the internal entry
    */
  private def toInternalEntry(e: MediumEntry): MediumEntryInternal =
    MediumEntryInternal(e.id.checksum, e.timestamp)

  private given fmtMediumEntry: JsonFormat[MediumEntryInternal] = jsonFormat2(MediumEntryInternal.apply)
end CloudArchiveCache

/**
  * A class for caching metadata about a cloud archive in a local folder.
  *
  * The cloud archive server needs information about the media hosted in a
  * cloud archive and the songs they contain. This information is available in
  * the archive's ''table of content'', and in metadata files referenced from
  * this document. Loading this information on every startup of the server is
  * inefficient and a waste of bandwidth. Therefore, this cache class is
  * introduced which is responsible for mirroring the relevant data locally.
  *
  * The cache for a specific cloud archive contains the following elements:
  *  - A stripped-down table-of-contents file providing information about the
  *    media available in the archive. This is mainly used to check whether the
  *    cache is still up-to-date.
  *  - For each medium, the description file is stored.
  *  - For each medium, a file with metadata about the contained songs is
  *    stored.
  *
  * The application loads the data about media directly from the cloud archive.
  * This class is mainly concerned with the organization of the local cache.
  * The mirroring of the data from the archive is handled by other components.
  *
  * @param cacheFolder the folder where to store the cached information
  */
class CloudArchiveCache(val cacheFolder: Path):

  import CloudArchiveCache.*

  /**
    * Saves an updated content document for the managed archive cache. This
    * function is called when the local cache was updated, since it was no
    * longer in sync with the archive. Note that this function does not perform
    * any cross-checks with the real content of the cache directory.
    *
    * @param content the content document to store
    * @param system  the actor system
    * @return a [[Future]] with the result of the operation
    */
  def saveContent(content: CloudArchiveContent)
                 (using system: ActorSystem): Future[Any] =
    val source = Source(content.media.values.toList)
      .map(toInternalEntry)
      .via(ListSeparatorStage.jsonStage)
    val sink = FileIO.toPath(contentPath)
    source.runWith(sink)

  /**
    * Loads the document with the media information stored in this cache. The
    * function also checks whether all the files referenced by this document
    * are actually available in the cache folder. So, the caller can be sure
    * that the returned object contains only valid entries.
    *
    * @param system the actor system
    * @return a [[Future]] with the validated content of this cache
    */
  def loadAndValidateContent(using system: ActorSystem): Future[CloudArchiveContent] =
    val toc = contentPath
    if Files.isRegularFile(toc) then
      val source = FileIO.fromPath(toc)
      val parser = JsonStreamParser.parseStream[MediumEntryInternal, Any](source)
      val sink = Sink.fold[Map[Checksums.MediumChecksum, MediumEntry], MediumEntry](Map.empty): (map, e) =>
        map + (e.id -> e)

      parser.map(toMediumEntry).runWith(sink).map(CloudArchiveContent.apply)
    else
      Future.successful(CloudArchiveContent(Map.empty))

  /**
    * Returns a [[CacheEntry]] that can be used to access the file with a
    * specific type for a specific medium. Note that this function does not
    * check whether the passed in parameters actually reference a valid medium
    * stored in the cache; this is not possible, as the resulting entry can
    * also be used to add new entries.
    *
    * @param mediumID  the ID of the affected medium
    * @param entryType the type of the desired entry
    * @return a [[CacheEntry]] to access the requested file
    */
  def entryFor(mediumID: Checksums.MediumChecksum, entryType: EntryType): CacheEntry =
    val path = cachePath(entryName(mediumID, entryType))

    new CacheEntry:
      override def source: Source[ByteString, Any] =
        FileIO.fromPath(path)

      override def sink: Sink[ByteString, Future[Any]] =
        FileIO.toPath(path)

  /**
    * Returns the path to the document storing the content of this cache.
    *
    * @return the path to the content document
    */
  private def contentPath: Path = cachePath(TocName)

  /**
    * Returns the name of the cache entry defined by the passed parameters.
    *
    * @param mediumID  the ID of the medium
    * @param entryType the type of the entry
    * @return the name of the file for this entry
    */
  private def entryName(mediumID: Checksums.MediumChecksum, entryType: EntryType): String =
    val extension = entryType match
      case EntryType.MediumDescription => ExtMediumDesc
      case EntryType.MediumSongs => ExtMediumSongs
    s"${mediumID.checksum}.$extension"

  /**
    * Helper function to return a [[Path]] to a file contained in this cache.
    *
    * @param name the name of the file
    * @return the [[Path]] to this file
    */
  private def cachePath(name: String): Path = cacheFolder.resolve(name)

  /** Returns the implicit execution context from the given actor system. */
  private given ec(using system: ActorSystem): ExecutionContext = system.dispatcher
