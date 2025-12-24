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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.io.stream.ListSeparatorStage
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.Locale
import scala.concurrent.Future

/**
  * A module offering functionality to write the "table of contents" of a media
  * archive.
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
  */
object ArchiveTocWriter:
  /** The file extension used by metadata files. */
  private val MetadataFileExtension = ".mdt"

  /**
    * A data class to represent an entry for a medium in the archive's table of
    * content.
    *
    * @param mediumDescriptionPath the relative path to the medium description 
    *                              file
    * @param checksum              the checksum of the medium
    * @param changedAt             the last modification time of this medium
    * @param metaDataPath          the legacy path to the metadata file used by 
    *                              old entries
    */
  final case class MediumEntry(mediumDescriptionPath: String,
                               checksum: Option[String],
                               changedAt: Option[Long],
                               metaDataPath: Option[String] = None)

  /**
    * The format to serialize and deserialize a [[MediumEntry]] to JSON.
    */
  given mediumEntryFormat: RootJsonFormat[MediumEntry] = jsonFormat4(MediumEntry.apply)

  /**
    * Returns a new instance of [[ArchiveTocWriter]]. An optional [[Clock]] can
    * be provided to compute the change time for modified media.
    *
    * @param clock the [[Clock]] to determine the last-modified time
    * @return the [[ArchiveTocWriter]] instance
    */
  def apply(clock: Clock = Clock.systemDefaultZone()): ArchiveTocWriter =
    new ArchiveTocWriter:
      override def writeToc(target: Path,
                            mediaData: Map[MediumID, Checksums.MediumChecksum],
                            unusedMedia: Set[Checksums.MediumChecksum],
                            modifiedMedia: Set[Checksums.MediumChecksum])
                           (using system: ActorSystem): Future[Option[Path]] =
        if modifiedMedia.isEmpty && unusedMedia.isEmpty then
          Future.successful(None)
        else
          updateTocFile(target, mediaData, unusedMedia, modifiedMedia, clock)

  /**
    * Actually writes the ToC file after changes have been detected.
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
    def readExistingToc(): Future[Map[Checksums.MediumChecksum, MediumEntry]] =
      if Files.isRegularFile(target) then
        val sink = Sink.fold[Map[Checksums.MediumChecksum, MediumEntry], MediumEntry](Map.empty): (map, e) =>
          convertEntry(e, timestamp).fold(map): converted =>
            map + (Checksums.MediumChecksum(converted.checksum.get) -> converted)
        val source = JsonStreamParser.parseStream[MediumEntry, Any](FileIO.fromPath(target))
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
    def writeNewToc(existingEntries: Map[Checksums.MediumChecksum, MediumEntry]): Future[Option[Path]] =
      val checksumMapping = mediaData.filter(e => e._1.mediumDescriptionPath.isDefined).map(_.swap)
      val validChecksums = checksumMapping.keySet -- unusedMedia
      val filteredExistingEntries = existingEntries.filter(e => validChecksums.contains(e._1))
      val tocEntries = modifiedMedia.filter(checksumMapping.contains)
        .foldRight(filteredExistingEntries): (cs, map) =>
          val mid = checksumMapping(cs)
          val newEntry = MediumEntry(
            mediumDescriptionPath = mid.mediumDescriptionPath.get,
            checksum = Some(cs.checksum),
            changedAt = Some(timestamp)
          )
          map + (cs -> newEntry)
        .values.toList.sortBy(e => sortableDescriptionPath(e.mediumDescriptionPath))

      val entriesSource = Source(tocEntries)
      val jsonStage = ListSeparatorStage.jsonStage[MediumEntry]
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
    * Converts a [[MediumEntry]] to the new format if necessary. This function
    * is used to do a conversion from the old to new ToC format on the fly. It
    * makes sure that the properties required by the new format are set.
    *
    * @param e         the entry
    * @param timestamp the current modification time
    * @return the converted entry or ''None'' if the entry is invalid
    */
  private def convertEntry(e: MediumEntry, timestamp: Long): Option[MediumEntry] =
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
end ArchiveTocWriter

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
