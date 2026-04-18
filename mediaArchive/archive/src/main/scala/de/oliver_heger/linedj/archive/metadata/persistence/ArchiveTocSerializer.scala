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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.io.stream.ListSeparatorStage
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.util.ByteString
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.Locale
import scala.concurrent.Future

/**
  * A module offering functionality to read and write the "table of contents"
  * of a media archive.
  *
  * If configured in the configuration of a media archive, the actors that scan
  * the content of the archive generate a file listing the media stored in the
  * archive and the paths to their description and metadata files. This module
  * implements the corresponding functionality to write this file and exposes a
  * trait that can be used for this purpose.
  *
  * The "table of contents" file also contains timestamps when the media have
  * been updated or changed. The file is only written if there are changes, and
  * then only the timestamps of modified media are updated.
  *
  * There is also a trait for reading such a "table of contents" file.
  */
object ArchiveTocSerializer:
  /** The file extension used by metadata files. */
  private val MetadataFileExtension = ".mdt"

  /**
    * An internal data class that is used to serialize entries of the archive's
    * table of content. The class has more fields than the normal 
    * representation used by clients to deal with legacy entries in an older
    * data format.
    *
    * @param mediumDescriptionPath the relative path to the medium description 
    *                              file
    * @param checksum              the checksum of the medium
    * @param changedAt             the last modification time of this medium
    * @param metaDataPath          the legacy path to the metadata file used by 
    *                              old entries
    */
  private[persistence] final case class InternalMediumEntry(mediumDescriptionPath: String,
                                                            checksum: Option[String],
                                                            changedAt: Option[Long],
                                                            metaDataPath: Option[String] = None)

  /**
    * A data class to represent an entry for a medium in the archive's table of
    * content.
    *
    * @param mediumDescriptionPath the relative path to the medium description 
    *                              file
    * @param checksum              the checksum of the medium
    * @param changedAt             the last modification time of this medium
    */
  final case class MediumEntry(mediumDescriptionPath: String,
                               checksum: String,
                               changedAt: Long)

  /**
    * The format to serialize and deserialize a [[InternalMediumEntry]] to JSON.
    */
  given mediumEntryFormat: RootJsonFormat[InternalMediumEntry] = jsonFormat4(InternalMediumEntry.apply)

  /**
    * Returns a new [[ArchiveTocWriter]] object. An optional [[Clock]] can be
    * provided to compute the change time for modified media.
    *
    * @param clock the [[Clock]] to determine the last-modified time
    * @return the [[ArchiveTocWriter]] instance
    */
  def writer(clock: Clock = Clock.systemDefaultZone()): ArchiveTocWriter =
    new ArchiveTocWriter:
      override def writeToc(target: Path,
                            mediaData: Map[MediumID, Checksums.MediumChecksum],
                            unusedMedia: Set[Checksums.MediumChecksum],
                            modifiedMedia: Set[Checksums.MediumChecksum])
                           (using system: ActorSystem): Future[Option[Path]] =
        updateTocFile(target, mediaData, unusedMedia, modifiedMedia, clock)

  /**
    * Returns a new [[ArchiveTocReader]] object. An optional [[Clock]] can be
    * provided to compute a default timestamp for entries missing the last
    * modified time.
    *
    * @param clock the [[Clock]] to determine the last-modified time if it is
    *              missing
    * @return the [[ArchiveTocWriter]] instance
    */
  def reader(clock: Clock = Clock.systemDefaultZone()): ArchiveTocReader =
    (tocData: Source[ByteString, Any]) =>
      tocFileSource(tocData, clock.millis()).map(toMediumEntry)

  /**
    * Returns a [[Source]] for reading a ToC document based on a raw data 
    * source. The source automatically converts old entries to the new format
    * and sets missing last-modified timestamps.
    *
    * @param rawSource the [[Source]] with the data of the content document
    * @param timestamp the timestamp to set if it is missing
    * @return the [[Source]] to read the content document
    */
  private def tocFileSource(rawSource: Source[ByteString, Any], timestamp: => Long): Source[InternalMediumEntry, Any] =
    JsonStreamParser.parseStream[InternalMediumEntry, Any](rawSource)
      .map(e => convertLegacyEntry(e, timestamp))
      .filter(_.isDefined)
      .map(_.get)

  /**
    * Returns a [[Source]] for reading a ToC file. The source automatically
    * converts old entries to the new format and sets missing last-modified
    * timestamps.
    *
    * @param target    the path to the ToC file to read
    * @param timestamp the timestamp to set if it is missing
    * @return the [[Source]] to read the file
    */
  private def tocFileSource(target: Path, timestamp: => Long): Source[InternalMediumEntry, Any] =
    tocFileSource(FileIO.fromPath(target), timestamp)

  /**
    * Writes an updated ToC file if this is necessary. An existing file is
    * read first to find out whether there are changes.
    *
    * @param target        the path to the file with the table of contents
    * @param mediaData     a map with all media and their IDs
    * @param unusedMedia   a set with the IDs of unused media files
    * @param modifiedMedia a set with the IDs of media that have been modified
    *                      and need to be updated
    * @param clock         the clock to use
    * @param system        the actor system
    * @return a [[Future]] with the path to the file that was written
    */
  private def updateTocFile(target: Path,
                            mediaData: Map[MediumID, Checksums.MediumChecksum],
                            unusedMedia: Set[Checksums.MediumChecksum],
                            modifiedMedia: Set[Checksums.MediumChecksum],
                            clock: Clock)
                           (using system: ActorSystem): Future[Option[Path]] =
    import system.dispatcher
    val timestamp = clock.millis()

    /**
      * Checks whether a ToC file already exists and reads it if this is the
      * case.
      *
      * @return a [[Future]] with the entries read from this file keyed by 
      *         their IDs
      */
    def readExistingToc(): Future[Map[Checksums.MediumChecksum, InternalMediumEntry]] =
      if Files.isRegularFile(target) then
        val sink =
          Sink.fold[Map[Checksums.MediumChecksum, InternalMediumEntry], InternalMediumEntry](Map.empty): (map, e) =>
            map + (Checksums.MediumChecksum(e.checksum.get) -> e)
        val source = tocFileSource(target, timestamp)
        source.runWith(sink)
      else
        Future.successful(Map.empty)

    /**
      * Writes the new ToC file based on the existing entries and the updated
      * data.
      *
      * @param existingEntries the existing entries from the original ToC
      * @return a [[Future]] with the path to the file that was written
      */
    def writeNewToc(existingEntries: Map[Checksums.MediumChecksum, InternalMediumEntry]): Future[Option[Path]] =
      val checksumMapping = mediaData.filter(e => e._1.mediumDescriptionPath.isDefined).map(_.swap)
      val validChecksums = checksumMapping.keySet -- unusedMedia
      val newMedia = validChecksums -- existingEntries.keySet
      if modifiedMedia.isEmpty && unusedMedia.isEmpty && newMedia.isEmpty then
        Future.successful(None)
      else
        val filteredExistingEntries = existingEntries.filter(e => validChecksums.contains(e._1))
        val tocEntries = (modifiedMedia.filter(checksumMapping.contains) ++ newMedia)
          .foldRight(filteredExistingEntries): (cs, map) =>
            val mid = checksumMapping(cs)
            val newEntry = InternalMediumEntry(
              mediumDescriptionPath = mid.mediumDescriptionPath.get,
              checksum = Some(cs.checksum),
              changedAt = Some(timestamp)
            )
            map + (cs -> newEntry)
          .values.toList.sortBy(e => sortableDescriptionPath(e.mediumDescriptionPath))

        val entriesSource = Source(tocEntries)
        val jsonStage = ListSeparatorStage.jsonStage[InternalMediumEntry]
        val sink = FileIO.toPath(target)
        entriesSource.via(jsonStage).runWith(sink).map(_ => Some(target))

    for
      existingEntries <- readExistingToc()
      result <- writeNewToc(existingEntries)
    yield result

  /**
    * Returns a string for the given path to a medium description file that is
    * used as sort criterion.
    *
    * @param path the original path
    * @return the sanitized path for sorting
    */
  private def sortableDescriptionPath(path: String): String =
    URLDecoder.decode(path.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8)

  /**
    * Converts a [[InternalMediumEntry]] to the new format if necessary. This function
    * is used to do a conversion from the old to new ToC format on the fly. It
    * makes sure that the properties required by the new format are set.
    *
    * @param e         the entry
    * @param timestamp the current modification time
    * @return the converted entry or ''None'' if the entry is invalid
    */
  private def convertLegacyEntry(e: InternalMediumEntry, timestamp: => Long): Option[InternalMediumEntry] =
    if e.checksum.isDefined && e.changedAt.isDefined then
      Some(e)
    else
      e.metaDataPath match
        case Some(path) =>
          val converted = e.copy(
            metaDataPath = None,
            checksum = Some(path.stripSuffix(MetadataFileExtension)),
            changedAt = e.changedAt.orElse(Some(timestamp))
          )
          Some(converted)
        case None => None

  /**
    * Converts the given internal entry to a [[MediumEntry]]. This function
    * expects that all required properties are set, which is achieved by the
    * [[convertLegacyEntry]] function.
    *
    * @param internalEntry the internal entry to be converted
    * @return the resulting entry
    */
  private def toMediumEntry(internalEntry: InternalMediumEntry): MediumEntry =
    MediumEntry(
      mediumDescriptionPath = internalEntry.mediumDescriptionPath,
      checksum = internalEntry.checksum.get,
      changedAt = internalEntry.changedAt.get
    )

  /**
    * A trait for writing a "table of contents" file of a media archive.
    */
  trait ArchiveTocWriter:
    /**
      * Writes the table of contents of a media archive based on the passed in
      * parameters. This is an asynchronous operation. The return value can be
      * used to find out if a file was written (this is only done if necessary)
      * and if the operation was successful. At the given target location, a file
      * may already exist. An implementation should load it, so that it can be
      * updated with modified media. The updated table of contents then replaces
      * the existing one.
      *
      * @param target        the path to the file with the table of contents
      * @param mediaData     a map with all media and their IDs
      * @param unusedMedia   a set with the IDs of unused media files
      * @param modifiedMedia a set with the IDs of media that have been modified
      *                      and need to be updated
      * @param system        the actor system
      * @return a [[Future]] with an optional [[Path]] to the file that was
      *         written; this is ''None'' if nothing was written
      */
    def writeToc(target: Path,
                 mediaData: Map[MediumID, Checksums.MediumChecksum],
                 unusedMedia: Set[Checksums.MediumChecksum],
                 modifiedMedia: Set[Checksums.MediumChecksum])
                (using system: ActorSystem): Future[Option[Path]]

  /**
    * A trait for reading a "table of contents" file of a media archive.
    */
  trait ArchiveTocReader:
    /**
      * Returns a [[Source]] for the entries of a "table of contents" file that
      * is read from a given source. A single entry defines a medium contained 
      * in the archive.
      *
      * @param tocData the raw data of the ToC document
      * @return a [[Source]] for the entries declared in this document
      */
    def readToc(tocData: Source[ByteString, Any]): Source[MediumEntry, Any]
end ArchiveTocSerializer
