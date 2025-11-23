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

package de.oliver_heger.linedj.archive.server.model

import de.oliver_heger.linedj.io.parser.JsonProtocolSupport
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, RootJsonFormat, deserializationError}

/**
  * A module that defines the API model and JSON formats for the archive server
  * API.
  */
object ArchiveModel:
  /**
    * An enumeration defining the supported ways to order the songs on a
    * medium.
    *
    * This can be used to give hints to player applications in which order the
    * songs on a medium should be played, unless the user has explicitly
    * defined a playlist.
    */
  enum OrderMode:
    /**
      * The order mode ''Album''. This means that songs are grouped by the
      * albums they belong to. The groups are ordered by the albums' inception
      * years, and then by the albums' titles. The songs on a single album are
      * ordered by the track numbers. So, this ordering can be used to bring
      * songs in a chronological order.
      */
    case Album

    /**
      * The order mode ''Artist''. This means that songs are grouped by their
      * artists, the groups are sorted alphabetically by the artist name. The
      * songs within a group are sorted via [[Album]] ordering.
      */
    case Artist

    /**
      * The order mode ''Medium''. This means that songs are ordered by their
      * URLs. It does not depend on the metadata for songs, but is derived from
      * the structure of the medium.
      */
    case Medium

    /**
      * The order mode ''random albums''. This means that songs are grouped by
      * the albums they belong to and ordered by their track number. But the
      * albums are played in random order.
      */
    case RandomAlbums

    /**
      * The order mode ''random artists''. This means that songs are grouped by
      * the artist they belong to and played in the order as defined by
      * [[Artist]]. The artists themselves are played in random order.
      */
    case RandomArtists

    /**
      * The order mode ''random songs''. This means a random order of all songs
      * contained on the medium.
      */
    case RandomSongs

  /**
    * A data class with overview information about a medium.
    *
    * This class is used to return information about multiple media. Therefore,
    * it contains only limited properties to restrict the size of responses.
    *
    * @param id    the ID of the medium which is derived from its checksum
    * @param title the title of the medium
    */
  final case class MediumOverview(id: Checksums.MediumChecksum,
                                  title: String)

  /**
    * A data class containing overview information about all known media.
    *
    * An instance of this class is returned to clients asking for all media.
    *
    * @param media a list with overview information about all media
    */
  final case class MediaOverview(media: List[MediumOverview])

  /**
    * A data class to fully describe a medium.
    *
    * An instance contains all the information available for a medium. This
    * class is used when a user requests the details about a medium.
    *
    * @param overview    the object with the overview properties
    * @param description a description for this medium
    * @param orderMode   the default order mode for playing the songs on the
    *                    medium if defined
    */
  final case class MediumDetails(overview: MediumOverview,
                                 description: String,
                                 orderMode: Option[OrderMode]):
    export overview.*

  /**
    * A data class holding information about an artist found on a medium.
    *
    * @param id         a unique ID to identify this artist
    * @param artistName the name of this artist
    */
  final case class ArtistInfo(id: String,
                              artistName: String)

  /**
    * A data class holding information about an album found on a medium.
    *
    * @param id        a unique ID to identify this album
    * @param albumName the name of this album
    */
  final case class AlbumInfo(id: String,
                             albumName: String)

  /**
    * A data class to represent a generic result consisting of a list of items.
    *
    * @param items the items making up the result
    * @tparam DATA the type of the items in this result
    */
  final case class ItemsResult[DATA](items: List[DATA])

  /**
    * A data class collecting all relevant information about a media file. This
    * includes information about the location of the file.
    *
    * @param metadata     the metadata about the file
    * @param relativePath the relative path of this file in the archive
    * @param mediumID     the ID of the medium that contains this file
    */
  final case class MediaFileInfo(metadata: MediaMetadata,
                                 relativePath: String,
                                 mediumID: Checksums.MediumChecksum)

  /**
    * A trait providing JSON converters for the classes of the archive data
    * model. By mixing in this trait, classes can get capabilities to do JSON
    * serialization with model classes.
    */
  trait ArchiveJsonSupport extends SprayJsonSupport with DefaultJsonProtocol:
    given mediumChecksumFormat: RootJsonFormat[Checksums.MediumChecksum] =
      new RootJsonFormat[Checksums.MediumChecksum]:
        override def write(obj: Checksums.MediumChecksum): JsValue = JsString(obj.checksum)

        override def read(json: JsValue): Checksums.MediumChecksum =
          json match
            case JsString(value) => Checksums.MediumChecksum(value)
            case o => deserializationError(s"String expected, but got '$o'.")

    given orderModeFormat: RootJsonFormat[OrderMode] = JsonProtocolSupport.enumFormat[OrderMode](OrderMode.valueOf)

    given mediumOverviewFormat: RootJsonFormat[MediumOverview] = jsonFormat2(MediumOverview.apply)

    given mediaOverviewFormat: RootJsonFormat[MediaOverview] = jsonFormat1(MediaOverview.apply)

    given mediumDetailsFormat: RootJsonFormat[MediumDetails] = jsonFormat3(MediumDetails.apply)

    given artistInfoFormat: RootJsonFormat[ArtistInfo] = jsonFormat2(ArtistInfo.apply)

    given albumInfoFormat: RootJsonFormat[AlbumInfo] = jsonFormat2(AlbumInfo.apply)

    given mediaMetadataFormat: RootJsonFormat[MediaMetadata] = jsonFormat9(MediaMetadata.apply)

    given itemsResultFormat[DATA: JsonFormat]: RootJsonFormat[ItemsResult[DATA]] =
      jsonFormat1(ItemsResult[DATA].apply)

    given mediaFileInfoFormat: RootJsonFormat[MediaFileInfo] = jsonFormat3(MediaFileInfo.apply)
  end ArchiveJsonSupport
